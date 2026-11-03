package io.appform.dropwizard.sharding.observers;

import io.appform.dropwizard.sharding.dao.operations.OpContext;
import io.appform.dropwizard.sharding.dao.operations.SaveWithParent;
import io.appform.dropwizard.sharding.execution.TransactionExecutionContext;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;

import java.util.function.Supplier;

/**
 * Observer that handles {@code @CopyFromParent} field processing for child entities
 * saved within a {@link io.appform.dropwizard.sharding.dao.LockedContext}.
 * <p>
 * This observer is opt-in. Register it during bundle initialization:
 *
 * <pre>
 *     dbShardingBundle.registerObserver(CopyFromParentObserver.builder()
 *         .copyEnabled(true) //static
 *         .mismatchDetectionEnabled(true) //static
 *         .copyIfDefaultOnly(true) //static
 *         .configSupplier(() -&gt; configProviderBundle.getDataProvider().getData().getCopyFromParentObserverConfig())
 *         .mismatchListener(() -&gt; guiceBundle.getInjector().getInstance(CopyFromParentMismatchListener.class))
 *         .build());
 * </pre>
 * <p>
 * Configuration options:
 * Priority: config > static > default
 * <ul>
 *     <li>{@code copyEnabled} — whether to copy {@code @CopyFromParent(override=true)} fields
 *         from parent to child before persistence (default: {@code true})</li>
 *     <li>{@code copyIfDefaultOnly} — when {@code true}, copy only if child field has default value,
 *         otherwise fail the operation. Used for migration validation. (default: {@code false})</li>
 *     <li>{@code mismatchDetectionEnabled} — whether to detect mismatches between parent and
 *         child field values before copying (default: {@code false})</li>
 *     <li>{@code mismatchListener} — lazy supplier for the callback invoked when mismatches are detected.
 *         Required whenever {@code mismatchDetectionEnabled} resolves to {@code true}, whether from static or
 *         dynamic config. It is invoked the first time mismatch detection is needed; the resolved listener is
 *         cached from then on.</li>
 *     <li>{@code configSupplier} — supplier for dynamic configuration. It is invoked on <b>every</b>
 *         {@link SaveWithParent} transaction (and on no other operation), so a change in the returned values takes
 *         effect from the next such transaction. Keep it cheap, e.g. a read of an in-memory config snapshot.</li>
 * </ul>
 * <p>
 * Runtime semantics:
 * <ul>
 *     <li>The flags are resolved per {@link SaveWithParent} transaction. The current {@link CopyFromParentPersistor}
 *         is reused while its flags match the resolved ones; on any difference a new persistor is created (under a
 *         lock, double-checked). Each transaction is always processed with a persistor matching the flags it
 *         resolved.</li>
 *     <li>If {@code configSupplier} throws, the last resolved configuration is kept. If there is none yet (first
 *         transaction), static/default values are used.</li>
 *     <li>Configuration errors fail fast, regardless of their source: if {@code mismatchDetectionEnabled} resolves
 *         to {@code true} and no listener can be obtained (no supplier, supplier throws, or supplier returns
 *         {@code null}) the transaction fails before the save is executed, instead of silently skipping detection.
 *         Failures are not cached; the next transaction retries.</li>
 * </ul>
 *
 * @see CopyFromParentMismatchListener
 * @see io.appform.dropwizard.sharding.utils.CopyFromParentUtils
 */
@Slf4j
public class CopyFromParentObserver extends TransactionObserver {

    static final String LISTENER_REQUIRED_MESSAGE =
            "mismatchListener must be provided when mismatchDetectionEnabled is true";

    // For static configuration (values set at initialization via builder)
    private final Boolean staticCopyEnabled;
    private final Boolean staticMismatchDetectionEnabled;
    private final Boolean staticCopyIfDefaultOnly;

    // For dynamic configuration (values fetched on every SaveWithParent transaction)
    private final Supplier<CopyFromParentObserverConfig> configSupplier;

    private final Supplier<CopyFromParentMismatchListener> mismatchListenerSupplier;

    // Persistor for the most recently resolved configuration. Never mutated, only replaced.
    private volatile CopyFromParentPersistor persistor;

    // Resolved lazily, the first time mismatch detection is needed. Guarded by "this".
    private CopyFromParentMismatchListener mismatchListener;

    private CopyFromParentObserver(Boolean staticCopyEnabled,
                                   Boolean staticMismatchDetectionEnabled,
                                   Boolean staticCopyIfDefaultOnly,
                                   Supplier<CopyFromParentObserverConfig> configSupplier,
                                   Supplier<CopyFromParentMismatchListener> mismatchListenerSupplier) {
        super(null);
        this.staticCopyEnabled = staticCopyEnabled;
        this.staticMismatchDetectionEnabled = staticMismatchDetectionEnabled;
        this.staticCopyIfDefaultOnly = staticCopyIfDefaultOnly;
        this.configSupplier = configSupplier;
        this.mismatchListenerSupplier = mismatchListenerSupplier;
    }

    /**
     * Configuration object that needs to be provided by external config classes.
     */
    public interface CopyFromParentObserverConfig {
        Boolean getCopyEnabled();
        Boolean getMismatchDetectionEnabled();
        Boolean getCopyIfDefaultOnly();
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public <T> T execute(TransactionExecutionContext context, Supplier<T> supplier) {
        final OpContext<?> opContext = context.getOpContext();
        // Only SaveWithParent is processed. Skipping everything else up-front avoids resolving config.
        if (opContext instanceof SaveWithParent) {
            opContext.visit(getPersistor());
        }
        return proceed(context, supplier);
    }

    /**
     * @return a persistor whose flags match the currently resolved configuration.
     */
    CopyFromParentPersistor getPersistor() {
        final ResolvedConfig config;
        try {
            config = resolveConfig(configSupplier != null ? configSupplier.get() : null);
        } catch (Exception e) {
            final CopyFromParentPersistor last = persistor;
            if (last != null) {
                log.warn("Failed to fetch dynamic config, continuing with the last resolved config", e);
                return last;
            }
            log.warn("Failed to fetch dynamic config, falling back to static/default", e);
            return getOrCreatePersistor(resolveConfig(null));
        }
        return getOrCreatePersistor(config);
    }

    private CopyFromParentPersistor getOrCreatePersistor(ResolvedConfig config) {
        final CopyFromParentPersistor current = persistor;
        if (config.matches(current)) {
            return current;
        }
        synchronized (this) {
            final CopyFromParentPersistor latest = persistor;
            if (config.matches(latest)) {
                return latest;
            }
            final CopyFromParentPersistor created = new CopyFromParentPersistor(
                    config.isCopyEnabled(),
                    config.isMismatchDetectionEnabled(),
                    config.isCopyIfDefaultOnly(),
                    config.isMismatchDetectionEnabled() ? getMismatchListener() : null);
            persistor = created;
            log.info("CopyFromParentPersistor {} with copyEnabled={}, mismatchDetectionEnabled={}, copyIfDefaultOnly={}",
                    latest == null ? "initialized" : "re-created on config change",
                    config.isCopyEnabled(), config.isMismatchDetectionEnabled(), config.isCopyIfDefaultOnly());
            return created;
        }
    }

    /**
     * Resolves each flag in order of precedence: dynamic value (if non-null), static value (if non-null), default.
     */
    private ResolvedConfig resolveConfig(CopyFromParentObserverConfig dynamicConfig) {
        return new ResolvedConfig(
                resolveConfigValue(dynamicConfig != null ? dynamicConfig.getCopyEnabled() : null,
                        staticCopyEnabled, true),
                resolveConfigValue(dynamicConfig != null ? dynamicConfig.getMismatchDetectionEnabled() : null,
                        staticMismatchDetectionEnabled, false),
                resolveConfigValue(dynamicConfig != null ? dynamicConfig.getCopyIfDefaultOnly() : null,
                        staticCopyIfDefaultOnly, false));
    }

    private static boolean resolveConfigValue(Boolean dynamicValue, Boolean staticValue, boolean defaultValue) {
        if (dynamicValue != null) {
            return dynamicValue;
        }
        if (staticValue != null) {
            return staticValue;
        }
        return defaultValue;
    }

    /**
     * Returns the cached listener, resolving it on first use. Must be called while holding the lock on "this".
     * Any problem is a configuration error and is thrown (failing the transaction) rather than disabling detection;
     * nothing is cached on failure, so the next transaction retries.
     */
    private CopyFromParentMismatchListener getMismatchListener() {
        if (mismatchListener == null) {
            mismatchListener = resolveMismatchListener();
        }
        return mismatchListener;
    }

    private CopyFromParentMismatchListener resolveMismatchListener() {
        if (mismatchListenerSupplier == null) {
            throw new IllegalArgumentException(LISTENER_REQUIRED_MESSAGE);
        }
        final CopyFromParentMismatchListener resolved;
        try {
            resolved = mismatchListenerSupplier.get();
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Failed to resolve CopyFromParentMismatchListener while mismatchDetectionEnabled is true", e);
        }
        if (resolved == null) {
            throw new IllegalStateException(
                    "mismatchListener supplier returned null while mismatchDetectionEnabled is true");
        }
        return resolved;
    }

    @Value
    private static class ResolvedConfig {
        boolean copyEnabled;
        boolean mismatchDetectionEnabled;
        boolean copyIfDefaultOnly;

        boolean matches(CopyFromParentPersistor candidate) {
            return candidate != null
                    && candidate.isCopyEnabled() == copyEnabled
                    && candidate.isMismatchDetectionEnabled() == mismatchDetectionEnabled
                    && candidate.isCopyIfDefaultOnly() == copyIfDefaultOnly;
        }
    }

    public static class Builder {
        private Boolean copyEnabled;
        private Boolean mismatchDetectionEnabled;
        private Boolean copyIfDefaultOnly;
        private Supplier<CopyFromParentObserverConfig> configSupplier;
        private Supplier<CopyFromParentMismatchListener> mismatchListenerSupplier;

        private Builder() {}

        /**
         * Supplier for dynamic configuration, invoked on every {@link SaveWithParent} transaction. Non-null values it
         * returns take precedence over the static values set on this builder.
         */
        public Builder configSupplier(Supplier<CopyFromParentObserverConfig> configSupplier) {
            this.configSupplier = configSupplier;
            return this;
        }

        public Builder copyEnabled(boolean copyEnabled) {
            this.copyEnabled = copyEnabled;
            return this;
        }

        public Builder mismatchDetectionEnabled(boolean mismatchDetectionEnabled) {
            this.mismatchDetectionEnabled = mismatchDetectionEnabled;
            return this;
        }

        /**
         * Enable strict default-value checking during copy.
         * When {@code true}, fields are only copied if the child field has a default value
         * (null for references, 0/false for primitives). If a non-default value is found,
         * the operation fails.
         * <p>
         * This is intended for migration validation to detect lingering manual setters.
         * After migration is complete, this should be set to {@code false}.
         * Default: {@code false}.
         * <p>
         * This sets a static value. If {@link #configSupplier} is also provided,
         * the dynamic config value takes precedence.
         */
        public Builder copyIfDefaultOnly(boolean copyIfDefaultOnly) {
            this.copyIfDefaultOnly = copyIfDefaultOnly;
            return this;
        }

        /**
         * Lazy supplier for the mismatch listener. Invoked the first time mismatch detection is needed; the resolved
         * listener is then cached.
         * <p>
         * Use a lazy supplier to defer resolution until after framework initialization
         * (e.g. after a Guice injector is created):
         * <pre>
         *     .mismatchListener(() -&gt; guiceBundle.getInjector().getInstance(MyListener.class))
         * </pre>
         */
        public Builder mismatchListener(Supplier<CopyFromParentMismatchListener> mismatchListenerSupplier) {
            this.mismatchListenerSupplier = mismatchListenerSupplier;
            return this;
        }

        public CopyFromParentObserver build() {
            // Validation: if static mismatchDetectionEnabled is true, must have listener
            if (Boolean.TRUE.equals(mismatchDetectionEnabled) && mismatchListenerSupplier == null) {
                throw new IllegalArgumentException(LISTENER_REQUIRED_MESSAGE);
            }

            return new CopyFromParentObserver(copyEnabled, mismatchDetectionEnabled,
                    copyIfDefaultOnly, configSupplier, mismatchListenerSupplier);
        }
    }
}

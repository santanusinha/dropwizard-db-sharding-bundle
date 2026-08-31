package io.appform.dropwizard.sharding.observers;

import io.appform.dropwizard.sharding.execution.TransactionExecutionContext;
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
 *     <li>{@code mismatchListener} — lazy supplier for the callback invoked when mismatches
 *         are detected. Required when {@code mismatchDetectionEnabled} is {@code true}.
 *         The supplier is resolved once on the first database transaction.</li>
 *     <li>{@code configSupplier} — lazy supplier for dynamic configuration. When provided,
 *         config values are fetched at runtime.</li>
 * </ul>
 *
 * @see CopyFromParentMismatchListener
 * @see io.appform.dropwizard.sharding.utils.CopyFromParentUtils
 */
@Slf4j
public class CopyFromParentObserver extends TransactionObserver {

    // For static configuration (values set at initialization via builder)
    private final Boolean staticCopyEnabled;
    private final Boolean staticMismatchDetectionEnabled;
    private final Boolean staticCopyIfDefaultOnly;
    
    // For dynamic configuration (values fetched at runtime from config supplier)
    private final Supplier<CopyFromParentObserverConfig> configSupplier;
    
    private final Supplier<CopyFromParentMismatchListener> mismatchListenerSupplier;
    private volatile CopyFromParentPersistor persistor;

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
        context.getOpContext().visit(getPersistor());
        return proceed(context, supplier);
    }

    private CopyFromParentPersistor getPersistor() {
        if (persistor == null) {
            synchronized (this) {
                if (persistor == null) {
                    persistor = initializePersistor();
                }
            }
        }
        return persistor;
    }

    private CopyFromParentPersistor initializePersistor() {
        CopyFromParentObserverConfig dynamicConfig = fetchDynamicConfig();

        boolean copyEnabled = resolveConfigValue(
                dynamicConfig != null ? dynamicConfig.getCopyEnabled() : null,
                staticCopyEnabled,
                true);

        boolean mismatchDetectionEnabled = resolveConfigValue(
                dynamicConfig != null ? dynamicConfig.getMismatchDetectionEnabled() : null,
                staticMismatchDetectionEnabled,
                false);

        boolean copyIfDefaultOnly = resolveConfigValue(
                dynamicConfig != null ? dynamicConfig.getCopyIfDefaultOnly() : null,
                staticCopyIfDefaultOnly,
                false);

        CopyFromParentMismatchListener listener = resolveMismatchListener(mismatchDetectionEnabled);

        CopyFromParentPersistor result = new CopyFromParentPersistor(copyEnabled, mismatchDetectionEnabled,
                copyIfDefaultOnly, listener);

        log.info("Initialized CopyFromParentPersistor with copyEnabled={}, mismatchDetectionEnabled={}, copyIfDefaultOnly={}",
                copyEnabled, mismatchDetectionEnabled, copyIfDefaultOnly);

        return result;
    }

    private CopyFromParentObserverConfig fetchDynamicConfig() {
        if (configSupplier == null) {
            return null;
        }
        try {
            return configSupplier.get();
        } catch (Exception e) {
            log.warn("Failed to fetch dynamic config, falling back to static/default", e);
            return null;
        }
    }

    private CopyFromParentMismatchListener resolveMismatchListener(boolean mismatchDetectionEnabled) {
        if (!mismatchDetectionEnabled) {
            return null;
        }
        if (mismatchListenerSupplier == null) {
            throw new IllegalArgumentException(
                    "mismatchListener must be provided when mismatchDetectionEnabled is true");
        }
        try {
            return mismatchListenerSupplier.get();
        } catch (Exception e) {
            log.error("Failed to resolve CopyFromParentMismatchListener, "
                    + "mismatch detection will be disabled", e);
            return null;
        }
    }

    /**
     * Resolves a configuration value in order of precedence:
     * 1. Dynamic value (if non-null)
     * 2. Static value (if non-null)
     * 3. Default value
     */
    private boolean resolveConfigValue(Boolean dynamicValue, Boolean staticValue, boolean defaultValue) {
        if (dynamicValue != null) {
            return dynamicValue;
        }

        if (staticValue != null) {
            return staticValue;
        }

        return defaultValue;
    }

    public static class Builder {
        private Boolean copyEnabled;
        private Boolean mismatchDetectionEnabled;
        private Boolean copyIfDefaultOnly;
        private Supplier<CopyFromParentObserverConfig> configSupplier;
        private Supplier<CopyFromParentMismatchListener> mismatchListenerSupplier;

        private Builder() {}

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
         * an error is logged and the copy is skipped for that field.
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
         * Lazy supplier for the mismatch listener. Resolved once on first database transaction.
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
                throw new IllegalArgumentException(
                        "mismatchListener must be provided when mismatchDetectionEnabled is true");
            }
            
            return new CopyFromParentObserver(copyEnabled, mismatchDetectionEnabled,
                    copyIfDefaultOnly, configSupplier, mismatchListenerSupplier);
        }
    }
}

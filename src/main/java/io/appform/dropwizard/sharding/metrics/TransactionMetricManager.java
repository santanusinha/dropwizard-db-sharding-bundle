package io.appform.dropwizard.sharding.metrics;

import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.SlidingTimeWindowArrayReservoir;
import com.codahale.metrics.Timer;
import com.google.common.base.Strings;
import io.appform.dropwizard.sharding.config.MetricConfig;
import io.appform.dropwizard.sharding.dao.LockedContext;
import io.appform.dropwizard.sharding.dao.operations.lockedcontext.LockAndExecute;
import io.appform.dropwizard.sharding.execution.TransactionExecutionContext;
import lombok.val;

import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

public class TransactionMetricManager {

    private static final String METRIC_PREFIX = "db.sharding";
    private static final String DELIMITER = ".";
    private static final String DELIMITER_REPLACEMENT = "_";
    private final Supplier<MetricConfig> metricConfigSupplier;
    private final MetricRegistry metricRegistry;

    public TransactionMetricManager(final Supplier<MetricConfig> metricConfigSupplier,
                                    final MetricRegistry metricRegistry) {
        this.metricConfigSupplier = metricConfigSupplier;
        this.metricRegistry = metricRegistry;
    }

    public boolean isMetricApplicable(final Class<?> entityClass) {
        if (metricConfigSupplier == null) {
            return false;
        }
        val metricConfig = metricConfigSupplier.get();
        if (metricConfig == null) {
            return false;
        }

        if (metricConfig.isEnabledForAll()) {
            return true;
        }

        return metricConfig.getEnabledForEntities() != null
                && metricConfig.getEnabledForEntities().contains(entityClass.getCanonicalName());
    }

    public MetricData getShardMetricData(final String shardName) {
        val metricPrefix = getMetricPrefix("shard", shardName);
        return getMetricData(metricPrefix);
    }

    public MetricData getEntityOpMetricData(final TransactionExecutionContext context) {
        val lockedContextMode = context.getOpContext() instanceof LockAndExecute ?
            ((LockAndExecute<?>)context.getOpContext()).getMode().name() : null;
        val metricPrefix = getMetricPrefix("entity", context.getEntityClass().getCanonicalName(),
                context.getDaoClass().getCanonicalName(),
                context.getCommandName(),
                lockedContextMode);
        return getMetricData(metricPrefix);
    }

    private String getMetricPrefix(String... metricNames) {
        val metricPrefix = new StringBuilder(METRIC_PREFIX);
        for (val metricName : metricNames) {
            if (Strings.isNullOrEmpty(metricName)) {
                continue;
            }
            metricPrefix.append(DELIMITER)
                    .append(normalizeString(metricName));
        }
        return metricPrefix.toString();
    }

    private MetricData getMetricData(final String metricPrefix) {
        return MetricData.builder()
                .timer(metricRegistry.timer(MetricRegistry.name(metricPrefix, "latency"),
                        () -> new Timer(new SlidingTimeWindowArrayReservoir(60, TimeUnit.SECONDS))))
                .success(metricRegistry.meter(MetricRegistry.name(metricPrefix, "success")))
                .failed(metricRegistry.meter(MetricRegistry.name(metricPrefix, "failed")))
                .total(metricRegistry.meter(MetricRegistry.name(metricPrefix, "total")))
                .build();
    }

    private String normalizeString(final String name) {
        return name.replace(DELIMITER, DELIMITER_REPLACEMENT);
    }

}

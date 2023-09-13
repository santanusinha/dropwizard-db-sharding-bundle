package io.appform.dropwizard.sharding.metrics;

import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.SlidingTimeWindowArrayReservoir;
import com.codahale.metrics.Timer;
import com.google.common.base.Strings;
import io.appform.dropwizard.sharding.config.MetricConfig;
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

    public String getDaoMetricPrefix(final Class<?> daoClass) {
        return METRIC_PREFIX + DELIMITER
                + "operation"
                + DELIMITER
                + normalizeString(daoClass.getCanonicalName());
    }

    public MetricData getDaoOpMetricData(final String metricPrefix,
                                         final TransactionExecutionContext context) {
        val metricBuilder = new StringBuilder(metricPrefix)
                .append(DELIMITER)
                .append(normalizeString(context.getOpType()));
        if (!Strings.isNullOrEmpty(context.getLockedContextMode())) {
            metricBuilder.append(DELIMITER).append(context.getLockedContextMode());
        }
        return getMetricData(metricBuilder.toString());
    }

    public MetricData getShardMetricData(final String shardName) {
        val metricPrefix = METRIC_PREFIX + DELIMITER
                + "shard"
                + DELIMITER
                + normalizeString(shardName);
        return getMetricData(metricPrefix);
    }

    public MetricData getEntityMetricData(final Class<?> entityClass) {
        val metricPrefix = METRIC_PREFIX + DELIMITER
                + "entity"
                + DELIMITER
                + normalizeString(entityClass.getCanonicalName());
        return getMetricData(metricPrefix);
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

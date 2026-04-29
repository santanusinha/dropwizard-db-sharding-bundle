package io.appform.dropwizard.sharding.metrics;

import com.codahale.metrics.Timer;
import io.appform.dropwizard.sharding.dao.operations.lockedcontext.LockAndExecute;
import io.appform.dropwizard.sharding.execution.TransactionExecutionContext;
import io.appform.dropwizard.sharding.observers.TransactionObserver;
import lombok.Getter;
import lombok.val;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.stream.Collectors;

public class TransactionMetricObserver extends TransactionObserver {
    private final TransactionMetricManager metricManager;

    @Getter
    private final Map<EntityOpMetricKey, MetricData> entityOpMetricCache = new ConcurrentHashMap<>();

    @Getter
    private final Map<String, MetricData> shardMetricCache = new ConcurrentHashMap<>();

    public TransactionMetricObserver(final TransactionMetricManager metricManager) {
        super(null);
        this.metricManager = metricManager;
    }

    @Override
    public <T> T execute(TransactionExecutionContext context, Supplier<T> supplier) {
        if (!metricManager.isMetricApplicable(context.getEntityClass())) {
            return proceed(context, supplier);
        }
        val metrics = getMetrics(context);

        val timerContexts = metrics.stream().map(metric -> metric.getTimer().time()).collect(Collectors.toList());
        metrics.forEach(metric -> metric.getTotal().mark());

        try {
            val response = proceed(context, supplier);
            metrics.forEach(metric -> metric.getSuccess().mark());
            return response;
        } catch (Throwable t) {
            metrics.forEach(metric -> metric.getFailed().mark());
            throw t;
        } finally {
            timerContexts.forEach(Timer.Context::stop);
        }
    }

    private List<MetricData> getMetrics(final TransactionExecutionContext context) {
        val entityOpMetricData = entityOpMetricCache.computeIfAbsent(EntityOpMetricKey.builder()
                        .lockedContextMode(context.getOpContext() instanceof LockAndExecute ?
                                ((LockAndExecute<?>) context.getOpContext()).getMode().name() : null)
                        .commandName(context.getCommandName())
                        .daoType(context.getDaoType())
                        .entityClass(context.getEntityClass())
                        .build(),
                key -> metricManager.getEntityOpMetricData(context));
        val shardMetricData = shardMetricCache.computeIfAbsent(context.getShardName(),
                key -> metricManager.getShardMetricData(context.getShardName()));
        return List.of(entityOpMetricData, shardMetricData);
    }
}

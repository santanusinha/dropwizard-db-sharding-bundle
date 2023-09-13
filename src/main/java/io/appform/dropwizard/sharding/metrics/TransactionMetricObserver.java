package io.appform.dropwizard.sharding.metrics;

import com.codahale.metrics.Timer;
import com.google.common.collect.Lists;
import io.appform.dropwizard.sharding.execution.TransactionExecutionContext;
import io.appform.dropwizard.sharding.observers.TransactionObserver;
import lombok.Getter;
import lombok.val;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.stream.Collectors;

public class TransactionMetricObserver extends TransactionObserver {
    private final TransactionMetricManager metricManager;

    @Getter
    private final Map<Class<?>, MetricData> entityMetricCache = new ConcurrentHashMap<>();

    @Getter
    private final Map<String, MetricData> shardMetricCache = new ConcurrentHashMap<>();

    @Getter
    private final Map<String, Map<String, MetricData>> daoToOpTypeMetricCache = new ConcurrentHashMap<>();

    @Getter
    private final Map<Class<?>, String> daoMetricPrefixCache = new ConcurrentHashMap<>();

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
        val entityMetricData = entityMetricCache.computeIfAbsent(context.getEntityClass(),
                key -> metricManager.getEntityMetricData(context.getEntityClass()));
        val shardMetricData = shardMetricCache.computeIfAbsent(context.getShardName(),
                key -> metricManager.getShardMetricData(context.getShardName()));
        val daoMetricData = getDaoMetricData(context);
        return Lists.newArrayList(entityMetricData, shardMetricData, daoMetricData);
    }

    private MetricData getDaoMetricData(final TransactionExecutionContext context) {
        val daoClass = context.getDaoClass();
        val daoMetricPrefix = daoMetricPrefixCache.computeIfAbsent(daoClass, key -> {
            val prefix = metricManager.getDaoMetricPrefix(daoClass);
            daoToOpTypeMetricCache.put(prefix, new HashMap<>());
            return prefix;
        });
        val opType = context.getOpType();
        return daoToOpTypeMetricCache.get(daoMetricPrefix).computeIfAbsent(opType,
                key -> metricManager.getDaoOpMetricData(daoMetricPrefix, context));
    }
}

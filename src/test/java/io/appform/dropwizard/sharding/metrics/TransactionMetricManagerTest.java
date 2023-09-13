package io.appform.dropwizard.sharding.metrics;

import com.codahale.metrics.MetricRegistry;
import com.google.common.collect.ImmutableSet;
import io.appform.dropwizard.sharding.config.MetricConfig;
import io.appform.dropwizard.sharding.execution.TransactionExecutionContext;
import lombok.val;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class TransactionMetricManagerTest {

    @Test
    public void testIsMetricApplicable() {
        TransactionMetricManager metricManager = new TransactionMetricManager(null, null);
        assertFalse(metricManager.isMetricApplicable(this.getClass()));

        metricManager = new TransactionMetricManager(() -> null, null);
        assertFalse(metricManager.isMetricApplicable(this.getClass()));

        metricManager = new TransactionMetricManager(() -> MetricConfig.builder().enabledForAll(true).build(), null);
        assertTrue(metricManager.isMetricApplicable(this.getClass()));

        metricManager = new TransactionMetricManager(() -> MetricConfig.builder().enabledForAll(false)
                .enabledForEntities(ImmutableSet.of(this.getClass().getCanonicalName()))
                .build(), null);
        assertTrue(metricManager.isMetricApplicable(this.getClass()));

        metricManager = new TransactionMetricManager(() -> MetricConfig.builder().enabledForAll(false)
                .enabledForEntities(ImmutableSet.of(this.getClass().getSimpleName()))
                .build(), null);
        assertFalse(metricManager.isMetricApplicable(this.getClass()));

        metricManager = new TransactionMetricManager(() -> MetricConfig.builder().enabledForAll(false)
                .build(), null);
        assertFalse(metricManager.isMetricApplicable(this.getClass()));
    }

    @Test
    public void testGetDaoMetricPrefix() {
        val metricManager = new TransactionMetricManager(null, null);
        val metricPrefix = metricManager.getDaoMetricPrefix(this.getClass());
        assertEquals("db.sharding.operation.io_appform_dropwizard_sharding_metrics_TransactionMetricManagerTest",
                metricPrefix);
    }

    @Test
    public void testGetDaoOpMetricData() {
        val metricRegistry = new MetricRegistry();
        val metricManager = new TransactionMetricManager(null, metricRegistry);
        val metricPrefix = "test";
        val context = TransactionExecutionContext.builder()
                .opType("save")
                .build();
        val metricData = metricManager.getDaoOpMetricData(metricPrefix, context);
        val metrics = metricRegistry.getMetrics();
        assertEquals(4, metrics.size());
        assertEquals(metrics.get(metricPrefix + "." + "save.latency"), metricData.getTimer());
        assertEquals(metrics.get(metricPrefix + "." + "save.total"), metricData.getTotal());
        assertEquals(metrics.get(metricPrefix + "." + "save.success"), metricData.getSuccess());
        assertEquals(metrics.get(metricPrefix + "." + "save.failed"), metricData.getFailed());
    }

    @Test
    public void testGetDaoOpMetricDataWithLockedContextMode() {
        val metricRegistry = new MetricRegistry();
        val metricManager = new TransactionMetricManager(null, metricRegistry);
        val metricPrefix = "test";
        val context = TransactionExecutionContext.builder()
                .opType("save")
                .lockedContextMode("read")
                .build();
        val metricData = metricManager.getDaoOpMetricData(metricPrefix, context);
        val metrics = metricRegistry.getMetrics();
        assertEquals(4, metrics.size());
        assertEquals(metrics.get(metricPrefix + "." + "save.read.latency"), metricData.getTimer());
        assertEquals(metrics.get(metricPrefix + "." + "save.read.total"), metricData.getTotal());
        assertEquals(metrics.get(metricPrefix + "." + "save.read.success"), metricData.getSuccess());
        assertEquals(metrics.get(metricPrefix + "." + "save.read.failed"), metricData.getFailed());
    }

    @Test
    public void testGetShardMetricData() {
        val metricRegistry = new MetricRegistry();
        val metricManager = new TransactionMetricManager(null, metricRegistry);
        val shardName = "test";
        val metricData = metricManager.getShardMetricData(shardName);
        val metrics = metricRegistry.getMetrics();
        assertEquals(4, metrics.size());
        val metricPrefix = "db.sharding.shard." + shardName + ".";
        assertEquals(metrics.get(metricPrefix + "latency"), metricData.getTimer());
        assertEquals(metrics.get(metricPrefix + "total"), metricData.getTotal());
        assertEquals(metrics.get(metricPrefix + "success"), metricData.getSuccess());
        assertEquals(metrics.get(metricPrefix + "failed"), metricData.getFailed());
    }

    @Test
    public void testGetEntityMetricData() {
        val metricRegistry = new MetricRegistry();
        val metricManager = new TransactionMetricManager(null, metricRegistry);
        val entityClass = this.getClass();
        val metricData = metricManager.getEntityMetricData(entityClass);
        val metrics = metricRegistry.getMetrics();
        assertEquals(4, metrics.size());

        val metricPrefix = "db.sharding.entity.io_appform_dropwizard_sharding_metrics_TransactionMetricManagerTest.";
        assertEquals(metrics.get(metricPrefix + "latency"), metricData.getTimer());
        assertEquals(metrics.get(metricPrefix + "total"), metricData.getTotal());
        assertEquals(metrics.get(metricPrefix + "success"), metricData.getSuccess());
        assertEquals(metrics.get(metricPrefix + "failed"), metricData.getFailed());
    }
}

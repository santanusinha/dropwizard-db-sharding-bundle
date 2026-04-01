package io.appform.dropwizard.sharding.metrics;

import com.codahale.metrics.MetricRegistry;
import com.google.common.collect.ImmutableSet;
import io.appform.dropwizard.sharding.config.MetricConfig;
import io.appform.dropwizard.sharding.dao.operations.lockedcontext.LockAndExecute;
import io.appform.dropwizard.sharding.execution.DaoType;
import io.appform.dropwizard.sharding.execution.TransactionExecutionContext;
import lombok.val;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TransactionMetricManagerTest {

    @Test
    void testIsMetricApplicable() {
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
    void testGetShardMetricData() {
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
    void testGetEntityOpMetricData() {
        val context = TransactionExecutionContext.builder()
                .entityClass(this.getClass())
                .daoType(DaoType.LOOKUP)
                .commandName("read")
                .shardName("testshard1")
                .opContext(LockAndExecute.<String>buildForRead().getter(() -> null).build())
                .build();
        val metricRegistry = new MetricRegistry();
        val metricManager = new TransactionMetricManager(null, metricRegistry);
        val metricData = metricManager.getEntityOpMetricData(context);
        val metrics = metricRegistry.getMetrics();
        assertEquals(4, metrics.size());

        val metricPrefix = "db.sharding.entity.io_appform_dropwizard_sharding_metrics_TransactionMetricManagerTest."
                + "io_appform_dropwizard_sharding_dao_LookupDao"
                + ".read." + "READ.";
        assertEquals(metrics.get(metricPrefix + "latency"), metricData.getTimer());
        assertEquals(metrics.get(metricPrefix + "total"), metricData.getTotal());
        assertEquals(metrics.get(metricPrefix + "success"), metricData.getSuccess());
        assertEquals(metrics.get(metricPrefix + "failed"), metricData.getFailed());
    }
}

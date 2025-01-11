package io.appform.dropwizard.sharding.metrics;

import com.codahale.metrics.Meter;
import com.codahale.metrics.Timer;
import io.appform.dropwizard.sharding.dao.RelationalDao;
import io.appform.dropwizard.sharding.dao.operations.Save;
import io.appform.dropwizard.sharding.dao.operations.lockedcontext.LockAndExecute;
import io.appform.dropwizard.sharding.dao.testdata.entities.RelationalEntity;
import io.appform.dropwizard.sharding.execution.DaoType;
import io.appform.dropwizard.sharding.execution.TransactionExecutionContext;
import lombok.val;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TransactionMetricObserverTest {

    private TransactionMetricObserver transactionMetricObserver;
    private TransactionMetricManager metricManager;

    @BeforeEach
    public void setup() {
        this.metricManager = Mockito.mock(TransactionMetricManager.class);
        this.transactionMetricObserver = new TransactionMetricObserver(metricManager);
    }

    @Test
    void testExecuteWhenMetricNotApplicable() {
        Mockito.doReturn(false).when(metricManager).isMetricApplicable(null);
        assertEquals(terminate(),
                transactionMetricObserver.execute(TransactionExecutionContext.builder()
                                                          .commandName("testCommand")
                                                          .shardName("testshard1")
                                                          .daoType(DaoType.RELATIONAL)
                                                          .entityClass(RelationalEntity.class)
                                                          .opContext(Save.<String, String>builder()
                                                                               .entity("dummy")
                                                                               .saver(t->t)
                                                                               .build())
                    .build(), this::terminate));
    }

    @Test
    void testExecuteWithNoException() {
        val context = TransactionExecutionContext.builder()
                .commandName("testCommand")
                .shardName("testshard1")
                .opContext(Save.<String, String>builder().entity("dummy").saver(t->t).build())
                .entityClass(RelationalDao.class)
                .shardName("shard")
                .daoType(DaoType.RELATIONAL)
                .build();
        val entityOpMetricData = MetricData.builder()
                .timer(new Timer())
                .total(new Meter())
                .success(new Meter())
                .failed(new Meter())
                .build();
        val shardMetricData = MetricData.builder()
                .timer(new Timer())
                .total(new Meter())
                .success(new Meter())
                .failed(new Meter())
                .build();

        Mockito.doReturn(entityOpMetricData).when(metricManager).getEntityOpMetricData(context);
        Mockito.doReturn(shardMetricData).when(metricManager).getShardMetricData(context.getShardName());
        Mockito.doReturn(true).when(metricManager).isMetricApplicable(context.getEntityClass());

        assertEquals(terminate(), transactionMetricObserver.execute(context, this::terminate));
        validateCache(entityOpMetricData, shardMetricData, context);
        validateMetrics(entityOpMetricData, shardMetricData, 1, 0);
    }

    @Test
    void testExecuteWithException() {
        val context = TransactionExecutionContext.builder()
                .commandName("testCommand")
                .shardName("testshard1")
                .opContext(Save.<String, String>builder().entity("dummy").saver(t->t).build())
                .entityClass(RelationalDao.class)
                .shardName("shard")
                .daoType(DaoType.RELATIONAL)
                .build();
        val entityOpMetricData = MetricData.builder()
                .timer(new Timer())
                .total(new Meter())
                .success(new Meter())
                .failed(new Meter())
                .build();
        val shardMetricData = MetricData.builder()
                .timer(new Timer())
                .total(new Meter())
                .success(new Meter())
                .failed(new Meter())
                .build();

        Mockito.doReturn(entityOpMetricData).when(metricManager).getEntityOpMetricData(context);
        Mockito.doReturn(shardMetricData).when(metricManager).getShardMetricData(context.getShardName());

        Mockito.doReturn(true).when(metricManager).isMetricApplicable(context.getEntityClass());

        assertThrows(RuntimeException.class, () -> transactionMetricObserver.execute(context, this::terminateWithException));
        validateCache(entityOpMetricData, shardMetricData, context);
        validateMetrics(entityOpMetricData, shardMetricData, 0, 1);
    }

    private void validateCache(final MetricData entityOpMetricData,
                               final MetricData shardMetricData,
                               final TransactionExecutionContext context) {
        assertEquals(1, transactionMetricObserver.getEntityOpMetricCache().size());
        assertEquals(entityOpMetricData, transactionMetricObserver.getEntityOpMetricCache().get(EntityOpMetricKey.builder()
                .entityClass(context.getEntityClass())
                .daoType(context.getDaoType())
                .lockedContextMode(context.getOpContext() instanceof LockAndExecute ?
                    ((LockAndExecute)context.getOpContext()).getMode().name() : null)
                .commandName(context.getCommandName())
                .build()));

        assertEquals(1, transactionMetricObserver.getShardMetricCache().size());
        assertEquals(shardMetricData, transactionMetricObserver.getShardMetricCache().get(context.getShardName()));
    }

    private void validateMetrics(final MetricData entityOpMetricData,
                                 final MetricData shardMetricData,
                                 final int successCount,
                                 final int failedCount) {
        assertEquals(1, entityOpMetricData.getTotal().getCount());
        assertEquals(1, shardMetricData.getTotal().getCount());

        assertEquals(1, entityOpMetricData.getTimer().getCount());
        assertEquals(1, shardMetricData.getTimer().getCount());

        assertEquals(successCount, entityOpMetricData.getSuccess().getCount());
        assertEquals(successCount, shardMetricData.getSuccess().getCount());

        assertEquals(failedCount, entityOpMetricData.getFailed().getCount());
        assertEquals(failedCount, shardMetricData.getFailed().getCount());
    }

    private Integer terminate() {
        return 1;
    }

    private Integer terminateWithException() {
        throw new RuntimeException();
    }
}

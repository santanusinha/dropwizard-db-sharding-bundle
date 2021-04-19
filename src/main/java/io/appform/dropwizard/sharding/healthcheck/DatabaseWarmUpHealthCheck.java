package io.appform.dropwizard.sharding.healthcheck;

import com.codahale.metrics.ConsoleReporter;
import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.Timer;
import com.codahale.metrics.health.HealthCheck;
import io.appform.dropwizard.sharding.config.DatabaseWarmUpConfig;
import io.dropwizard.lifecycle.Managed;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.Transaction;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;

/**
 * @author - suraj.s
 * @version - 1.0
 * @since - 23-02-2021
 */
@Slf4j
public class DatabaseWarmUpHealthCheck extends HealthCheck implements Managed {

    private static final String DB_HEALTHY_MESSAGE = "Database is successfully warmed up.";
    private static final String DB_UNHEALTHY_MESSAGE = "Database is still warming up.";
    private static final MetricRegistry metricRegistry = new MetricRegistry();

    private enum DatabaseWarmUpState {
        INITIATED,
        WARMING_UP,
        WARMED_UP
    }

    private class WarmupThread extends Thread {

        private WarmupThread(final String threadName) {
            super(threadName);
        }

        @Override
        public void run() {
            sessionFactories.forEach(
                    sessionFactory -> {
                        try (final Session session = sessionFactory.openSession()) {
                            final Transaction txn = session.beginTransaction();
                            try {
                                session.createNativeQuery(databaseWarmUpConfig.getValidationQuery()).list();
                                Thread.sleep(databaseWarmUpConfig.getSleepDurationInMillis());
                                txn.commit();
                            } catch (Exception e) {
                                if (txn.getStatus().canRollback()) {
                                    txn.rollback();
                                }

                                log.error("[DatabaseWarmUpHealthCheck] error encountered while qeurying db");
                            }
                        }
                    }
            );
        }
    }

    private final List<SessionFactory> sessionFactories;
    private final DatabaseWarmUpConfig databaseWarmUpConfig;
    private final AtomicReference<DatabaseWarmUpState> databaseWarmUpStateAtomicReference;
    private final int callCounts;

    /* To capture metrics */
    private final Timer timer;
    private final ConsoleReporter reporter;

    public DatabaseWarmUpHealthCheck(final List<SessionFactory> sessionFactories,
                                     final DatabaseWarmUpConfig databaseWarmUpConfig) {
        this.sessionFactories = sessionFactories;
        this.databaseWarmUpConfig = databaseWarmUpConfig;
        this.databaseWarmUpStateAtomicReference = databaseWarmUpConfig.isWarmUpRequired()
                ? new AtomicReference<>(DatabaseWarmUpState.INITIATED)
                : new AtomicReference<>(DatabaseWarmUpState.WARMED_UP);
        this.callCounts = databaseWarmUpConfig.getCallCounts();
        this.timer = metricRegistry.timer("DatabaseWarmUpHealthCheck-Metric");
        this.reporter = ConsoleReporter
                .forRegistry(metricRegistry)
                .convertRatesTo(TimeUnit.SECONDS)
                .convertDurationsTo(TimeUnit.MILLISECONDS)
                .build();
    }

    @Override
    protected Result check() {
        if (!databaseWarmUpConfig.isWarmUpRequired()) {
            return Result.healthy(DB_HEALTHY_MESSAGE);
        }

        if (DatabaseWarmUpState.WARMED_UP.equals(this.databaseWarmUpStateAtomicReference.get())) {
            return Result.healthy(DB_HEALTHY_MESSAGE);
        }

        return Result.unhealthy(DB_UNHEALTHY_MESSAGE);
    }

    @Override
    public void start() {
        if (!databaseWarmUpConfig.isWarmUpRequired()) {
            this.databaseWarmUpStateAtomicReference.set(DatabaseWarmUpState.WARMED_UP);
            return;
        }

        log.info("[DatabaseWarmUpHealthCheck] database warm-up has started. All {} shards will be checked.", sessionFactories.size());
        this.databaseWarmUpStateAtomicReference.set(DatabaseWarmUpState.WARMING_UP);

        // this code can work asynchronously. need to introduced executor-service here.
        IntStream.range(0, callCounts).forEach(
                count -> {
                    final Thread warmupThread  = new WarmupThread(String.format("WarmupThread-%s",count));
                    timer.time(() -> {
                        log.info("[DatabaseWarmUpHealthCheck] checking the shards for {} time on the thread {}.", count, warmupThread.getName());
                        warmupThread.start();
                    });
                }
        );

        log.info("[DatabaseWarmUpHealthCheck] The warm-up reports are as follows\n");
        reporter.report();

        this.databaseWarmUpStateAtomicReference.set(DatabaseWarmUpState.WARMED_UP);
    }

    @Override
    public void stop() {
        /*do nothing*/
    }
}

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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
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
            IntStream.range(0, numberOfIteration).forEach( count -> {
                log.info("[DatabaseWarmUpHealthCheck] Thread: {} is initiating {} iteration.", getName(), count);
                final AtomicInteger counter = new AtomicInteger(0);
                sessionFactories.forEach(
                        sessionFactory -> {
                            boolean encouteredError = false;
                            log.info("[DatabaseWarmUpHealthCheck] thread: {} trying to connect with shardId: {}.", getName(), counter.getAndIncrement());
                            try (final Session session = sessionFactory.openSession()) {
                                Transaction txn = null;
                                try {
                                    txn = session.beginTransaction();
                                    session.createNativeQuery(databaseWarmUpConfig.getValidationQuery()).list();
                                    Thread.sleep(databaseWarmUpConfig.getSleepDurationInMillis());
                                    txn.commit();
                                } catch (Exception e) {
                                    encouteredError = true;
                                    log.error("[DatabaseWarmUpHealthCheck] thread: {} encountered error while qeurying db",getName(), e);
                                } finally {
                                    if (txn != null) {
                                        if (encouteredError && txn.getStatus().canRollback()) {
                                            txn.rollback();
                                        }
                                    }
                                }
                            }
                        });
            });
        }
    }

    private final List<SessionFactory> sessionFactories;
    private final DatabaseWarmUpConfig databaseWarmUpConfig;
    private final AtomicReference<DatabaseWarmUpState> databaseWarmUpStateAtomicReference;
    private final int numberOfThreads;
    private final int numberOfIteration;

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
        this.numberOfThreads = databaseWarmUpConfig.getNumberOfThreads();
        this.numberOfIteration = databaseWarmUpConfig.getNumberOfIteration();
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

        // [numberOfThreads] numbers of threads will spin-up and work asynchronously.
        final List<Thread> warmupThreads = new ArrayList<>();
        IntStream.range(0, numberOfThreads).forEach(
                count -> {
                    final Thread warmupThread  = new WarmupThread(String.format("WarmupThread-%s", count));
                    warmupThreads.add(warmupThread);
                    timer.time(() -> {
                        log.info("[DatabaseWarmUpHealthCheck] Thread {} spinned-up.", warmupThread.getName());
                        warmupThread.start();
                    });
                }
        );

        // holding-off further execution of code until all the threads are safely closed.
        warmupThreads.forEach(warmupThread -> {
            try {
                log.info("[DatabaseWarmUpHealthCheck] waiting for {} to complete.", warmupThread.getName());
                warmupThread.join(5 * sessionFactories.size() * databaseWarmUpConfig.getSleepDurationInMillis());
                log.info("[DatabaseWarmUpHealthCheck] {} is complete.", warmupThread.getName());
            } catch (InterruptedException e) {
                log.error("[DatabaseWarmUpHealthCheck] Error encountered closing the thread : {} ", warmupThread.getName(), e);
            }
        });

        log.info("[DatabaseWarmUpHealthCheck] The warm-up reports are as follows\n");
        reporter.report();

        this.databaseWarmUpStateAtomicReference.set(DatabaseWarmUpState.WARMED_UP);
    }

    @Override
    public void stop() {
        /*do nothing*/
    }
}

package io.appform.dropwizard.sharding.healthcheck;

import com.codahale.metrics.health.HealthCheck;
import io.appform.dropwizard.sharding.config.DatabaseWarmUpConfig;
import io.dropwizard.lifecycle.Managed;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.Transaction;

import java.util.List;
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
    private static int MAX_CALL_COUNT = 200;

    private enum DatabaseWarmUpState {
        INITIATED,
        WARMING_UP,
        WARMED_UP
    }

    private final List<SessionFactory> sessionFactories;
    private final DatabaseWarmUpConfig databaseWarmUpConfig;
    private final AtomicReference<DatabaseWarmUpState> databaseWarmUpStateAtomicReference;

    public DatabaseWarmUpHealthCheck(final List<SessionFactory> sessionFactories,
                                     final DatabaseWarmUpConfig databaseWarmUpConfig) {
        this.sessionFactories = sessionFactories;
        this.databaseWarmUpConfig = databaseWarmUpConfig;
        this.databaseWarmUpStateAtomicReference = databaseWarmUpConfig.isWarmUpRequired()
                ? new AtomicReference<>(DatabaseWarmUpState.INITIATED)
                : new AtomicReference<>(DatabaseWarmUpState.WARMED_UP);
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
        IntStream.range(0, MAX_CALL_COUNT).forEach(
                count -> {
                    log.info("[DatabaseWarmUpHealthCheck] checking the shards for {} time.", count);
                    sessionFactories.forEach(
                            sessionFactory -> {
                                try (final Session session = sessionFactory.openSession()) {
                                    final Transaction txn = session.beginTransaction();
                                    try {
                                        // log.info("[DatabaseWarmUpHealthCheck] checking the shard {} time.", count);
                                        session.createNativeQuery(databaseWarmUpConfig.getValidationQuery()).list();
                                        txn.commit();
                                    } catch (Exception e) {
                                        if (txn.getStatus().canRollback()) {
                                            txn.rollback();
                                        }
                                        throw e;
                                    }
                                }
                            }
                    );
                }
        );

        this.databaseWarmUpStateAtomicReference.set(DatabaseWarmUpState.WARMED_UP);
    }

    @Override
    public void stop() {
        /*do nothing*/
    }
}

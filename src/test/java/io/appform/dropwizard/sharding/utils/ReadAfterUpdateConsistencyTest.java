package io.appform.dropwizard.sharding.utils;

import io.appform.dropwizard.sharding.DBShardingBundleBase;
import io.appform.dropwizard.sharding.ShardInfoProvider;
import io.appform.dropwizard.sharding.config.ShardingBundleOptions;
import io.appform.dropwizard.sharding.dao.LookupDao;
import io.appform.dropwizard.sharding.dao.MultiTenantLookupDao;
import io.appform.dropwizard.sharding.dao.interceptors.TimerObserver;
import io.appform.dropwizard.sharding.dao.listeners.LoggingListener;
import io.appform.dropwizard.sharding.dao.testdata.entities.TestEntity;
import io.appform.dropwizard.sharding.observers.internal.ListenerTriggeringObserver;
import io.appform.dropwizard.sharding.sharding.BalancedShardManager;
import org.hibernate.SessionFactory;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end consistency test: write → read → update → read.
 *
 * <p>Verifies that a read-only {@code LookupDao.get()} call after an update returns the
 * freshly-committed value, not a stale snapshot from the earlier read's implicit JDBC transaction.
 *
 * <p><b>Why this matters</b>: {@code Get.isTransactionOptional()} returns {@code true}, so the
 * handler sets {@code skipCommit=true} and never calls {@code beginTransaction()}. With
 * {@code autoCommit=false} (the JDBC pool default), an implicit DB transaction opens as soon as
 * the first SQL statement executes. Without an explicit {@code conn.rollback()} after the read,
 * that implicit transaction is returned to the pool still open. On a real database with
 * REPEATABLE_READ isolation (PostgreSQL, MySQL InnoDB), a later read that reuses the same physical
 * connection would inherit the old snapshot and return stale data.
 *
 * <p>The fix ({@code conn.rollback()} in {@code TransactionHandler.afterEnd()}) closes the
 * implicit transaction before the connection returns to the pool, ensuring the next read always
 * starts with a fresh snapshot.
 *
 * <p><b>Pool size = 1</b>: All four DAO calls reuse the same physical JDBC connection, making the
 * connection lifecycle deterministic and eliminating any ambiguity about which connection is
 * actually used.
 */
class ReadAfterUpdateConsistencyTest {

    private SessionFactory sessionFactory;
    private LookupDao<TestEntity> lookupDao;

    @BeforeEach
    void setUp() {
        sessionFactory = buildSessionFactory();
        final var shardManager = new BalancedShardManager(1);
        final var shardingOptions = new ShardingBundleOptions();
        final var shardInfoProvider = new ShardInfoProvider("default");
        final var observer = new TimerObserver(
                new ListenerTriggeringObserver().addListener(new LoggingListener()));

        lookupDao = new LookupDao<>(
                DBShardingBundleBase.DEFAULT_NAMESPACE,
                new MultiTenantLookupDao<>(
                        Map.of(DBShardingBundleBase.DEFAULT_NAMESPACE, List.of(sessionFactory)),
                        TestEntity.class,
                        Map.of(DBShardingBundleBase.DEFAULT_NAMESPACE, shardManager),
                        Map.of(DBShardingBundleBase.DEFAULT_NAMESPACE, shardingOptions),
                        Map.of(DBShardingBundleBase.DEFAULT_NAMESPACE, shardInfoProvider),
                        observer));
    }

    @AfterEach
    void tearDown() {
        if (sessionFactory != null) {
            sessionFactory.close();
        }
    }

    /**
     * The core scenario:
     * <ol>
     *   <li>Save entity with text = "version-1"</li>
     *   <li>Read it (transactionOptional=true) → assert "version-1"</li>
     *   <li>Update it to "version-2" in a write transaction</li>
     *   <li>Read it again (transactionOptional=true) → must see "version-2", not stale "version-1"</li>
     * </ol>
     */
    @Test
    void secondReadAfterUpdateSeesUpdatedValue() throws Exception {
        // 1. Write initial row
        lookupDao.save(TestEntity.builder()
                .externalId("consistency-test-1")
                .text("version-1")
                .build());

        // 2. Read (transactionOptional=true → conn.rollback() called in afterEnd, clears implicit tx)
        Optional<TestEntity> firstRead = lookupDao.get("consistency-test-1");
        assertTrue(firstRead.isPresent());
        assertEquals("version-1", firstRead.get().getText(),
                "first read must return the saved value");

        // 3. Update the row in a separate write transaction (begins + commits its own transaction)
        boolean updated = lookupDao.update("consistency-test-1", entity -> {
            if (entity.isPresent()) {
                entity.get().setText("version-2");
                return entity.get();
            }
            return null;
        });
        assertTrue(updated, "update must succeed");

        // 4. Read again — must see "version-2"
        // If conn.rollback() were absent, the first read's implicit JDBC transaction would still
        // be open on the connection. On real databases (PostgreSQL / MySQL InnoDB) with
        // REPEATABLE_READ isolation this causes the second read to return the snapshot from
        // before the update, i.e., "version-1" — a stale read.
        Optional<TestEntity> secondRead = lookupDao.get("consistency-test-1");
        assertTrue(secondRead.isPresent());
        assertEquals("version-2", secondRead.get().getText(),
                "second read must see the committed update, not a stale snapshot from the first read");
    }

    private SessionFactory buildSessionFactory() {
        Configuration config = new Configuration();
        config.setProperty("hibernate.dialect", "org.hibernate.dialect.H2Dialect");
        config.setProperty("hibernate.connection.driver_class", "org.h2.Driver");
        // Unique in-memory DB name per test run
        config.setProperty("hibernate.connection.url",
                "jdbc:h2:mem:consistency_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
        config.setProperty("hibernate.hbm2ddl.auto", "create");
        config.setProperty("hibernate.current_session_context_class", "managed");
        // Single physical connection: all DAO calls reuse the same JDBC connection, making
        // the connection lifecycle deterministic for this test.
        config.setProperty("hibernate.connection.pool_size", "1");
        config.addAnnotatedClass(TestEntity.class);

        return config.buildSessionFactory(
                new StandardServiceRegistryBuilder()
                        .applySettings(config.getProperties())
                        .build());
    }
}

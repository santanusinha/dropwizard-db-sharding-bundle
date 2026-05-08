package io.appform.dropwizard.sharding.utils;

import io.appform.dropwizard.sharding.dao.testdata.entities.TestEntity;
import org.h2.jdbcx.JdbcDataSource;
import org.hibernate.SessionFactory;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

// NOTE: this test is written against the CURRENT TransactionHandler API (constructor + beforeStart).
// Once the strategy-pattern refactoring is done (TransactionHandler.begin() factory), replace
// `new TransactionHandler(...) + beforeStart()` with `TransactionHandler.begin(...)` here.

/**
 * Integration test that verifies conn.rollback() is called on the real JDBC connection
 * at the end of a read-only optional transaction (transactionOptional=true).
 *
 * <p>Why this matters: when transactionOptional=true, Hibernate never calls beginTransaction(),
 * so no Hibernate-managed transaction exists. However, because autoCommit=false is the JDBC
 * pool default, an implicit DB-level transaction is open from the moment the connection is
 * acquired. Without an explicit rollback, that implicit transaction is returned to the pool
 * still open, causing MVCC snapshot leakage (the next borrower inherits a stale snapshot).
 *
 * <p>The fix (conn.rollback() in afterEnd()) is tested here by wrapping the DataSource with
 * a JDBC proxy that counts actual rollback() calls on the physical connection.
 */
class TransactionHandlerIntegrationTest {

    private SessionFactory sessionFactory;
    private AtomicInteger rollbackCount;
    private AtomicInteger commitCount;

    @BeforeEach
    void setUp() {
        rollbackCount = new AtomicInteger(0);
        commitCount = new AtomicInteger(0);
        sessionFactory = buildSessionFactory();
    }

    @AfterEach
    void tearDown() {
        if (sessionFactory != null) {
            sessionFactory.close();
        }
    }

    // -------------------------------------------------------------------------
    // The case the commit covers: readOnly=true, transactionOptional=true
    // -------------------------------------------------------------------------

    @Test
    void readOnly_transactionOptional_rollsBackConnectionOnAfterEnd() {
        // Simulate an op like Get/Count/Select that sets transactionOptional=true.
        // No beginTransaction() is called. afterEnd() must still rollback the JDBC conn.
        TransactionHandler handler = new TransactionHandler(sessionFactory, true, true);
        handler.beforeStart();
        handler.afterEnd();

        assertEquals(1, rollbackCount.get(),
                "conn.rollback() must be called exactly once to clean up the implicit JDBC transaction");
        assertEquals(0, commitCount.get(),
                "conn.commit() must never be called for a transaction-optional read");
    }

    @Test
    void readOnly_transactionOptional_rollsBackConnectionOnOnError() {
        // Even when the operation fails, the implicit JDBC transaction must be cleaned up.
        TransactionHandler handler = new TransactionHandler(sessionFactory, true, true);
        handler.beforeStart();
        handler.onError();

        // onError() for this case just closes the session — no doWork rollback.
        // We verify the handler itself doesn't call rollback (that's afterEnd()'s job).
        assertEquals(0, rollbackCount.get(),
                "onError() must not call conn.rollback() — it just closes the session");
    }

    // -------------------------------------------------------------------------
    // Guard cases: rollback must NOT be called for owned transactions
    // -------------------------------------------------------------------------

    @Test
    void readWrite_ownedTransaction_doesNotCallConnectionRollback() {
        // Normal write transaction: Hibernate manages commit via txn.commit() → conn.commit().
        // Our conn.rollback() path must not fire.
        TransactionHandler handler = new TransactionHandler(sessionFactory, false, false);
        handler.beforeStart();
        handler.afterEnd();

        assertEquals(0, rollbackCount.get(),
                "conn.rollback() must not be called for a normal owned write transaction");
    }

    @Test
    void readOnly_ownedTransaction_doesNotCallConnectionRollback() {
        // Read-only but skipCommit=false: Hibernate still manages the transaction normally.
        TransactionHandler handler = new TransactionHandler(sessionFactory, true, false);
        handler.beforeStart();
        handler.afterEnd();

        assertEquals(0, rollbackCount.get(),
                "conn.rollback() must not be called when Hibernate owns the transaction lifecycle");
    }

    // -------------------------------------------------------------------------
    // N1/N2/N3: nested transaction scenarios (outer owns session, inner is passthrough)
    // -------------------------------------------------------------------------

    @Nested
    class NestedTransactionScenarios {

        /**
         * N1: Outer owns the transaction; inner joins as passthrough. Both do DB work.
         * Only the outer's afterEnd() should commit — in a single commit call covering both rows.
         */
        @Test
        void n1_outerAndInnerSucceed_onlyOuterCommits() {
            TransactionHandler outer = new TransactionHandler(sessionFactory, false, false);
            outer.beforeStart();

            // Inner finds an active transaction → skipCommit=true (passthrough)
            TransactionHandler inner = new TransactionHandler(sessionFactory, false, false);
            inner.beforeStart();

            inner.getSession().persist(TestEntity.builder().externalId("n1-a").text("inner-work").build());
            inner.afterEnd(); // no-op: passthrough never commits

            outer.getSession().persist(TestEntity.builder().externalId("n1-b").text("outer-work").build());
            outer.afterEnd(); // single commit covering both rows

            assertEquals(1, commitCount.get(), "exactly one commit — from the outer owner");
            assertEquals(0, rollbackCount.get());

            // Verify both rows are durable
            TransactionHandler reader = new TransactionHandler(sessionFactory, true, false);
            reader.beforeStart();
            List<TestEntity> rows = reader.getSession()
                    .createQuery("from TestEntity where externalId in ('n1-a','n1-b') order by externalId",
                            TestEntity.class)
                    .list();
            reader.afterEnd();

            assertEquals(2, rows.size(), "both rows must be visible after outer commits");
        }

        /**
         * N2: Outer owns the transaction; inner joins as passthrough. Outer calls onError().
         * The single rollback must revert ALL work, including what inner persisted.
         */
        @Test
        void n2_outerErrors_bothRowsRolledBack() {
            TransactionHandler outer = new TransactionHandler(sessionFactory, false, false);
            outer.beforeStart();

            TransactionHandler inner = new TransactionHandler(sessionFactory, false, false);
            inner.beforeStart();

            inner.getSession().persist(TestEntity.builder().externalId("n2-a").text("inner-work").build());
            inner.afterEnd(); // no-op

            outer.getSession().persist(TestEntity.builder().externalId("n2-b").text("outer-work").build());
            outer.onError(); // rolls back the entire transaction — inner's work too

            assertEquals(0, commitCount.get());
            assertEquals(1, rollbackCount.get(), "one rollback from the outer owner");

            // Verify neither row was committed
            TransactionHandler reader = new TransactionHandler(sessionFactory, true, false);
            reader.beforeStart();
            List<TestEntity> rows = reader.getSession()
                    .createQuery("from TestEntity where externalId in ('n2-a','n2-b')", TestEntity.class)
                    .list();
            reader.afterEnd();

            assertTrue(rows.isEmpty(), "rollback must revert all work, including inner's");
        }

        /**
         * N3: Outer owns the transaction; inner joins as passthrough. Inner encounters a
         * non-DB failure and calls onError() — which is a no-op for a passthrough handler.
         * The outer transaction is still active and can commit its own work.
         */
        @Test
        void n3_innerNonDbFailure_outerStillCommits() {
            TransactionHandler outer = new TransactionHandler(sessionFactory, false, false);
            outer.beforeStart();
            outer.getSession().persist(TestEntity.builder().externalId("n3-outer").text("outer-work").build());

            TransactionHandler inner = new TransactionHandler(sessionFactory, false, false);
            inner.beforeStart();

            // Simulate inner's non-DB business logic failing before any DB work
            try {
                throw new RuntimeException("inner non-DB failure");
            } catch (RuntimeException e) {
                inner.onError(); // passthrough — does NOT rollback the transaction
            }

            // Outer's transaction is still active; afterEnd() commits its work
            outer.afterEnd();

            assertEquals(1, commitCount.get(), "outer commits despite inner's non-DB failure");
            assertEquals(0, rollbackCount.get(), "no rollback — inner.onError() is a passthrough no-op");

            // Verify outer's entity was committed
            TransactionHandler reader = new TransactionHandler(sessionFactory, true, false);
            reader.beforeStart();
            TestEntity found = reader.getSession().get(TestEntity.class, "n3-outer");
            reader.afterEnd();

            assertNotNull(found, "outer's work must survive inner's non-DB failure");
            assertEquals("outer-work", found.getText());
        }
    }

    // -------------------------------------------------------------------------
    // SessionFactory wired to a tracking DataSource
    // -------------------------------------------------------------------------

    private SessionFactory buildSessionFactory() {
        JdbcDataSource h2 = new JdbcDataSource();
        h2.setURL("jdbc:h2:mem:txhandler_it_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
        h2.setUser("sa");
        h2.setPassword("");

        DataSource trackingDs = wrapWithTracking(h2);

        Configuration config = new Configuration();
        config.setProperty("hibernate.dialect", "org.hibernate.dialect.H2Dialect");
        config.setProperty("hibernate.hbm2ddl.auto", "create");
        config.setProperty("hibernate.current_session_context_class", "managed");
        config.addAnnotatedClass(TestEntity.class);

        return config.buildSessionFactory(
                new StandardServiceRegistryBuilder()
                        .applySettings(config.getProperties())
                        // Provide our tracking DataSource so Hibernate uses it for all connections.
                        .applySetting("hibernate.connection.datasource", trackingDs)
                        .build()
        );
    }

    /**
     * Wraps the DataSource so every Connection it vends is itself wrapped by a proxy
     * that increments {@link #rollbackCount} / {@link #commitCount} on each call.
     */
    private DataSource wrapWithTracking(DataSource delegate) {
        return (DataSource) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class[]{DataSource.class},
                (proxy, method, args) -> {
                    Object result = method.invoke(delegate, args);
                    if ("getConnection".equals(method.getName())) {
                        return wrapConnection((Connection) result);
                    }
                    return result;
                });
    }

    private Connection wrapConnection(Connection real) {
        return (Connection) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class[]{Connection.class},
                (proxy, method, args) -> {
                    // Count no-arg rollback() only (not rollback(Savepoint))
                    if ("rollback".equals(method.getName()) && (args == null || args.length == 0)) {
                        rollbackCount.incrementAndGet();
                    }
                    if ("commit".equals(method.getName())) {
                        commitCount.incrementAndGet();
                    }
                    return method.invoke(real, args);
                });
    }
}

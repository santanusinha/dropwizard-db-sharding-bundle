package io.appform.dropwizard.sharding.utils;

import io.appform.dropwizard.sharding.dao.testdata.entities.TestEntity;
import org.hibernate.SessionFactory;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Two-pool MVCC stale-read prevention test.
 *
 * <h3>The problem</h3>
 * When {@code transactionOptional=true} (e.g., {@code Get}, {@code Count}), the
 * {@code TransactionHandler} never calls {@code beginTransaction()}, so Hibernate
 * never opens a Hibernate-managed transaction. However, JDBC connection pools set
 * {@code autoCommit=false}, which means an <em>implicit</em> database-level transaction
 * opens as soon as the first SQL statement executes. If no {@code conn.rollback()} is
 * issued before the connection is returned to the pool, that implicit transaction
 * remains open.
 *
 * <p>On databases that use snapshot isolation for REPEATABLE_READ (PostgreSQL,
 * MySQL InnoDB, H2 MVStore), a stale snapshot is kept alive on the pooled connection.
 * If the same physical connection is later reused for another read, that read executes
 * within the old snapshot and returns outdated data — even though a write transaction on
 * a <em>different</em> connection has since committed fresh data.
 *
 * <h3>Why this test uses two pools</h3>
 * <ul>
 *   <li><b>readSf</b> uses a {@link SingleConnectionDataSource}: one physical JDBC
 *       connection where {@code close()} is a no-op, so the implicit transaction state
 *       survives the Hibernate session lifecycle (exactly like a real pool returning a
 *       connection without resetting its tx state).</li>
 *   <li><b>writeSf</b> uses a standard Hibernate connection from its own pool, entirely
 *       independent of readSf's connection. Writes committed on writeSf's connection are
 *       visible in the shared H2 database.</li>
 * </ul>
 *
 * <h3>How the fix is validated</h3>
 * {@code TransactionHandler.afterEnd()} calls {@code session.doWork(conn -> conn.rollback())}
 * when {@code skipCommit && readOnly && sessionAcquired}. This rolls back the implicit
 * transaction on the pooled connection before returning it, releasing the REPEATABLE_READ
 * snapshot. The next read starts a fresh transaction and sees the latest committed data.
 *
 * <p>Without the fix, step 4 would return {@code "version-1"} (stale snapshot from step 2),
 * and the {@code assertEquals("version-2", ...)} assertion would fail.
 */
class TwoPoolMvccSnapshotTest {

    // writeSf: standard Hibernate pool — used for all writes
    private SessionFactory writeSf;
    // readSf: single tracked connection — used for all reads
    private SessionFactory readSf;
    private AtomicInteger readRollbackCount;

    @BeforeEach
    void setUp() throws Exception {
        // Both session factories share the same named H2 in-memory database
        String dbName = "mvcc_" + System.nanoTime();
        String dbUrl = "jdbc:h2:mem:" + dbName + ";DB_CLOSE_DELAY=-1";

        readRollbackCount = new AtomicInteger(0);

        // writeSf must be built first so it creates the schema (hbm2ddl.auto=create)
        writeSf = buildWriteSf(dbUrl);
        // readSf uses hbm2ddl.auto=none — schema already exists
        readSf = buildReadSf(dbUrl);
    }

    @AfterEach
    void tearDown() {
        if (readSf != null) readSf.close();
        if (writeSf != null) writeSf.close();
    }

    /**
     * Sequence:
     * <ol>
     *   <li>Write "version-1" via writeSf (commits on C_write)</li>
     *   <li>Read via readSf (transactionOptional=true):
     *       C_read opens implicit REPEATABLE_READ transaction T1 at snapshot S1.
     *       The fix calls {@code conn.rollback()} → T1 is closed, C_read returns clean.</li>
     *   <li>Write "version-2" via writeSf (commits on C_write — entirely separate connection)</li>
     *   <li>Read via readSf again (same physical C_read):
     *       <ul>
     *         <li><b>With fix</b>: C_read is clean, new T2 at snapshot S2 → sees "version-2" ✓</li>
     *         <li><b>Without fix</b>: T1 is still open on C_read (REPEATABLE_READ snapshot S1)
     *             → sees stale "version-1" ✗</li>
     *       </ul></li>
     * </ol>
     */
    @Test
    void readAfterCrossPoolUpdate_withRollbackFix_seesLatestCommit() throws Exception {
        // 1. Write "version-1"
        TransactionHandler writer1 = new TransactionHandler(writeSf, false, false);
        writer1.beforeStart();
        writer1.getSession().persist(TestEntity.builder()
                .externalId("mvcc-1")
                .text("version-1")
                .build());
        writer1.afterEnd();

        // 2. Read (transactionOptional=true) — implicit T1 opens on C_read; fix rolls it back
        TransactionHandler reader1 = new TransactionHandler(readSf, true, true);
        reader1.beforeStart();
        TestEntity v1 = reader1.getSession()
                .createQuery("from TestEntity where externalId = :id", TestEntity.class)
                .setParameter("id", "mvcc-1")
                .uniqueResult();
        reader1.afterEnd();

        assertNotNull(v1);
        assertEquals("version-1", v1.getText());
        assertEquals(1, readRollbackCount.get(),
                "conn.rollback() must be called after the read-only session to release the snapshot");

        // 3. Update to "version-2" via writeSf — commits on its own separate C_write connection
        TransactionHandler writer2 = new TransactionHandler(writeSf, false, false);
        writer2.beforeStart();
        TestEntity toUpdate = writer2.getSession()
                .createQuery("from TestEntity where externalId = :id", TestEntity.class)
                .setParameter("id", "mvcc-1")
                .uniqueResult();
        toUpdate.setText("version-2");  // dirty — Hibernate flushes before commit
        writer2.afterEnd();

        // 4. Read again via readSf — same physical C_read
        //    WITH FIX:    T1 was rolled back → new T2 at snapshot after the commit → "version-2" ✓
        //    WITHOUT FIX: T1 still open (REPEATABLE_READ snapshot S1) → stale "version-1" ✗
        TransactionHandler reader2 = new TransactionHandler(readSf, true, true);
        reader2.beforeStart();
        TestEntity v2 = reader2.getSession()
                .createQuery("from TestEntity where externalId = :id", TestEntity.class)
                .setParameter("id", "mvcc-1")
                .uniqueResult();
        reader2.afterEnd();

        assertNotNull(v2);
        assertEquals("version-2", v2.getText(),
                "second read must see the committed update — not the stale snapshot from the first read");
        assertEquals(2, readRollbackCount.get(),
                "conn.rollback() must be called after each read-only session");
    }

    // -------------------------------------------------------------------------
    // Session factory builders
    // -------------------------------------------------------------------------

    private SessionFactory buildWriteSf(String dbUrl) {
        Configuration config = new Configuration();
        config.setProperty("hibernate.dialect", "org.hibernate.dialect.H2Dialect");
        config.setProperty("hibernate.connection.driver_class", "org.h2.Driver");
        config.setProperty("hibernate.connection.url", dbUrl);
        config.setProperty("hibernate.connection.username", "sa");
        config.setProperty("hibernate.connection.password", "");
        config.setProperty("hibernate.hbm2ddl.auto", "create");
        config.setProperty("hibernate.current_session_context_class", "managed");
        config.setProperty("hibernate.connection.pool_size", "1");
        config.setProperty("hibernate.connection.isolation", "4"); // REPEATABLE_READ
        config.addAnnotatedClass(TestEntity.class);
        return config.buildSessionFactory(
                new StandardServiceRegistryBuilder()
                        .applySettings(config.getProperties())
                        .build());
    }

    private SessionFactory buildReadSf(String dbUrl) throws SQLException {
        DataSource ds = new SingleConnectionDataSource(dbUrl, readRollbackCount);
        Configuration config = new Configuration();
        config.setProperty("hibernate.dialect", "org.hibernate.dialect.H2Dialect");
        // Schema already created by writeSf — do not touch it
        config.setProperty("hibernate.hbm2ddl.auto", "none");
        config.setProperty("hibernate.current_session_context_class", "managed");
        config.addAnnotatedClass(TestEntity.class);
        return config.buildSessionFactory(
                new StandardServiceRegistryBuilder()
                        .applySettings(config.getProperties())
                        .applySetting("hibernate.connection.datasource", ds)
                        .build());
    }

    // -------------------------------------------------------------------------
    // Single-connection DataSource — simulates a pool with pool_size=1
    // -------------------------------------------------------------------------

    /**
     * A minimal DataSource that always returns the same physical JDBC connection.
     *
     * <p>{@code close()} on the vended connection is intercepted and made a no-op, so the
     * underlying connection (and any open implicit transaction) survives across Hibernate session
     * open/close cycles. This faithfully mimics real connection pools like HikariCP or c3p0,
     * where {@code Connection.close()} returns the connection to the pool without physically
     * closing it — leaving any uncommitted implicit transaction intact.
     *
     * <p>The underlying physical connection is created with:
     * <ul>
     *   <li>{@code autoCommit=false} — pool default; triggers implicit transactions</li>
     *   <li>{@code REPEATABLE_READ} isolation — H2 MVStore snapshot isolation; the snapshot
     *       is pinned until the transaction ends via commit or rollback</li>
     * </ul>
     */
    private static final class SingleConnectionDataSource implements DataSource {

        private final Connection singleConn;

        SingleConnectionDataSource(String dbUrl, AtomicInteger rollbackCount) throws SQLException {
            Connection raw = DriverManager.getConnection(dbUrl, "sa", "");
            raw.setAutoCommit(false);
            raw.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);

            singleConn = (Connection) Proxy.newProxyInstance(
                    SingleConnectionDataSource.class.getClassLoader(),
                    new Class[]{Connection.class},
                    (proxy, method, args) -> {
                        switch (method.getName()) {
                            case "close":
                                // Simulate returning to pool: keep connection alive with tx state
                                return null;
                            case "rollback":
                                if (args == null || args.length == 0) {
                                    rollbackCount.incrementAndGet();
                                }
                                return method.invoke(raw, args);
                            default:
                                return method.invoke(raw, args);
                        }
                    });
        }

        @Override public Connection getConnection() { return singleConn; }
        @Override public Connection getConnection(String u, String p) { return singleConn; }
        @Override public PrintWriter getLogWriter() { return null; }
        @Override public void setLogWriter(PrintWriter out) {}
        @Override public void setLoginTimeout(int s) {}
        @Override public int getLoginTimeout() { return 0; }
        @Override public Logger getParentLogger() throws SQLFeatureNotSupportedException {
            throw new SQLFeatureNotSupportedException();
        }
        @Override public <T> T unwrap(Class<T> iface) throws SQLException {
            throw new SQLException("not a wrapper");
        }
        @Override public boolean isWrapperFor(Class<?> iface) { return false; }
    }
}

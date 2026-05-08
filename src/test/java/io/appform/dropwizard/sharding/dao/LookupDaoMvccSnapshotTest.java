package io.appform.dropwizard.sharding.dao;

import io.appform.dropwizard.sharding.DBShardingBundleBase;
import io.appform.dropwizard.sharding.ShardInfoProvider;
import io.appform.dropwizard.sharding.config.ShardingBundleOptions;
import io.appform.dropwizard.sharding.dao.interceptors.TimerObserver;
import io.appform.dropwizard.sharding.dao.listeners.LoggingListener;
import io.appform.dropwizard.sharding.dao.testdata.entities.TestEntity;
import io.appform.dropwizard.sharding.observers.internal.ListenerTriggeringObserver;
import io.appform.dropwizard.sharding.sharding.BalancedShardManager;
import lombok.val;
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
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression test verifying that {@link LookupDao#get(String)} does not return stale MVCC
 * snapshots after a committed write from another connection.
 *
 * <h3>The problem</h3>
 * {@link LookupDao#get(String)} uses {@code transactionOptional=true}, which means
 * {@code TransactionHandler} never calls {@code beginTransaction()}. JDBC connection pools set
 * {@code autoCommit=false}, so the first SQL statement silently opens an implicit transaction.
 * If no {@code conn.rollback()} is issued before the connection is returned to the pool, a
 * REPEATABLE_READ snapshot is pinned on the pooled connection. The next read on that same
 * physical connection sees stale data — even after another connection commits fresh rows.
 *
 * <h3>Setup</h3>
 * <ul>
 *   <li><b>writeLookupDao</b>: backed by a standard Hibernate pool ({@code writeSf}).
 *       All writes commit on their own physical connection {@code C_write}.</li>
 *   <li><b>readLookupDao</b>: backed by a {@link SingleConnectionDataSource} ({@code readSf}).
 *       The same physical connection {@code C_read} is reused across reads (close = no-op),
 *       exactly as HikariCP would behave. It is created with {@code autoCommit=false} and
 *       {@code REPEATABLE_READ} to trigger the implicit-transaction scenario.</li>
 * </ul>
 *
 * <h3>How the fix is validated</h3>
 * {@code TransactionHandler.afterEnd()} calls {@code conn.rollback()} when
 * {@code skipCommit && readOnly && sessionAcquired}. This releases the REPEATABLE_READ snapshot
 * on {@code C_read} before it is "returned to the pool". Without the fix, the second
 * {@code LookupDao.get()} would return stale {@code "version-1"}.
 */
class LookupDaoMvccSnapshotTest {

    private SessionFactory writeSf;
    private SessionFactory readSf;
    private AtomicInteger readRollbackCount;

    private LookupDao<TestEntity> writeLookupDao;
    private LookupDao<TestEntity> readLookupDao;

    @BeforeEach
    void setUp() throws Exception {
        String dbName = "mvcc_" + System.nanoTime();
        String dbUrl = "jdbc:h2:mem:" + dbName + ";DB_CLOSE_DELAY=-1";

        readRollbackCount = new AtomicInteger(0);

        // writeSf creates the schema; all writes go through its own pool connection
        writeSf = buildWriteSf(dbUrl);
        // readSf uses a single tracked connection; all reads reuse C_read (close = no-op)
        readSf = buildReadSf(dbUrl);

        val shardingOptions = new ShardingBundleOptions();
        val shardInfoProvider = new ShardInfoProvider("default");
        val observer = new TimerObserver(
                new ListenerTriggeringObserver().addListener(new LoggingListener()));

        writeLookupDao = new LookupDao<>(DBShardingBundleBase.DEFAULT_NAMESPACE,
                new MultiTenantLookupDao<>(
                        Map.of(DBShardingBundleBase.DEFAULT_NAMESPACE, List.of(writeSf)),
                        TestEntity.class,
                        Map.of(DBShardingBundleBase.DEFAULT_NAMESPACE, new BalancedShardManager(1)),
                        Map.of(DBShardingBundleBase.DEFAULT_NAMESPACE, shardingOptions),
                        Map.of(DBShardingBundleBase.DEFAULT_NAMESPACE, shardInfoProvider),
                        observer));

        readLookupDao = new LookupDao<>(DBShardingBundleBase.DEFAULT_NAMESPACE,
                new MultiTenantLookupDao<>(
                        Map.of(DBShardingBundleBase.DEFAULT_NAMESPACE, List.of(readSf)),
                        TestEntity.class,
                        Map.of(DBShardingBundleBase.DEFAULT_NAMESPACE, new BalancedShardManager(1)),
                        Map.of(DBShardingBundleBase.DEFAULT_NAMESPACE, shardingOptions),
                        Map.of(DBShardingBundleBase.DEFAULT_NAMESPACE, shardInfoProvider),
                        observer));
    }

    @AfterEach
    void tearDown() {
        if (readSf != null) readSf.close();
        if (writeSf != null) writeSf.close();
    }

    /**
     * Sequence:
     * <ol>
     *   <li>Write {@code "version-1"} via writeLookupDao — commits on {@code C_write}</li>
     *   <li>Read via readLookupDao → {@code LookupDao → TransactionHandler(readSf, readOnly=true,
     *       transactionOptional=true)}. Implicit T1 opens on {@code C_read}. The fix calls
     *       {@code conn.rollback()} → T1 released, {@code C_read} is clean.</li>
     *   <li>Write {@code "version-2"} via writeLookupDao — commits on {@code C_write} (separate
     *       connection, entirely independent of {@code C_read})</li>
     *   <li>Read again via readLookupDao (same physical {@code C_read}):
     *       <ul>
     *         <li><b>With fix:</b> T1 was rolled back → new T2 at fresh snapshot → {@code "version-2"} ✓</li>
     *         <li><b>Without fix:</b> T1 still open (REPEATABLE_READ snapshot S1) → stale
     *             {@code "version-1"} ✗</li>
     *       </ul></li>
     * </ol>
     */
    @Test
    void get_afterCrossPoolUpdate_seesLatestCommit() throws Exception {
        // 1. Write "version-1" via writeLookupDao (standard pool, C_write)
        writeLookupDao.save(TestEntity.builder()
                .externalId("mvcc-1")
                .text("version-1")
                .build());

        // 2. Read via readLookupDao → LookupDao → TransactionHandler(readSf, true, true)
        //    Implicit T1 opens on C_read; fix calls conn.rollback() in afterEnd()
        val v1 = readLookupDao.get("mvcc-1");
        assertTrue(v1.isPresent());
        assertEquals("version-1", v1.get().getText());
        assertEquals(1, readRollbackCount.get(),
                "conn.rollback() must be called after the read-only session to release the snapshot");

        // 3. Update to "version-2" via writeLookupDao (commits on C_write — separate connection)
        writeLookupDao.update("mvcc-1", entity -> {
            entity.ifPresent(e -> e.setText("version-2"));
            return entity.orElse(null);
        });

        // 4. Read again via readLookupDao — same physical C_read is reused
        //    WITH FIX:    T1 rolled back → new T2 at fresh snapshot → "version-2" ✓
        //    WITHOUT FIX: T1 still open (REPEATABLE_READ snapshot S1) → stale "version-1" ✗
        val v2 = readLookupDao.get("mvcc-1");
        assertNotNull(v2);
        assertEquals("version-2", v2.get().getText(),
                "LookupDao.get() must see the committed update — not the stale MVCC snapshot from the prior read");
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
        // Schema already created by writeSf — do not recreate
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
    // Single-connection DataSource — simulates a connection pool with pool_size=1
    // -------------------------------------------------------------------------

    /**
     * A minimal {@link DataSource} that always returns the same physical JDBC connection.
     *
     * <p>{@code close()} is intercepted as a no-op so the underlying connection (and any open
     * implicit transaction) survives across Hibernate session open/close cycles. This faithfully
     * mimics real connection pools (HikariCP, c3p0) where {@code Connection.close()} returns the
     * connection to the pool without physically closing it — leaving any uncommitted implicit
     * transaction intact.
     *
     * <p>The physical connection is created with:
     * <ul>
     *   <li>{@code autoCommit=false} — pool default; triggers implicit transactions on reads that
     *       skip {@code beginTransaction()} (i.e. {@code transactionOptional=true} paths)</li>
     *   <li>{@code REPEATABLE_READ} isolation — H2 MVStore snapshot isolation; the snapshot is
     *       pinned until the transaction ends via commit or rollback</li>
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

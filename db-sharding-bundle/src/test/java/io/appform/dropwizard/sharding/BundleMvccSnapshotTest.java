package io.appform.dropwizard.sharding;

import com.codahale.metrics.health.HealthCheckRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.appform.dropwizard.sharding.config.ShardedHibernateFactory;
import io.appform.dropwizard.sharding.config.ShardingBundleOptions;
import io.appform.dropwizard.sharding.dao.LookupDao;
import io.appform.dropwizard.sharding.dao.testdata.entities.TestEntity;
import io.appform.dropwizard.sharding.sharding.InMemoryLocalShardBlacklistingStore;
import io.appform.dropwizard.sharding.sharding.ShardBlacklistingStore;
import io.dropwizard.Configuration;
import io.dropwizard.db.DataSourceFactory;
import io.dropwizard.jersey.DropwizardResourceConfig;
import io.dropwizard.jersey.setup.JerseyEnvironment;
import io.dropwizard.lifecycle.setup.LifecycleEnvironment;
import io.dropwizard.setup.AdminEnvironment;
import io.dropwizard.setup.Bootstrap;
import io.dropwizard.setup.Environment;
import lombok.Getter;
import lombok.val;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Regression test for the MVCC stale-read bug, exercised through the full bundle stack.
 *
 * <h3>The problem</h3>
 * {@link LookupDao#get(String)} uses {@code transactionOptional=true}, which means
 * {@code TransactionHandler} never calls {@code beginTransaction()}. Production connection pools
 * set {@code autoCommit=false}, so the first SQL statement silently opens an implicit database
 * transaction. If no {@code conn.rollback()} is issued before the connection is returned to the
 * pool, a REPEATABLE_READ snapshot is pinned on the pooled connection. A subsequent read on that
 * same physical connection sees stale data even after another connection committed fresh rows.
 *
 * <h3>Setup</h3>
 * Two separate bundle instances both point to the same H2 named database:
 * <ul>
 *   <li><b>writeBundle</b>: standard Tomcat JDBC pool — handles all inserts and updates.</li>
 *   <li><b>readBundle</b>: Tomcat JDBC pool configured exactly as production would be:
 *       {@code autoCommitByDefault=false}, {@code maxSize=1} (so the same physical connection
 *       is always reused on "return to pool"), and {@code REPEATABLE_READ} isolation.</li>
 * </ul>
 *
 * <h3>How the fix is validated</h3>
 * {@code TransactionHandler.afterEnd()} calls {@code conn.rollback()} when
 * {@code skipCommit && readOnly && sessionAcquired}. This releases the REPEATABLE_READ snapshot
 * on the pooled read connection before it is returned. Without the fix, the second
 * {@code readLookupDao.get()} returns stale {@code "version-1"}.
 */
class BundleMvccSnapshotTest {

    private static class TestConfig extends Configuration {
        @Getter
        private final ShardedHibernateFactory shards;

        TestConfig(ShardedHibernateFactory shards) {
            this.shards = shards;
        }
    }

    private final HealthCheckRegistry healthChecks = mock(HealthCheckRegistry.class);
    private final JerseyEnvironment jerseyEnvironment = mock(JerseyEnvironment.class);
    private final LifecycleEnvironment lifecycleEnvironment = mock(LifecycleEnvironment.class);
    private final Environment environment = mock(Environment.class);
    private final AdminEnvironment adminEnvironment = mock(AdminEnvironment.class);
    private final Bootstrap<?> bootstrap = mock(Bootstrap.class);

    private LookupDao<TestEntity> writeLookupDao;
    private LookupDao<TestEntity> readLookupDao;

    @BeforeEach
    void setUp() {
        when(jerseyEnvironment.getResourceConfig()).thenReturn(new DropwizardResourceConfig());
        when(environment.jersey()).thenReturn(jerseyEnvironment);
        when(environment.lifecycle()).thenReturn(lifecycleEnvironment);
        when(environment.healthChecks()).thenReturn(healthChecks);
        when(environment.admin()).thenReturn(adminEnvironment);
        when(bootstrap.getHealthCheckRegistry()).thenReturn(mock(HealthCheckRegistry.class));
        when(bootstrap.getObjectMapper()).thenReturn(mock(ObjectMapper.class));

        String dbUrl = "jdbc:h2:mem:mvcc_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1";

        // writeBundle: creates schema; owns all inserts and updates
        val writeBundle = initBundle(new TestConfig(ShardedHibernateFactory.builder()
                .shards(List.of(buildWriteDataSourceFactory(dbUrl)))
                .shardingOptions(ShardingBundleOptions.builder().build())
                .build()));
        writeLookupDao = writeBundle.createParentObjectDao(TestEntity.class);

        // readBundle: autoCommit=false, pool_size=1, REPEATABLE_READ — exactly like production
        val readBundle = initBundle(new TestConfig(ShardedHibernateFactory.builder()
                .shards(List.of(buildReadDataSourceFactory(dbUrl)))
                .shardingOptions(ShardingBundleOptions.builder().build())
                .build()));
        readLookupDao = readBundle.createParentObjectDao(TestEntity.class);
    }

    /**
     * Sequence:
     * <ol>
     *   <li>Write {@code "version-1"} via writeBundle (own Tomcat pool connection, commits).</li>
     *   <li>Read via readBundle → {@code LookupDao → TransactionHandler(readSf, readOnly=true,
     *       transactionOptional=true)}. Implicit T1 opens on the pooled read connection C_read.
     *       The fix calls {@code conn.rollback()} → T1 released, C_read is clean.</li>
     *   <li>Write {@code "version-2"} via writeBundle (commits on a separate connection).</li>
     *   <li>Read again via readBundle — the same physical connection C_read is reused:
     *       <ul>
     *         <li><b>With fix:</b> T1 was rolled back → new T2 at fresh snapshot → {@code "version-2"} ✓</li>
     *         <li><b>Without fix:</b> T1 still open (REPEATABLE_READ snapshot S1) → stale
     *             {@code "version-1"} ✗</li>
     *       </ul></li>
     * </ol>
     */
    @Test
    void get_afterCrossPoolUpdate_seesLatestCommit() throws Exception {
        // 1. Write "version-1"
        writeLookupDao.save(TestEntity.builder()
                .externalId("mvcc-1")
                .text("version-1")
                .build());

        // 2. Read via readBundle (transactionOptional=true) — fix calls conn.rollback() in afterEnd()
        val v1 = readLookupDao.get("mvcc-1");
        assertTrue(v1.isPresent());
        assertEquals("version-1", v1.get().getText());

        // 3. Update to "version-2" via writeBundle — commits on a separate connection
        writeLookupDao.update("mvcc-1", entity -> {
            entity.ifPresent(e -> e.setText("version-2"));
            return entity.orElse(null);
        });

        // 4. Read again — same physical C_read is reused (pool_size=1)
        //    WITH FIX:    T1 rolled back → new T2 at fresh snapshot → "version-2" ✓
        //    WITHOUT FIX: T1 still open (REPEATABLE_READ snapshot S1) → stale "version-1" ✗
        val v2 = readLookupDao.get("mvcc-1");
        assertTrue(v2.isPresent());
        assertEquals("version-2", v2.get().getText(),
                "LookupDao.get() must see the committed update — not a stale MVCC snapshot");
    }

    /**
     * Companion to {@link #get_afterCrossPoolUpdate_seesLatestCommit()} that exercises the
     * <b>error</b> completion path ({@code TransactionHandler.onError()}) rather than the normal
     * one ({@code afterEnd()}).
     *
     * <p>A transaction-optional read ({@code transactionOptional=true}, {@code readOnly=true})
     * runs its SELECT — silently opening an implicit DB transaction on the pooled read connection
     * — and then throws while applying the post-fetch handler. This drives
     * {@code TransactionExecutor} into {@code onError()}, which (like {@code afterEnd()}) must roll
     * back the implicit transaction before the connection is returned to the pool. Without the
     * {@code onError()} rollback, the REPEATABLE_READ snapshot stays pinned on the reused physical
     * connection and the next read returns stale {@code "version-1"}.
     *
     * <p>{@code GetByLookupKey.apply()} executes {@code getter.apply(...)} (the SELECT) <i>before</i>
     * {@code afterGet.apply(...)} (the handler), so a throwing handler guarantees the implicit
     * transaction is already open when the exception propagates.
     */
    @Test
    void get_afterErrorInOptionalRead_seesLatestCommit() throws Exception {
        // 1. Write "version-1"
        writeLookupDao.save(TestEntity.builder()
                .externalId("mvcc-err-1")
                .text("version-1")
                .build());

        // 2. Optional read whose SELECT runs (opening implicit T1 on C_read) and then throws in the
        //    post-fetch handler -> TransactionExecutor.onError(). The fix rolls T1 back here.
        final java.util.function.Function<TestEntity, TestEntity> throwingHandler = entity -> {
            throw new IllegalStateException("boom after select");
        };
        assertThrows(IllegalStateException.class, () ->
                readLookupDao.get("mvcc-err-1", throwingHandler));

        // 3. Update to "version-2" via writeBundle — commits on a separate connection
        writeLookupDao.update("mvcc-err-1", entity -> {
            entity.ifPresent(e -> e.setText("version-2"));
            return entity.orElse(null);
        });

        // 4. Read again — same physical C_read is reused (pool_size=1)
        //    WITH FIX:    onError rolled T1 back → new T2 at fresh snapshot → "version-2" ✓
        //    WITHOUT FIX: T1 still open (REPEATABLE_READ snapshot S1) → stale "version-1" ✗
        val v2 = readLookupDao.get("mvcc-err-1");
        assertTrue(v2.isPresent());
        assertEquals("version-2", v2.get().getText(),
                "After an errored optional read, onError() must roll back the connection so later "
                        + "reads see the latest commit — not a stale MVCC snapshot");
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private DBShardingBundleBase<TestConfig> initBundle(TestConfig config) {
        DBShardingBundleBase<TestConfig> bundle =
                new BalancedDBShardingBundle<TestConfig>(
                        "io.appform.dropwizard.sharding.dao.testdata.entities") {
                    @Override
                    protected ShardedHibernateFactory getConfig(TestConfig c) {
                        return c.getShards();
                    }

                    @Override
                    protected ShardBlacklistingStore getBlacklistingStore() {
                        return new InMemoryLocalShardBlacklistingStore();
                    }
                };
        bundle.initialize(bootstrap);
        bundle.run(config, environment);
        return bundle;
    }

    private DataSourceFactory buildWriteDataSourceFactory(String dbUrl) {
        val ds = new DataSourceFactory();
        ds.setDriverClass("org.h2.Driver");
        ds.setUrl(dbUrl);
        ds.setValidationQuery("select 1");
        ds.setProperties(new HashMap<>(Map.of(
                "hibernate.dialect", "org.hibernate.dialect.H2Dialect",
                "hibernate.hbm2ddl.auto", "create")));
        return ds;
    }

    private DataSourceFactory buildReadDataSourceFactory(String dbUrl) {
        val ds = new DataSourceFactory();
        ds.setDriverClass("org.h2.Driver");
        ds.setUrl(dbUrl);
        ds.setValidationQuery("select 1");
        // Pool configured exactly as production: autoCommit=false so reads open implicit
        // transactions, and pool_size=1 so the same physical connection is always reused
        // (close() returns it to the pool without resetting transaction state).
        ds.setAutoCommitByDefault(false);
        ds.setMaxSize(1);
        ds.setMinSize(1);
        ds.setInitialSize(1);
        ds.setDefaultTransactionIsolation(DataSourceFactory.TransactionIsolation.REPEATABLE_READ);
        ds.setProperties(new HashMap<>(Map.of(
                "hibernate.dialect", "org.hibernate.dialect.H2Dialect",
                "hibernate.hbm2ddl.auto", "none"))); // schema already created by writeBundle
        return ds;
    }
}

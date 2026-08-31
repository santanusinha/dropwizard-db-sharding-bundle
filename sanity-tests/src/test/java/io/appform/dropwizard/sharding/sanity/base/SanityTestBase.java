package io.appform.dropwizard.sharding.sanity.base;

import com.codahale.metrics.health.HealthCheckRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.appform.dropwizard.sharding.BalancedDBShardingBundle;
import io.appform.dropwizard.sharding.DBShardingBundleBase;
import io.appform.dropwizard.sharding.config.ShardedHibernateFactory;
import io.appform.dropwizard.sharding.config.ShardingBundleOptions;
import io.appform.dropwizard.sharding.dao.LookupDao;
import io.appform.dropwizard.sharding.dao.RelationalDao;
import io.appform.dropwizard.sharding.sanity.entities.SanityOrder;
import io.appform.dropwizard.sharding.sanity.entities.SanityOrderItem;
import io.appform.dropwizard.sharding.sharding.InMemoryLocalShardBlacklistingStore;
import io.appform.dropwizard.sharding.sharding.ShardBlacklistingStore;
import io.appform.dropwizard.sharding.utils.ShardCalculator;
import io.dropwizard.Configuration;
import io.dropwizard.db.DataSourceFactory;
import io.dropwizard.jersey.DropwizardResourceConfig;
import io.dropwizard.jersey.setup.JerseyEnvironment;
import io.dropwizard.lifecycle.setup.LifecycleEnvironment;
import io.dropwizard.setup.AdminEnvironment;
import io.dropwizard.setup.Bootstrap;
import io.dropwizard.setup.Environment;
import io.dropwizard.util.Duration;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import lombok.val;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.containers.MariaDBContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Base class for all sanity tests.
 *
 * <h3>Architecture</h3>
 * <pre>
 *  ┌──────────────────────────────────────────────────────────────┐
 *  │                    MariaDB Container                         │
 *  │              (singleton — shared across all tests)           │
 *  │                                                              │
 *  │   ┌─────────────┐              ┌─────────────┐               │
 *  │   │   shard_0   │              │   shard_1   │               │
 *  │   └──────┬──────┘              └──────┬──────┘               │
 *  └──────────┼────────────────────────────┼──────────────────────┘
 *             │                            │
 *             └────────────┬───────────────┘
 *                 ┌────────┴────────┐
 *                 │     Bundle      │
 *                 │  (Tomcat pool)  │
 *                 │  hbm2ddl=create │
 *                 │  pool: min=3    │
 *                 │        max=3    │
 *                 └────────┬────────┘
 *                          │
 *                  orderLookupDao
 *                  orderItemDao
 * </pre>
 *
 * <h3>Lifecycle</h3>
 * All infrastructure (container, databases, bundle, connection pools, DAOs) is
 * initialized <b>once</b> for the entire JVM and shared across all subclasses.
 * Each test method gets a clean slate via {@link #truncateAllTables()} in
 * {@code @BeforeEach}.
 *
 * <h3>Event tracing</h3>
 * The bundle uses {@link TracingDataSourceFactory} which wraps the real Tomcat pool
 * in a dynamic proxy to record JDBC lifecycle events ({@code GET_CONNECTION},
 * {@code COMMIT}, {@code ROLLBACK}, {@code RELEASE_CONNECTION}) in {@link DbEventTracker}.
 * A {@link TestTransactionInterceptor} is registered on each Hibernate SessionFactory
 * to record {@code BEGIN_TRANSACTION} events.
 *
 * <p>Tests can use:
 * <pre>
 *   var result = checkpoint(() -&gt; dao.get(key));
 *   assertTransactionEvents(result, 1, true);
 * </pre>
 */
@Slf4j
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public abstract class SanityTestBase {

    // -------------------------------------------------------------------------
    // Shared infrastructure — initialized ONCE for the entire JVM
    // -------------------------------------------------------------------------

    private static final String SHARD_0_DB = "shard_0";
    private static final String SHARD_1_DB = "shard_1";
    protected static final int NUM_SHARDS = 2;

    private static final MariaDBContainer<?> MARIADB;
    private static final DBShardingBundleBase<SanityTestConfig> BUNDLE;

    protected static final LookupDao<SanityOrder> orderLookupDao;
    protected static final RelationalDao<SanityOrderItem> orderItemDao;

    static {
        // 1. Start MariaDB container
//        MARIADB = new MariaDBContainer<>("mariadb:10.11")
//                .withDatabaseName(SHARD_0_DB)
//                .withUsername("test")
//                .withPassword("test")
//                .withCommand("--transaction-isolation=REPEATABLE-READ");
//        MARIADB.start();

        MARIADB = new MariaDBContainer<>("mariadb:10.11")
                .withDatabaseName(SHARD_0_DB)
                .withUsername("test")
                .withPassword("test")
                .withCommand("--transaction-isolation=READ-COMMITTED")
                .withCreateContainerCmdModifier(cmd ->
                        cmd.withHostConfig(cmd.getHostConfig()
                                .withPortBindings(new com.github.dockerjava.api.model.PortBinding(
                                        com.github.dockerjava.api.model.Ports.Binding.bindPort(3310),
                                        new com.github.dockerjava.api.model.ExposedPort(3306)))));
        MARIADB.start();

        // 2. Create second shard database
        try {
            createDatabase(SHARD_1_DB);
        } catch (SQLException e) {
            throw new RuntimeException("Failed to create shard_1 database", e);
        }

        // 3. Wire Dropwizard mocks
        val healthChecks = mock(HealthCheckRegistry.class);
        val jerseyEnvironment = mock(JerseyEnvironment.class);
        when(jerseyEnvironment.getResourceConfig()).thenReturn(new DropwizardResourceConfig());
        val lifecycleEnvironment = mock(LifecycleEnvironment.class);
        val environment = mock(Environment.class);
        when(environment.jersey()).thenReturn(jerseyEnvironment);
        when(environment.lifecycle()).thenReturn(lifecycleEnvironment);
        when(environment.healthChecks()).thenReturn(healthChecks);
        when(environment.admin()).thenReturn(mock(AdminEnvironment.class));
        val bootstrap = mock(Bootstrap.class);
        when(bootstrap.getHealthCheckRegistry()).thenReturn(mock(HealthCheckRegistry.class));
        when(bootstrap.getObjectMapper()).thenReturn(new ObjectMapper());

        // 4. Build and initialize the bundle
        String shard0Url = MARIADB.getJdbcUrl();
        String shard1Url = shard0Url.replace("/" + SHARD_0_DB, "/" + SHARD_1_DB);


        val config = new SanityTestConfig(ShardedHibernateFactory.builder()
                .shards(List.of(buildDataSource(shard0Url), buildDataSource(shard1Url)))
                .shardingOptions(ShardingBundleOptions.builder().build())
                .build());

        BUNDLE = new BalancedDBShardingBundle<SanityTestConfig>(
                SanityOrder.class, SanityOrderItem.class) {
            @Override
            protected ShardedHibernateFactory getConfig(SanityTestConfig c) {
                return c.getShards();
            }

            @Override
            protected ShardBlacklistingStore getBlacklistingStore() {
                return new InMemoryLocalShardBlacklistingStore();
            }
        };
        BUNDLE.initialize(bootstrap);
        BUNDLE.run(config, environment);

        // 5. Create DAOs
        orderLookupDao = BUNDLE.createParentObjectDao(SanityOrder.class);
        orderItemDao = BUNDLE.createRelatedObjectDao(SanityOrderItem.class);

        log.info("SanityTestBase: static initialization complete — bundle, pools, and DAOs ready");
    }

    // -------------------------------------------------------------------------
    // Config wrapper
    // -------------------------------------------------------------------------

    protected static class SanityTestConfig extends Configuration {
        @Getter
        private final ShardedHibernateFactory shards;

        SanityTestConfig(ShardedHibernateFactory shards) {
            this.shards = shards;
        }
    }

    // -------------------------------------------------------------------------
    // Static helpers used during initialization
    // -------------------------------------------------------------------------

    private static void createDatabase(String dbName) throws SQLException {
        String rootUrl = MARIADB.getJdbcUrl().replace("/" + SHARD_0_DB, "/");
        try (Connection conn = DriverManager.getConnection(rootUrl, "root", MARIADB.getPassword())) {
            conn.createStatement().execute("CREATE DATABASE IF NOT EXISTS " + dbName);
            conn.createStatement().execute(
                    "GRANT ALL PRIVILEGES ON " + dbName + ".* TO '" + MARIADB.getUsername() + "'@'%'");
            conn.createStatement().execute("FLUSH PRIVILEGES");
        }
    }


    /**
     * Builds a {@link TracingDataSourceFactory} for a single shard.
     *
     * <p>Uses {@link TracingDataSourceFactory} (extends {@link DataSourceFactory}) which
     * creates a real Tomcat connection pool and wraps it in a dynamic proxy that records
     * JDBC events in {@link DbEventTracker}.
     *
     * <p>Also registers {@link TestTransactionInterceptor} via the Hibernate property
     * {@code hibernate.session_factory.interceptor} to capture BEGIN_TRANSACTION events.
     *
     * <p><b>Note:</b> {@code autoCommitByDefault} must NOT be set here. The sharding
     * bundle's {@code SessionFactoryFactory.disableAutoCommit()} handles this — it calls
     * {@code setAutoCommitByDefault(false)} on the DataSourceFactory before building the
     * pool, mirroring production behavior.
     */
    private static DataSourceFactory buildDataSource(String jdbcUrl) {
        val ds = new TracingDataSourceFactory();
        ds.setDriverClass("org.mariadb.jdbc.Driver");
        ds.setUrl(jdbcUrl);
        ds.setUser(MARIADB.getUsername());
        ds.setPassword(MARIADB.getPassword());
        ds.setValidationQuery("SELECT 1");
        ds.setMinSize(3);
        ds.setMaxSize(3);
        ds.setInitialSize(3);

        val props = new HashMap<>(Map.of(
                "hibernate.dialect", "org.hibernate.dialect.MariaDBDialect",
                "hibernate.hbm2ddl.auto", "create",
                "hibernate.session_factory.interceptor", TestTransactionInterceptor.class.getName()));
        ds.setProperties(props);

        return ds;
    }

    // -------------------------------------------------------------------------
    // Checkpoint helpers — execute an operation and capture/assert DB events
    // -------------------------------------------------------------------------

    /**
     * Result of a checkpoint: the captured events and the operation's return value.
     */
    @Getter
    protected static class CheckpointResult<T> {
        private final T value;
        private final List<DbEventTracker.DbEvent> events;
        private final boolean autoCommitDisabledThroughout;

        CheckpointResult(T value, List<DbEventTracker.DbEvent> events, boolean autoCommitDisabledThroughout) {
            this.value = value;
            this.events = events;
            this.autoCommitDisabledThroughout = autoCommitDisabledThroughout;
        }
    }

    /**
     * Executes a DAO operation inside a checkpoint: clears the tracker, runs the
     * operation, captures events and autoCommit latch state.
     */
    protected <T> CheckpointResult<T> checkpoint(Supplier<T> operation) {
        DbEventTracker.clear();
        T value = operation.get();
        return new CheckpointResult<>(
                value,
                DbEventTracker.getEvents(),
                DbEventTracker.wasAutoCommitDisabledThroughout()
        );
    }

    /**
     * Overload for void operations.
     */
    protected CheckpointResult<Void> checkpoint(Runnable operation) {
        DbEventTracker.clear();
        operation.run();
        return new CheckpointResult<>(
                null,
                DbEventTracker.getEvents(),
                DbEventTracker.wasAutoCommitDisabledThroughout()
        );
    }

    /**
     * Asserts the exact event sequence for a transaction.
     *
     * <p>Expected pattern:
     * <pre>
     *   [BEGIN_TRANSACTION, GET_CONNECTION, PREPARE_STATEMENT × N, COMMIT/ROLLBACK, RELEASE_CONNECTION]
     * </pre>
     *
     * <p>Validates: correct size, correct event at each position, and autoCommit
     * was disabled throughout.
     *
     * @param result            the checkpoint result to assert on
     * @param prepareStatements expected number of PREPARE_STATEMENT events
     * @param committed         {@code true} for COMMIT, {@code false} for ROLLBACK
     */
    protected void assertTransactionEvents(CheckpointResult<?> result, int prepareStatements, boolean committed) {
        val events = result.getEvents();
        val terminalEvent = committed ? DbEventTracker.DbEvent.COMMIT : DbEventTracker.DbEvent.ROLLBACK;
        int expectedSize = prepareStatements + 4; // BEGIN + GET_CONN + N*PREPARE + COMMIT/ROLLBACK + RELEASE

        assertEquals(expectedSize, events.size(),
                "Expected " + expectedSize + " events but got " + events.size() + ". Events: " + events);

        int i = 0;
        assertEquals(DbEventTracker.DbEvent.BEGIN_TRANSACTION, events.get(i++),
                "Position 0: expected BEGIN_TRANSACTION. Events: " + events);
        assertEquals(DbEventTracker.DbEvent.GET_CONNECTION, events.get(i++),
                "Position 1: expected GET_CONNECTION. Events: " + events);
        for (int p = 0; p < prepareStatements; p++) {
            assertEquals(DbEventTracker.DbEvent.PREPARE_STATEMENT, events.get(i++),
                    "Position " + (p + 2) + ": expected PREPARE_STATEMENT. Events: " + events);
        }
        assertEquals(terminalEvent, events.get(i++),
                "Position " + (prepareStatements + 2) + ": expected " + terminalEvent.getLabel() + ". Events: " + events);
        assertEquals(DbEventTracker.DbEvent.RELEASE_CONNECTION, events.get(i),
                "Position " + (prepareStatements + 3) + ": expected RELEASE_CONNECTION. Events: " + events);

        assertTrue(result.isAutoCommitDisabledThroughout(),
                "autoCommit must be disabled throughout. Events: " + events);
    }

    /**
     * Asserts transaction event structure without checking the number of PREPARE_STATEMENTs.
     *
     * <p>Validates only the bookend events:
     * <pre>
     *   position 0:   BEGIN_TRANSACTION
     *   position 1:   GET_CONNECTION
     *   ...           (any events in between — not checked)
     *   position n-1: COMMIT or ROLLBACK
     *   position n:   RELEASE_CONNECTION
     * </pre>
     *
     * <p>Also validates autoCommit was disabled throughout and that at least 4 events
     * exist (the minimum: BEGIN, GET_CONNECTION, COMMIT/ROLLBACK, RELEASE).
     *
     * @param result    the checkpoint result to assert on
     * @param committed {@code true} for COMMIT, {@code false} for ROLLBACK
     */
    protected void assertTransactionEvents(CheckpointResult<?> result, boolean committed) {
        val events = result.getEvents();
        val terminalEvent = committed ? DbEventTracker.DbEvent.COMMIT : DbEventTracker.DbEvent.ROLLBACK;

        assertTrue(events.size() >= 4,
                "Expected at least 4 events but got " + events.size() + ". Events: " + events);

        assertEquals(DbEventTracker.DbEvent.BEGIN_TRANSACTION, events.get(0),
                "Position 0: expected BEGIN_TRANSACTION. Events: " + events);
        assertEquals(DbEventTracker.DbEvent.GET_CONNECTION, events.get(1),
                "Position 1: expected GET_CONNECTION. Events: " + events);
        assertEquals(terminalEvent, events.get(events.size() - 2),
                "Position " + (events.size() - 2) + ": expected " + terminalEvent.getLabel() + ". Events: " + events);
        assertEquals(DbEventTracker.DbEvent.RELEASE_CONNECTION, events.get(events.size() - 1),
                "Last position: expected RELEASE_CONNECTION. Events: " + events);

        assertTrue(result.isAutoCommitDisabledThroughout(),
                "autoCommit must be disabled throughout. Events: " + events);
    }

    /**
     * Asserts the event sequence for scatter-gather operations that run one
     * transaction per shard.
     *
     * <p>Expected pattern (repeated {@code numShards} times):
     * <pre>
     *   [BEGIN, GET_CONNECTION, PREPARE_STATEMENT × prepareStatementsPerShard, COMMIT/ROLLBACK, RELEASE_CONNECTION]
     * </pre>
     *
     * @param result                    the checkpoint result to assert on
     * @param numShards                 number of shards (= number of sequential transactions)
     * @param prepareStatementsPerShard expected PREPARE_STATEMENT count per shard
     * @param committed                 {@code true} for COMMIT, {@code false} for ROLLBACK
     */
    protected void assertScatterGatherEvents(CheckpointResult<?> result, int numShards,
                                             int prepareStatementsPerShard, boolean committed) {
        val events = result.getEvents();
        val terminalEvent = committed ? DbEventTracker.DbEvent.COMMIT : DbEventTracker.DbEvent.ROLLBACK;
        int eventsPerShard = prepareStatementsPerShard + 4; // BEGIN + GET_CONN + N*PREPARE + COMMIT/ROLLBACK + RELEASE
        int expectedTotal = numShards * eventsPerShard;

        assertEquals(expectedTotal, events.size(),
                "Expected " + expectedTotal + " events (" + numShards + " shards × " + eventsPerShard +
                        " events/shard) but got " + events.size() + ". Events: " + events);

        for (int shard = 0; shard < numShards; shard++) {
            int base = shard * eventsPerShard;
            int i = base;
            assertEquals(DbEventTracker.DbEvent.BEGIN_TRANSACTION, events.get(i++),
                    "Shard " + shard + " position " + base + ": expected BEGIN_TRANSACTION. Events: " + events);
            assertEquals(DbEventTracker.DbEvent.GET_CONNECTION, events.get(i++),
                    "Shard " + shard + " position " + (base + 1) + ": expected GET_CONNECTION. Events: " + events);
            for (int p = 0; p < prepareStatementsPerShard; p++) {
                assertEquals(DbEventTracker.DbEvent.PREPARE_STATEMENT, events.get(i++),
                        "Shard " + shard + " position " + (base + 2 + p) + ": expected PREPARE_STATEMENT. Events: " + events);
            }
            assertEquals(terminalEvent, events.get(i++),
                    "Shard " + shard + " position " + (base + 2 + prepareStatementsPerShard) +
                            ": expected " + terminalEvent.getLabel() + ". Events: " + events);
            assertEquals(DbEventTracker.DbEvent.RELEASE_CONNECTION, events.get(i),
                    "Shard " + shard + " position " + (base + 3 + prepareStatementsPerShard) +
                            ": expected RELEASE_CONNECTION. Events: " + events);
        }

        assertTrue(result.isAutoCommitDisabledThroughout(),
                "autoCommit must be disabled throughout. Events: " + events);
    }

    /**
     * Queries a specific shard database directly via JDBC to check if an order item
     * exists there. Bypasses the DAO/sharding layer entirely.
     *
     * @param shardIndex 0 or 1
     * @param orderId    the order_id value
     * @param itemName   the item_name value
     * @return true if a row with that (order_id, item_name) exists on the specified shard
     */
    protected boolean existsOnShardItems(int shardIndex, String orderId, String itemName) throws SQLException {
        String db = shardIndex == 0 ? SHARD_0_DB : SHARD_1_DB;
        String url = MARIADB.getJdbcUrl().replace("/" + SHARD_0_DB, "/" + db);
        try (Connection conn = DriverManager.getConnection(url, MARIADB.getUsername(), MARIADB.getPassword());
             var ps = conn.prepareStatement("SELECT 1 FROM sanity_order_items WHERE order_id = ? AND item_name = ?")) {
            ps.setString(1, orderId);
            ps.setString(2, itemName);
            try (var rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    /**
     * Returns the JDBC URL for the MariaDB container (pointing to shard_0).
     * Subclasses can use this for direct JDBC queries.
     */
    protected static String getMariaDbUrl() {
        return MARIADB.getJdbcUrl();
    }

    /**
     * Truncates all sanity test tables across both shards.
     * Call in {@code @BeforeEach} if tests need a clean slate between test methods.
     */
    protected void truncateAllTables() throws SQLException {
        for (String db : List.of(SHARD_0_DB, SHARD_1_DB)) {
            String url = MARIADB.getJdbcUrl().replace("/" + SHARD_0_DB, "/" + db);
            try (Connection conn = DriverManager.getConnection(
                    url, MARIADB.getUsername(), MARIADB.getPassword())) {
                conn.createStatement().execute("SET FOREIGN_KEY_CHECKS = 0");
                conn.createStatement().execute("TRUNCATE TABLE sanity_order_items");
                conn.createStatement().execute("TRUNCATE TABLE sanity_orders");
                conn.createStatement().execute("SET FOREIGN_KEY_CHECKS = 1");
            }
        }
    }

    /**
     * Queries a specific shard database directly via JDBC to check if an order
     * exists there. Bypasses the DAO/sharding layer entirely.
     *
     * @param shardIndex 0 or 1
     * @param orderId    the order_id value to search for
     * @return true if a row with that order_id exists on the specified shard
     */
    protected boolean existsOnShard(int shardIndex, String orderId) throws SQLException {
        String db = shardIndex == 0 ? SHARD_0_DB : SHARD_1_DB;
        String url = MARIADB.getJdbcUrl().replace("/" + SHARD_0_DB, "/" + db);
        try (Connection conn = DriverManager.getConnection(url, MARIADB.getUsername(), MARIADB.getPassword());
             var ps = conn.prepareStatement("SELECT 1 FROM sanity_orders WHERE order_id = ?")) {
            ps.setString(1, orderId);
            try (var rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    /**
     * Returns the shard index (0 or 1) that the sharding bundle would route
     * the given orderId to, based on the Murmur3 hash.
     */
    protected int shardForKey(String orderId) {
        // With 2 shards and BalancedShardManager:
        // bucket = abs(murmur3_128(key)) % 1024
        // shard 0: buckets [0, 511], shard 1: buckets [512, 1023]
        int hash = com.google.common.hash.Hashing.murmur3_128()
                .hashString(orderId, java.nio.charset.StandardCharsets.UTF_8)
                .asInt();
        hash = hash < 0 ? -hash : hash;
        int bucket = hash % 1024;
        return bucket < 512 ? 0 : 1;
    }

    /**
     * Returns the number of distinct shards that the given keys will be routed to,
     * as determined by the supplied {@link ShardCalculator}.
     *
     * <p>Usage: {@code uniqueShardCount(orderLookupDao.getShardCalculator(), keys)}
     * or {@code uniqueShardCount(orderItemDao.getShardCalculator(), keys)} — any
     * {@code ShardedDao}'s calculator works since shard keys are always {@code String}.
     *
     * @param shardCalculator the shard calculator to resolve each key's shard id with
     * @param keys            the keys to bucket
     * @return the count of distinct shard ids across all keys
     */
    protected int uniqueShardCount(ShardCalculator<String> shardCalculator, List<String> keys) {
        return (int) keys.stream()
                .map(shardCalculator::shardId)
                .distinct()
                .count();
    }
}

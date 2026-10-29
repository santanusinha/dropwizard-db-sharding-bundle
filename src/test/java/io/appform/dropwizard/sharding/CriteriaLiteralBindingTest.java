package io.appform.dropwizard.sharding;

import com.codahale.metrics.health.HealthCheckRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.appform.dropwizard.sharding.config.ShardedHibernateFactory;
import io.appform.dropwizard.sharding.config.ShardingBundleOptions;
import io.appform.dropwizard.sharding.dao.RelationalDao;
import io.appform.dropwizard.sharding.dao.testdata.entities.Order;
import io.appform.dropwizard.sharding.dao.testdata.entities.OrderItem;
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
import org.hibernate.cfg.AvailableSettings;
import org.hibernate.query.criteria.LiteralHandlingMode;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Verifies that {@link io.appform.dropwizard.sharding.hibernate.SessionFactoryFactory} enforces
 * {@code hibernate.criteria.literal_handling_mode=BIND} so JPA Criteria numeric literals are bound
 * as {@code ?} parameters rather than inlined into the SQL text.
 */
class CriteriaLiteralBindingTest {

    public static class CapturingStatementInspector implements StatementInspector {
        private static final List<String> CAPTURED_SQL = new CopyOnWriteArrayList<>();

        static void clear() {
            CAPTURED_SQL.clear();
        }

        static List<String> selectStatementsForAmount() {
            return CAPTURED_SQL.stream()
                    .filter(sql -> sql.toLowerCase().contains("select") && sql.toLowerCase().contains("amount"))
                    .collect(Collectors.toList());
        }

        @Override
        public String inspect(String sql) {
            CAPTURED_SQL.add(sql);
            return sql;
        }
    }

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

    private DBShardingBundleBase<TestConfig> bundle;
    private RelationalDao<Order> orderDao;

    @BeforeEach
    void setUp() {
        CapturingStatementInspector.clear();
        when(jerseyEnvironment.getResourceConfig()).thenReturn(new DropwizardResourceConfig());
        when(environment.jersey()).thenReturn(jerseyEnvironment);
        when(environment.lifecycle()).thenReturn(lifecycleEnvironment);
        when(environment.healthChecks()).thenReturn(healthChecks);
        when(environment.admin()).thenReturn(adminEnvironment);
        when(bootstrap.getHealthCheckRegistry()).thenReturn(mock(HealthCheckRegistry.class));
        when(bootstrap.getObjectMapper()).thenReturn(mock(ObjectMapper.class));

        String dbUrl = "jdbc:h2:mem:criteria_bind_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1";
        val ds = new DataSourceFactory();
        ds.setDriverClass("org.h2.Driver");
        ds.setUrl(dbUrl);
        ds.setValidationQuery("select 1");
        // Explicitly attempt to override with INLINE to verify the bundle enforces BIND
        ds.setProperties(new HashMap<>(Map.of(
                "hibernate.dialect", "org.hibernate.dialect.H2Dialect",
                "hibernate.hbm2ddl.auto", "create",
                AvailableSettings.CRITERIA_LITERAL_HANDLING_MODE, LiteralHandlingMode.INLINE.name(),
                AvailableSettings.STATEMENT_INSPECTOR, CapturingStatementInspector.class.getName())));

        val config = new TestConfig(ShardedHibernateFactory.builder()
                .shards(List.of(ds))
                .shardingOptions(ShardingBundleOptions.builder().build())
                .build());

        bundle = new BalancedDBShardingBundle<TestConfig>(Order.class, OrderItem.class) {
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
        orderDao = bundle.createRelatedObjectDao(Order.class);
    }

    @Test
    void criteriaNumericLiteralsAreBoundAndCannotBeOverridden() throws Exception {
        bundle.getSessionFactories().forEach(sf ->
                assertEquals(
                        LiteralHandlingMode.BIND.name(),
                        sf.getProperties().get(AvailableSettings.CRITERIA_LITERAL_HANDLING_MODE)));

        final String customer = "cust-bind-1";
        orderDao.save(customer, Order.builder()
                .customerId(customer)
                .orderId("ORD-1")
                .amount(13579)
                .build());
        orderDao.save(customer, Order.builder()
                .customerId(customer)
                .orderId("ORD-2")
                .amount(24680)
                .build());

        CapturingStatementInspector.clear();

        val firstResult = orderDao.select(
                customer,
                (root, query, cb) -> query.where(
                        cb.and(
                                cb.equal(root.get("customerId"), customer),
                                cb.equal(root.get("amount"), 13579))),
                0,
                10);
        assertEquals(1, firstResult.size());
        assertEquals("ORD-1", firstResult.get(0).getOrderId());

        val secondResult = orderDao.select(
                customer,
                (root, query, cb) -> query.where(
                        cb.and(
                                cb.equal(root.get("customerId"), customer),
                                cb.equal(root.get("amount"), 24680))),
                0,
                10);
        assertEquals(1, secondResult.size());
        assertEquals("ORD-2", secondResult.get(0).getOrderId());

        val capturedSelects = CapturingStatementInspector.selectStatementsForAmount();
        assertEquals(2, capturedSelects.size());

        for (String sql : capturedSelects) {
            assertFalse(sql.contains("13579"), "Numeric literal 13579 must not be inlined in SQL: " + sql);
            assertFalse(sql.contains("24680"), "Numeric literal 24680 must not be inlined in SQL: " + sql);
        }
        assertEquals(
                capturedSelects.get(0),
                capturedSelects.get(1),
                "Criteria queries differing only in numeric literal values must produce identical SQL");
    }
}

package io.appform.dropwizard.sharding;

import io.appform.dropwizard.sharding.config.ShardedHibernateFactory;
import io.appform.dropwizard.sharding.dao.RelationalDao;
import io.appform.dropwizard.sharding.dao.testdata.entities.Order;
import io.appform.dropwizard.sharding.dao.testdata.entities.OrderItem;
import lombok.val;
import org.hibernate.cfg.AvailableSettings;
import org.hibernate.query.criteria.LiteralHandlingMode;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class CriteriaLiteralBindingTest extends BundleBasedTestBase {

    public static class CapturingInspector implements StatementInspector {
        private static final List<String> SQL = new CopyOnWriteArrayList<>();

        @Override
        public String inspect(String sql) {
            SQL.add(sql);
            return sql;
        }
    }

    @Override
    protected DBShardingBundleBase<TestConfig> getBundle() {
        testConfig.getShards().getShards().forEach(shard -> {
            shard.getProperties().put(
                    AvailableSettings.CRITERIA_LITERAL_HANDLING_MODE, LiteralHandlingMode.INLINE.name());
            shard.getProperties().put(
                    AvailableSettings.STATEMENT_INSPECTOR, CapturingInspector.class.getName());
        });
        return new BalancedDBShardingBundle<TestConfig>(Order.class, OrderItem.class) {
            @Override
            protected ShardedHibernateFactory getConfig(TestConfig config) {
                return testConfig.getShards();
            }
        };
    }

    @Test
    void criteriaNumericLiteralsAreBoundAndCannotBeOverridden() throws Exception {
        val bundle = getBundle();
        bundle.initialize(bootstrap);
        bundle.run(testConfig, environment);

        bundle.getSessionFactories().forEach(sf ->
                assertEquals(
                        LiteralHandlingMode.BIND.name(),
                        sf.getProperties().get(AvailableSettings.CRITERIA_LITERAL_HANDLING_MODE)));

        RelationalDao<Order> orderDao = bundle.createRelatedObjectDao(Order.class);
        final String customer = "cust-1";
        orderDao.save(customer, Order.builder().customerId(customer).orderId("ORD-1").amount(13579).build());
        orderDao.save(customer, Order.builder().customerId(customer).orderId("ORD-2").amount(24680).build());

        CapturingInspector.SQL.clear();

        assertEquals(1, orderDao.select(customer,
                (root, q, cb) -> q.where(cb.equal(root.get("amount"), 13579)), 0, 10).size());
        assertEquals(1, orderDao.select(customer,
                (root, q, cb) -> q.where(cb.equal(root.get("amount"), 24680)), 0, 10).size());

        assertEquals(2, CapturingInspector.SQL.size());
        assertEquals(CapturingInspector.SQL.get(0), CapturingInspector.SQL.get(1));
        assertFalse(CapturingInspector.SQL.get(0).contains("13579"));
        assertFalse(CapturingInspector.SQL.get(1).contains("24680"));
    }
}

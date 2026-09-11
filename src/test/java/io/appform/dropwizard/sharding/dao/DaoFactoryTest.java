package io.appform.dropwizard.sharding.dao;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import io.appform.dropwizard.sharding.DBShardingBundleBase;
import io.appform.dropwizard.sharding.ShardInfoProvider;
import io.appform.dropwizard.sharding.config.ShardingBundleOptions;
import io.appform.dropwizard.sharding.dao.testdata.OrderDao;
import io.appform.dropwizard.sharding.dao.testdata.entities.Order;
import io.appform.dropwizard.sharding.dao.testdata.entities.OrderItem;
import io.appform.dropwizard.sharding.dao.testdata.entities.TestEntity;
import io.appform.dropwizard.sharding.observers.internal.TerminalTransactionObserver;
import io.appform.dropwizard.sharding.sharding.BalancedShardManager;
import io.appform.dropwizard.sharding.sharding.ShardManager;
import org.hibernate.SessionFactory;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class DaoFactoryTest {

    private static final String NS = DBShardingBundleBase.DEFAULT_NAMESPACE;

    private final List<SessionFactory> sessionFactories = Lists.newArrayList();
    private ShardManager shardManager;

    private SessionFactory buildSessionFactory(final String dbName) {
        final Configuration configuration = new Configuration();
        configuration.setProperty("hibernate.dialect", "org.hibernate.dialect.H2Dialect");
        configuration.setProperty("hibernate.connection.driver_class", "org.h2.Driver");
        configuration.setProperty("hibernate.connection.url", "jdbc:h2:mem:" + dbName);
        configuration.setProperty("hibernate.hbm2ddl.auto", "create");
        configuration.setProperty("hibernate.current_session_context_class", "managed");
        configuration.addAnnotatedClass(TestEntity.class);
        configuration.addAnnotatedClass(Order.class);
        configuration.addAnnotatedClass(OrderItem.class);
        return configuration.buildSessionFactory(
                new StandardServiceRegistryBuilder().applySettings(configuration.getProperties()).build());
    }

    @BeforeEach
    void before() {
        for (int i = 0; i < 2; i++) {
            sessionFactories.add(buildSessionFactory(String.format("factory_db_%d", i)));
        }
        shardManager = new BalancedShardManager(sessionFactories.size());
    }

    @AfterEach
    void after() {
        sessionFactories.forEach(SessionFactory::close);
    }

    @Test
    void createsWorkingMultiTenantLookupDao() throws Exception {
        final MultiTenantLookupDao<TestEntity> dao = DaoFactory.INSTANCE.createMultiTenantLookupDao(
                Map.of(NS, sessionFactories),
                TestEntity.class,
                Map.of(NS, shardManager),
                Map.of(NS, new ShardingBundleOptions()),
                Map.of(NS, new ShardInfoProvider(NS)),
                new TerminalTransactionObserver());
        assertNotNull(dao);
        dao.save(NS, TestEntity.builder().externalId("customer-1").text("hello").build());
        assertEquals("customer-1", dao.get(NS, "customer-1").orElseThrow().getExternalId());
    }

    @Test
    void createsWorkingSingleTenantLookupDao() throws Exception {
        final LookupDao<TestEntity> dao = DaoFactory.INSTANCE.createLookupDao(
                NS,
                DaoFactory.INSTANCE.createMultiTenantLookupDao(
                        Map.of(NS, sessionFactories),
                        TestEntity.class,
                        Map.of(NS, shardManager),
                        Map.of(NS, new ShardingBundleOptions()),
                        Map.of(NS, new ShardInfoProvider(NS)),
                        new TerminalTransactionObserver()));
        dao.save(TestEntity.builder().externalId("customer-2").text("hello").build());
        assertEquals("customer-2", dao.get("customer-2").orElseThrow().getExternalId());
    }

    @Test
    void createsWorkingWrapperDao() {
        final WrapperDao<Order, OrderDao> dao = DaoFactory.INSTANCE.createWrapperDao(
                NS, sessionFactories, OrderDao.class, shardManager);

        final String customer = "customer-3";
        final Order order = Order.builder()
                .customerId(customer)
                .build();
        final OrderItem item = OrderItem.builder()
                .order(order)
                .name("Item A")
                .build();
        order.setItems(ImmutableList.of(item));

        final Order saveResult = dao.forParent(customer).save(order);
        final Order result = dao.forParent(customer).get(saveResult.getId());

        assertEquals(saveResult.getId(), result.getId());
        assertEquals(customer, result.getCustomerId());
    }
}

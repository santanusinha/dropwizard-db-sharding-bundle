package io.appform.dropwizard.sharding.dao;

import io.appform.dropwizard.sharding.DBShardingBundleBase;
import io.appform.dropwizard.sharding.dao.testdata.OrderDao;
import io.appform.dropwizard.sharding.dao.testdata.entities.Order;
import io.appform.dropwizard.sharding.dao.testdata.entities.OrderItem;
import io.appform.dropwizard.sharding.sharding.BalancedShardManager;
import io.appform.dropwizard.sharding.sharding.ShardManager;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.Transaction;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.cfg.Configuration;
import org.hibernate.context.internal.ManagedSessionContext;
import org.hibernate.resource.transaction.spi.TransactionStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests at DAO level focusing on transactional semantics of {@link WrapperDao}'s
 * internal TransactionHandler logic when a Hibernate {@link Session} + {@link Transaction} may
 * already be bound to the calling thread via {@link ManagedSessionContext}.
 */
class WrapperDaoTransactionReuseTest {

    private final List<SessionFactory> sessionFactories = new ArrayList<>();
    private WrapperDao<Order, OrderDao> dao;

    private SessionFactory buildSessionFactory(String dbName) {
        Configuration configuration = getConfiguration(dbName);
        configuration.addAnnotatedClass(Order.class);
        configuration.addAnnotatedClass(OrderItem.class);
        StandardServiceRegistry serviceRegistry = new StandardServiceRegistryBuilder().applySettings(
                configuration.getProperties()).build();
        return configuration.buildSessionFactory(serviceRegistry);
    }

    private static Configuration getConfiguration(String dbName) {
        Configuration configuration = new Configuration();
        configuration.setProperty("hibernate.dialect", "org.hibernate.dialect.H2Dialect");
        configuration.setProperty("hibernate.connection.driver_class", "org.h2.Driver");
        configuration.setProperty("hibernate.connection.url",
                "jdbc:h2:mem:" + dbName + ";DB_CLOSE_DELAY=-1");
        configuration.setProperty("hibernate.hbm2ddl.auto", "create-drop");
        configuration.setProperty("hibernate.current_session_context_class", "managed");
        return configuration;
    }

    @BeforeEach
    void setUp() {
        for (int i = 0; i < 2; i++) {
            sessionFactories.add(buildSessionFactory("reuse_tx_db_" + i));
        }
        ShardManager shardManager = new BalancedShardManager(sessionFactories.size());
        dao = new WrapperDao<>(DBShardingBundleBase.DEFAULT_NAMESPACE, sessionFactories,
                OrderDao.class, shardManager);
    }

    @AfterEach
    void tearDown() {
        for (SessionFactory sf : sessionFactories) {
            if (ManagedSessionContext.hasBind(sf)) {
                ManagedSessionContext.unbind(sf);
            }
            sf.close();
        }
        sessionFactories.clear();
    }

    private void assertReuse(SessionFactory sf, Session session, Transaction txn) {
        assertSame(session, sf.getCurrentSession(), "Bound current session should remain the same");
        assertTrue(session.isOpen(), "Session should remain open during outer transaction");
        assertSame(txn, session.getTransaction(), "Same transaction object should be reused");
        assertEquals(TransactionStatus.ACTIVE, txn.getStatus(), "Transaction should stay ACTIVE");
    }

    @Test
    void testTransactionReuseAcrossMultipleDaoCalls() {
        String parentKey = "customer-tx-reuse"; // Determines shard
        int shardId = dao.getShardCalculator()
                .shardId(DBShardingBundleBase.DEFAULT_NAMESPACE, parentKey);
        SessionFactory targetSessionFactory = sessionFactories.get(shardId);

        // Outer application layer begins and binds session + transaction.
        Session outerSession = targetSessionFactory.openSession();
        ManagedSessionContext.bind(outerSession);
        Transaction outerTxn = outerSession.beginTransaction();
        assertTrue(outerTxn.isActive());
        assertEquals(TransactionStatus.ACTIVE, outerTxn.getStatus());

        // First DAO transactional call (save) should join outer transaction.
        Order order = Order.builder().customerId(parentKey).build();
        OrderItem item = OrderItem.builder().order(order).name("First Item").build();
        order.setItems(List.of(item));
        Order persisted = dao.forParent(parentKey).save(order);
        assertTrue(persisted.getId() > 0);
        assertTrue(outerTxn.isActive());
        assertFalse(outerSession.getTransaction().getStatus()
                .isOneOf(TransactionStatus.COMMITTED, TransactionStatus.ROLLED_BACK));
        assertReuse(targetSessionFactory, outerSession, outerTxn);

        // Second DAO call (get) still within same active transaction.
        Order loaded = dao.forParent(parentKey).get(persisted.getId());
        assertEquals(persisted.getId(), loaded.getId());
        assertTrue(outerTxn.isActive());
        assertSame(outerSession, outerSession.getSessionFactory().getCurrentSession());
        assertReuse(targetSessionFactory, outerSession, outerTxn);

        // Commit only at outer scope.
        outerTxn.commit();
        assertEquals(TransactionStatus.COMMITTED, outerTxn.getStatus());

        // Verify persistence using fresh session post commit.
        ManagedSessionContext.unbind(targetSessionFactory);
        outerSession.close();
        Session verificationSession = targetSessionFactory.openSession();
        ManagedSessionContext.bind(verificationSession);
        assertNotNull(verificationSession.get(Order.class, persisted.getId()));
        verificationSession.close();
        ManagedSessionContext.unbind(targetSessionFactory);
    }
}

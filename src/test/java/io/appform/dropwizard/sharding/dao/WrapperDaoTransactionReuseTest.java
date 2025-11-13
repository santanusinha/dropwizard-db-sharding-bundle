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

    @Test
    void testMultipleWritesReuseSameOuterTransaction() {
        String parentKey = "customer-multi-write";
        int shardId = dao.getShardCalculator().shardId(DBShardingBundleBase.DEFAULT_NAMESPACE, parentKey);
        SessionFactory sf = sessionFactories.get(shardId);
        Session outer = sf.openSession();
        ManagedSessionContext.bind(outer);
        Transaction outerTxn = outer.beginTransaction();
        assertTrue(outerTxn.isActive());
        assertReuse(sf, outer, outerTxn);

        // First save
        Order o1 = Order.builder().customerId(parentKey).build();
        o1.setItems(List.of(OrderItem.builder().order(o1).name("item-1").build()));
        Order p1 = dao.forParent(parentKey).save(o1);
        assertTrue(p1.getId() > 0);
        assertTrue(outerTxn.isActive());
        assertReuse(sf, outer, outerTxn);

        // Second save
        Order o2 = Order.builder().customerId(parentKey).build();
        o2.setItems(List.of(OrderItem.builder().order(o2).name("item-2").build()));
        Order p2 = dao.forParent(parentKey).save(o2);
        assertTrue(p2.getId() > 0);
        assertTrue(outerTxn.isActive());
        assertReuse(sf, outer, outerTxn);

        // Read both before commit
        Order r1 = dao.forParent(parentKey).get(p1.getId());
        Order r2 = dao.forParent(parentKey).get(p2.getId());
        assertNotNull(r1);
        assertNotNull(r2);
        assertTrue(outerTxn.isActive());
        assertReuse(sf, outer, outerTxn);

        outerTxn.commit();
        assertEquals(TransactionStatus.COMMITTED, outerTxn.getStatus());
        ManagedSessionContext.unbind(sf);
        outer.close();

        // Verify after commit
        Session ver = sf.openSession();
        ManagedSessionContext.bind(ver);
        assertNotNull(ver.get(Order.class, p1.getId()));
        assertNotNull(ver.get(Order.class, p2.getId()));
        ManagedSessionContext.unbind(sf);
        ver.close();
    }

    @Test
    void testOuterRollbackDiscardsInnerDaoWork() {
        // TC to verify that inner DAO calls within an outer transaction
        // that is rolled back do not persist any data.
        String parentKey = "customer-rollback";
        int shardId = dao.getShardCalculator().shardId(DBShardingBundleBase.DEFAULT_NAMESPACE, parentKey);
        SessionFactory sf = sessionFactories.get(shardId);
        Session outer = sf.openSession();
        ManagedSessionContext.bind(outer);
        Transaction outerTxn = outer.beginTransaction();
        assertTrue(outerTxn.isActive());
        assertReuse(sf, outer, outerTxn);

        // First inner write
        Order order = Order.builder().customerId(parentKey).build();
        order.setItems(List.of(OrderItem.builder().order(order).name("rollback-item-1").build()));
        Order persisted1 = dao.forParent(parentKey).save(order);
        assertTrue(persisted1.getId() > 0);
        assertReuse(sf, outer, outerTxn);

        // Second inner write
        Order order2 = Order.builder().customerId(parentKey).build();
        order2.setItems(List.of(OrderItem.builder().order(order2).name("rollback-item-2").build()));
        Order persisted2 = dao.forParent(parentKey).save(order2);
        assertTrue(persisted2.getId() > 0);
        assertReuse(sf, outer, outerTxn);

        // Inner reads (should see both inside same transaction/session)
        Order loaded1 = dao.forParent(parentKey).get(persisted1.getId());
        Order loaded2 = dao.forParent(parentKey).get(persisted2.getId());
        assertNotNull(loaded1);
        assertNotNull(loaded2);
        assertReuse(sf, outer, outerTxn);

        // Rollback instead of commit - both writes should vanish
        outerTxn.rollback();
        assertEquals(TransactionStatus.ROLLED_BACK, outerTxn.getStatus());
        ManagedSessionContext.unbind(sf);
        outer.close();

        // New session should not find either entity
        Session ver = sf.openSession();
        ManagedSessionContext.bind(ver);
        assertNull(ver.get(Order.class, persisted1.getId()));
        assertNull(ver.get(Order.class, persisted2.getId()));
        ManagedSessionContext.unbind(sf);
        ver.close();
    }
}

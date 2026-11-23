package io.appform.dropwizard.sharding.dao;

import io.appform.dropwizard.sharding.ShardInfoProvider;
import io.appform.dropwizard.sharding.TestAccess;
import io.appform.dropwizard.sharding.config.ShardingBundleOptions;
import io.appform.dropwizard.sharding.dao.testdata.entities.TestEntity;
import io.appform.dropwizard.sharding.observers.internal.TerminalTransactionObserver;
import io.appform.dropwizard.sharding.sharding.BalancedShardManager;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class UnknownTenantTest {

    private final List<SessionFactory> factories = new ArrayList<>();

    private SessionFactory buildSessionFactory(String dbName) {
        final var configuration = new org.hibernate.cfg.Configuration();
        configuration.setProperty("hibernate.dialect", "org.hibernate.dialect.H2Dialect");
        configuration.setProperty("hibernate.connection.driver_class", "org.h2.Driver");
        configuration.setProperty("hibernate.connection.url", "jdbc:h2:mem:" + dbName);
        configuration.setProperty("hibernate.hbm2ddl.auto", "create");
        configuration.setProperty("hibernate.current_session_context_class", "managed");
        configuration.addAnnotatedClass(TestEntity.class);
        final var serviceRegistry = new org.hibernate.boot.registry.StandardServiceRegistryBuilder()
                .applySettings(configuration.getProperties())
                .build();
        final var factory = configuration.buildSessionFactory(serviceRegistry);
        factories.add(factory);
        return factory;
    }

    @AfterEach
    void close() {
        factories.forEach(SessionFactory::close);
    }

    @Test
    void lookupDaoConstructionFailsForUnregisteredTenant() {
        final var ex = assertThrows(IllegalArgumentException.class, () -> new MultiTenantLookupDao<>(
                Map.of("T1", List.of(buildSessionFactory("unk_1"))), TestEntity.class,
                Map.of("T1", new ShardingBundleOptions()), Map.of("T1", new ShardInfoProvider("T1")),
                new TerminalTransactionObserver()));
        assertEquals("Unknown tenant: T1", ex.getMessage());
    }

    @Test
    void lookupDaoCallForUnknownTenantFails() {
        TestAccess.register("T1", new BalancedShardManager(1));
        final var dao = new MultiTenantLookupDao<>(
                Map.of("T1", List.of(buildSessionFactory("unk_2"))), TestEntity.class,
                Map.of("T1", new ShardingBundleOptions()), Map.of("T1", new ShardInfoProvider("T1")),
                new TerminalTransactionObserver());
        final var ex = assertThrows(IllegalArgumentException.class, () -> dao.get("T2", "k"));
        assertEquals("Unknown tenant: T2", ex.getMessage());
    }

    @Test
    void wrapperDaoConstructionFailsForUnregisteredTenant() {
        final var ex = assertThrows(IllegalArgumentException.class,
                () -> new WrapperDao<>("T1", List.of(buildSessionFactory("unk_3")),
                        io.appform.dropwizard.sharding.dao.testdata.OrderDao.class));
        assertEquals("Unknown tenant: T1", ex.getMessage());
    }
}

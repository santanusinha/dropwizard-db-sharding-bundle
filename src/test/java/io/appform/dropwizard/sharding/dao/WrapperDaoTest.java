/*
 * Copyright 2016 Santanu Sinha <santanu.sinha@gmail.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package io.appform.dropwizard.sharding.dao;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import io.appform.dropwizard.sharding.DBShardingBundleBase;
import io.appform.dropwizard.sharding.dao.testdata.OrderDao;
import io.appform.dropwizard.sharding.dao.testdata.entities.Order;
import io.appform.dropwizard.sharding.dao.testdata.entities.OrderItem;
import io.appform.dropwizard.sharding.sharding.BalancedShardManager;
import io.appform.dropwizard.sharding.sharding.ShardManager;
import io.appform.dropwizard.sharding.utils.ShardCalculatorRegistry;
import io.appform.dropwizard.sharding.utils.ShardCalculatorTestUtils;
import org.hibernate.SessionFactory;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class WrapperDaoTest {

    private List<SessionFactory> sessionFactories = Lists.newArrayList();
    private WrapperDao<Order, OrderDao> dao;
    private ShardCalculatorRegistry registry;

    private SessionFactory buildSessionFactory(String dbName) {
        Configuration configuration = new Configuration();
        configuration.setProperty("hibernate.dialect",
                "org.hibernate.dialect.H2Dialect");
        configuration.setProperty("hibernate.connection.driver_class",
                "org.h2.Driver");
        configuration.setProperty("hibernate.connection.url", "jdbc:h2:mem:" + dbName);
        configuration.setProperty("hibernate.hbm2ddl.auto", "create");
        configuration.setProperty("hibernate.current_session_context_class", "managed");
        configuration.addAnnotatedClass(Order.class);
        configuration.addAnnotatedClass(OrderItem.class);

        StandardServiceRegistry serviceRegistry
                = new StandardServiceRegistryBuilder().applySettings(
                configuration.getProperties()).build();
        return configuration.buildSessionFactory(serviceRegistry);
    }

    @BeforeEach
    public void before() {
        for (int i = 0; i < 2; i++) {
            sessionFactories.add(buildSessionFactory(String.format("db_%d", i)));
        }
        final ShardManager shardManager = new BalancedShardManager(sessionFactories.size());
        registry = ShardCalculatorTestUtils.registryFor(
                Map.of(DBShardingBundleBase.DEFAULT_NAMESPACE, shardManager));
        dao = new WrapperDao<>(DBShardingBundleBase.DEFAULT_NAMESPACE, sessionFactories, OrderDao.class, registry);

    }

    @AfterEach
    public void after() {
        sessionFactories.forEach(SessionFactory::close);
    }

    @Test
    public void testDao() {

        final String customer = "customer1";

        Order order = Order.builder()
                .customerId(customer)
                .build();

        OrderItem itemA = OrderItem.builder()
                .order(order)
                .name("Item A")
                .build();
        OrderItem itemB = OrderItem.builder()
                .order(order)
                .name("Item B")
                .build();

        order.setItems(ImmutableList.of(itemA, itemB));

        Order saveResult = dao.forParent(customer).save(order);

        long saveId = saveResult.getId();

        Order result = dao.forParent(customer).get(saveId);

        assertEquals(saveResult.getId(), result.getId());
        assertEquals(saveResult.getId(), result.getId());
    }

    @Test
    void testRegistryClearIsObservedAfterInitialLookup() {
        dao.forParent("customer-before-clear");

        registry.clear();

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> dao.forParent("customer-after-clear"));
        assertEquals(
                "ShardCalculator has not been registered for tenant: default",
                error.getMessage());
    }

    @Test
    void testConstructorRejectsMissingNamespace() {
        ShardCalculatorRegistry emptyRegistry = new ShardCalculatorRegistry();

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> new WrapperDao<>(
                        DBShardingBundleBase.DEFAULT_NAMESPACE,
                        sessionFactories,
                        OrderDao.class,
                        emptyRegistry));
        assertEquals(
                "ShardCalculator has not been registered for tenant: default",
                error.getMessage());
    }

    @Test
    void testCalculatedShardMustMatchSessionFactories() {
        ShardCalculatorRegistry mismatchedRegistry = ShardCalculatorTestUtils.registryFor(
                Map.of(DBShardingBundleBase.DEFAULT_NAMESPACE, new BalancedShardManager(4)));
        String parentKey = parentKeyForShard(mismatchedRegistry, 2);
        WrapperDao<Order, OrderDao> mismatchedDao = new WrapperDao<>(
                DBShardingBundleBase.DEFAULT_NAMESPACE,
                sessionFactories,
                OrderDao.class,
                mismatchedRegistry);

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> mismatchedDao.forParent(parentKey));
        assertEquals(
                "Calculated shard 2 for tenant default is outside configured session factory range [0, 1]",
                error.getMessage());
    }

    private String parentKeyForShard(ShardCalculatorRegistry targetRegistry, int targetShard) {
        for (int i = 0; i < 1000; i++) {
            String parentKey = "customer-" + i;
            if (targetRegistry.get(DBShardingBundleBase.DEFAULT_NAMESPACE).shardId(parentKey)
                    == targetShard) {
                return parentKey;
            }
        }
        throw new AssertionError("Unable to find parent key for shard " + targetShard);
    }
}
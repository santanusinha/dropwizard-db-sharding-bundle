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
import io.appform.dropwizard.sharding.dao.testdata.OrderDao;
import io.appform.dropwizard.sharding.dao.testdata.entities.Order;
import io.appform.dropwizard.sharding.dao.testdata.entities.OrderItem;
import io.appform.dropwizard.sharding.sharding.BalancedShardManager;
import io.appform.dropwizard.sharding.sharding.ShardManager;
import io.appform.dropwizard.sharding.sharding.impl.ConsistentHashBucketIdExtractor;
import io.appform.dropwizard.sharding.utils.ShardCalculator;
import org.hibernate.SessionFactory;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class WrapperDaoTest {

    private List<SessionFactory> sessionFactories = Lists.newArrayList();
    private WrapperDao<Order, OrderDao> dao;

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
        dao = new WrapperDao<>(sessionFactories, OrderDao.class,
                new ShardCalculator<>(shardManager, new ConsistentHashBucketIdExtractor<>(shardManager)));

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
}
/*
 * Copyright 2019 Santanu Sinha <santanu.sinha@gmail.com>
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

package io.appform.dropwizard.sharding;

import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.health.HealthCheckRegistry;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Maps;
import io.appform.dropwizard.sharding.config.ShardedHibernateFactory;
import io.appform.dropwizard.sharding.dao.RelationalDao;
import io.appform.dropwizard.sharding.dao.WrapperDao;
import io.appform.dropwizard.sharding.dao.testdata.OrderDao;
import io.appform.dropwizard.sharding.dao.testdata.entities.Order;
import io.appform.dropwizard.sharding.dao.testdata.entities.OrderItem;
import io.dropwizard.Configuration;
import io.dropwizard.db.DataSourceFactory;
import io.dropwizard.jersey.DropwizardResourceConfig;
import io.dropwizard.jersey.setup.JerseyEnvironment;
import io.dropwizard.lifecycle.setup.LifecycleEnvironment;
import io.dropwizard.setup.AdminEnvironment;
import io.dropwizard.setup.Bootstrap;
import io.dropwizard.setup.Environment;
import lombok.Getter;
import org.hibernate.criterion.DetachedCriteria;
import org.hibernate.criterion.Restrictions;
import org.junit.Before;
import org.junit.Test;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Top level test. Saves an order using custom dao to a shard belonging to a particular customer.
 * Core systems are not mocked. Uses H2 for testing.
 */
public abstract class DBShardingBundleTestBase {
    protected static class TestConfig extends Configuration {
        @Getter
        private ShardedHibernateFactory shards = new ShardedHibernateFactory();
    }

    protected final TestConfig testConfig = new TestConfig();
    protected final HealthCheckRegistry healthChecks = mock(HealthCheckRegistry.class);
    protected final JerseyEnvironment jerseyEnvironment = mock(JerseyEnvironment.class);
    protected final LifecycleEnvironment lifecycleEnvironment = mock(LifecycleEnvironment.class);
    protected final Environment environment = mock(Environment.class);
    protected final AdminEnvironment adminEnvironment = mock(AdminEnvironment.class);
    protected final Bootstrap<?> bootstrap = mock(Bootstrap.class);


    protected abstract DBShardingBundleBase<TestConfig> getBundle();

    private DataSourceFactory createConfig(String dbName) {
        Map<String, String> properties = Maps.newHashMap();
        properties.put("hibernate.dialect", "org.hibernate.dialect.H2Dialect");
        properties.put("hibernate.hbm2ddl.auto", "create");

        DataSourceFactory shard = new DataSourceFactory();
        shard.setDriverClass("org.h2.Driver");
        shard.setUrl("jdbc:h2:mem:" + dbName);
        shard.setValidationQuery("select 1");
        shard.setProperties(properties);

        return shard;
    }

    @Before
    public void setup() {
        testConfig.shards.setShards(ImmutableList.of(createConfig("1"), createConfig("2")));
        when(jerseyEnvironment.getResourceConfig()).thenReturn(new DropwizardResourceConfig());
        when(environment.jersey()).thenReturn(jerseyEnvironment);
        when(environment.lifecycle()).thenReturn(lifecycleEnvironment);
        when(environment.healthChecks()).thenReturn(healthChecks);
        when(environment.admin()).thenReturn(adminEnvironment);
        when(environment.metrics()).thenReturn(new MetricRegistry());
        when(bootstrap.getHealthCheckRegistry()).thenReturn(mock(HealthCheckRegistry.class));
    }

    @Test
    public void testBundle() throws Exception {
        DBShardingBundleBase<TestConfig> bundle = getBundle();
        bundle.initialize(bootstrap);
        bundle.initBundles(bootstrap);
        bundle.runBundles(testConfig, environment);
        bundle.run(testConfig, environment);

        WrapperDao<Order, OrderDao> dao = bundle.createWrapperDao(OrderDao.class);

        RelationalDao<Order> rDao = bundle.createRelatedObjectDao(Order.class);

        RelationalDao<OrderItem> orderItemDao = bundle.createRelatedObjectDao(OrderItem.class);


        final String customer = "customer1";

        Order order = Order.builder()
                .customerId(customer)
                .orderId("OD00001")
                .amount(100)
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

        Optional<Order> newOrder = rDao.save("customer1", order);

        assertTrue(newOrder.isPresent());

        long generatedId = newOrder.get().getId();

        Optional<Order> checkOrder = rDao.get("customer1", generatedId);

        assertEquals(100, checkOrder.get().getAmount());

        rDao.update("customer1", saveId, foundOrder -> {
            foundOrder.setAmount(200);
            return foundOrder;
        });

        Optional<Order> modifiedOrder = rDao.get("customer1", saveId);
        assertEquals(200, modifiedOrder.get().getAmount());

        assertTrue(checkOrder.isPresent());

        assertEquals(newOrder.get().getId(), checkOrder.get().getId());

        Map<String, Object> blah = Maps.newHashMap();

        rDao.get("customer1", generatedId, foundOrder -> {
            if(null == foundOrder) {
                return Collections.emptyList();
            }
            List<OrderItem> itemList = foundOrder.getItems();
            blah.put("count", itemList.size());
            return itemList;
        });

        assertEquals(2, blah.get("count"));

        List<OrderItem> orderItems = orderItemDao.select("customer1",
                DetachedCriteria.forClass(OrderItem.class)
                        .createAlias("order", "o")
                        .add(Restrictions.eq("o.orderId", "OD00001")), 0, 10);
        assertEquals(2, orderItems.size());
        orderItemDao.update("customer1",
                DetachedCriteria.forClass(OrderItem.class)
                        .createAlias("order", "o")
                        .add(Restrictions.eq("o.orderId", "OD00001")),
                item -> OrderItem.builder()
                        .id(item.getId())
                        .order(item.getOrder())
                        .name("Item AA")
                        .build());

        orderItems = orderItemDao.select("customer1",
                DetachedCriteria.forClass(OrderItem.class)
                        .createAlias("order", "o")
                        .add(Restrictions.eq("o.orderId", "OD00001")), 0, 10);
        assertEquals(2, orderItems.size());
        assertEquals("Item AA", orderItems.get(0).getName());
    }

    @Test
    public void testBundleWithShardBlacklisted() throws Exception {
        DBShardingBundleBase<TestConfig> bundle = getBundle();
        bundle.initialize(bootstrap);
        bundle.initBundles(bootstrap);
        bundle.runBundles(testConfig, environment);
        bundle.run(testConfig, environment);
        bundle.getShardManager().blacklistShard(1);

        assertTrue(bundle.healthStatus()
                .values()
                .stream()
                .allMatch(status -> status));
    }

}
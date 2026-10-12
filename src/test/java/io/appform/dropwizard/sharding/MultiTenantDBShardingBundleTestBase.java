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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.common.collect.Maps;
import io.appform.dropwizard.sharding.dao.MultiTenantRelationalDao;
import io.appform.dropwizard.sharding.dao.WrapperDao;
import io.appform.dropwizard.sharding.dao.interceptors.TimerObserver;
import io.appform.dropwizard.sharding.dao.listeners.LoggingListener;
import io.appform.dropwizard.sharding.dao.testdata.OrderDao;
import io.appform.dropwizard.sharding.dao.testdata.entities.Order;
import io.appform.dropwizard.sharding.dao.testdata.entities.OrderItem;
import io.appform.dropwizard.sharding.dao.testdata.pending.PendingRegistrationTestEntity;
import io.appform.dropwizard.sharding.dao.testdata.pending.PendingRegistrationTestEntityWithAIId;
import jakarta.persistence.criteria.Join;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import lombok.SneakyThrows;
import org.apache.commons.lang3.reflect.FieldUtils;
import org.junit.jupiter.api.Assertions;


/**
 * Top level test. Saves an order using custom dao to a shard belonging to a particular customer.
 * Core systems are not mocked. Uses H2 for testing.
 */
public abstract class MultiTenantDBShardingBundleTestBase extends MultiTenantBundleBasedTestBase {

    @Test
    public void testBundle() throws Exception {
        MultiTenantDBShardingBundleBase<TestConfig> bundle = getBundle();
        bundle.initialize(bootstrap);
        bundle.run(testConfig, environment);
        bundle.registerObserver(new TimerObserver());
        bundle.registerListener(new LoggingListener());
        WrapperDao<Order, OrderDao> tenant1Dao = bundle.createWrapperDao("TENANT1", OrderDao.class);
        MultiTenantRelationalDao<Order> tenant1RelDao = bundle.createRelatedObjectDao(Order.class);
        MultiTenantRelationalDao<OrderItem> tenant1OrderItemDao = bundle.createRelatedObjectDao(OrderItem.class);

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
        order.setItems(List.of(itemA, itemB));
        Order saveResult = tenant1Dao.forParent(customer).save(order);
        long saveId = saveResult.getId();
        Order result = tenant1Dao.forParent(customer).get(saveId);
        assertEquals(saveResult.getId(), result.getId());
        assertEquals(saveResult.getId(), result.getId());
        Optional<Order> newOrder = tenant1RelDao.save("TENANT1", "customer1", order);
        assertTrue(newOrder.isPresent());
        long generatedId = newOrder.get().getId();
        Optional<Order> checkOrder = tenant1RelDao.get("TENANT1","customer1", generatedId);
        assertEquals(100, checkOrder.get().getAmount());
        tenant1RelDao.update("TENANT1","customer1", saveId, foundOrder -> {
            foundOrder.setAmount(200);
            return foundOrder;
        });
        Optional<Order> modifiedOrder = tenant1RelDao.get("TENANT1","customer1", saveId);
        assertEquals(200, modifiedOrder.get().getAmount());
        assertTrue(checkOrder.isPresent());
        assertEquals(newOrder.get().getId(), checkOrder.get().getId());
        Map<String, Object> blah = Maps.newHashMap();
        tenant1RelDao.get("TENANT1","customer1", generatedId, foundOrder -> {
            if (null == foundOrder) {
                return Collections.emptyList();
            }
            List<OrderItem> itemList = foundOrder.getItems();
            blah.put("count", itemList.size());
            return itemList;
        });
        assertEquals(2, blah.get("count"));
        List<OrderItem> orderItems = tenant1OrderItemDao.select("TENANT1","customer1",
                (root, query, cb) -> {
                    Join<Object, Object> orderJoin = root.join("order");
                    query.where(cb.equal(orderJoin.get("orderId"), "OD00001"));
                }, 0, 10);
        assertEquals(2, orderItems.size());
        tenant1OrderItemDao.update("TENANT1","customer1",
                (root, query, cb) -> {
                    Join<Object, Object> orderJoin = root.join("order");
                    query.where(cb.equal(orderJoin.get("orderId"), "OD00001"));
                },
                item -> OrderItem.builder()
                        .id(item.getId())
                        .order(item.getOrder())
                        .name("Item AA")
                        .build());
        orderItems = tenant1OrderItemDao.select("TENANT1","customer1",
                (root, query, cb) -> {
                    Join<Object, Object> orderJoin = root.join("order");
                    query.where(cb.equal(orderJoin.get("orderId"), "OD00001"));
                }, 0, 10);
        assertEquals(2, orderItems.size());
        assertEquals("Item AA", orderItems.get(0).getName());
    }

    @Test
    public void testBundleWithShardBlacklisted() throws Exception {
        MultiTenantDBShardingBundleBase<TestConfig> bundle = getBundle();
        bundle.initialize(bootstrap);
        bundle.run(testConfig, environment);
        //one for each tenant
        assertEquals(2, bundle.healthStatus().size());
        bundle.getShardManagers().get("TENANT1").blacklistShard(1);
        //no healthchecks for blacklisting aware bundle
        assertEquals(0, bundle.healthStatus().get("TENANT1").size());
    }

    @Test
    public void testRegisterEntityClassesBeforeRun() {
        MultiTenantDBShardingBundleBase<TestConfig> bundle = getBundle();
        bundle.initialize(bootstrap);
        bundle.registerEntities(PendingRegistrationTestEntity.class, PendingRegistrationTestEntityWithAIId.class);
        bundle.run(testConfig, environment);
        assertTrue(bundle.getInitialisedEntities()
                .contains(PendingRegistrationTestEntity.class));
        assertTrue(bundle.getInitialisedEntities()
                .contains(PendingRegistrationTestEntityWithAIId.class));
    }

    @Test
    @SneakyThrows
    @SuppressWarnings("unchecked")
    public void testRegisterAlreadyRegisteredEntityClassesBeforeRun() {
        MultiTenantDBShardingBundleBase<TestConfig> bundle = getBundle();
        bundle.initialize(bootstrap);
        final var initializedEntities = (List<Class<?>>) FieldUtils.readField(bundle, "initialisedEntities", true);
        final var alreadyRegisteredEntityClasses = initializedEntities.toArray(new Class<?>[0]);
        Assertions.assertDoesNotThrow(() -> bundle.registerEntities(alreadyRegisteredEntityClasses));
        Assertions.assertDoesNotThrow(() -> bundle.run(testConfig, environment));
    }

    @Test
    public void testRegisterEntityPackagesBeforeRun() {
        MultiTenantDBShardingBundleBase<TestConfig> bundle = getBundle();
        bundle.initialize(bootstrap);
        bundle.registerEntities(List.of("io.appform.dropwizard.sharding.dao.testdata.pending"));
        bundle.run(testConfig, environment);
        assertTrue(bundle.getInitialisedEntities()
                .contains(PendingRegistrationTestEntity.class));
        assertTrue(bundle.getInitialisedEntities()
                .contains(PendingRegistrationTestEntityWithAIId.class));
    }

    @Test
    @SneakyThrows
    @SuppressWarnings("unchecked")
    public void testRegisterAlreadyRegisteredEntityPackagesBeforeRun() {
        MultiTenantDBShardingBundleBase<TestConfig> bundle = getBundle();
        bundle.initialize(bootstrap);
        final var initializedEntities = (List<Class<?>>) FieldUtils.readField(bundle, "initialisedEntities", true);
        final var alreadyRegisteredEntityPackages = initializedEntities.stream()
                .map(Class::getPackageName)
                .collect(Collectors.toList());
        Assertions.assertDoesNotThrow(() -> bundle.registerEntities(alreadyRegisteredEntityPackages));
        Assertions.assertDoesNotThrow(() -> bundle.run(testConfig, environment));
    }

    @Test
    public void testRegisterEntityClassesFailsAfterRun() {
        MultiTenantDBShardingBundleBase<TestConfig> bundle = getBundle();
        bundle.initialize(bootstrap);
        bundle.run(testConfig, environment);
        final var unsupportedOperationException = Assertions.assertThrows(UnsupportedOperationException.class, () -> {
            bundle.registerEntities(PendingRegistrationTestEntity.class, PendingRegistrationTestEntityWithAIId.class);
        });
        assertEquals("Entity registration is not supported after run method execution.",
                unsupportedOperationException.getMessage());
        assertFalse(bundle.getInitialisedEntities()
                .contains(PendingRegistrationTestEntity.class));
        assertFalse(bundle.getInitialisedEntities()
                .contains(PendingRegistrationTestEntityWithAIId.class));
    }

    @Test
    public void testRegisterEntityPackagesFailsAfterRun() {
        MultiTenantDBShardingBundleBase<TestConfig> bundle = getBundle();
        bundle.initialize(bootstrap);
        bundle.run(testConfig, environment);
        final var packagesToRegister = List.of("io.appform.dropwizard.sharding.dao.testdata.pending");
        final var unsupportedOperationException = Assertions.assertThrows(UnsupportedOperationException.class, () -> {
            bundle.registerEntities(packagesToRegister);
        });
        assertEquals("Entity registration is not supported after run method execution.",
                unsupportedOperationException.getMessage());
        assertFalse(bundle.getInitialisedEntities()
                .contains(PendingRegistrationTestEntity.class));
        assertFalse(bundle.getInitialisedEntities()
                .contains(PendingRegistrationTestEntityWithAIId.class));
    }
}
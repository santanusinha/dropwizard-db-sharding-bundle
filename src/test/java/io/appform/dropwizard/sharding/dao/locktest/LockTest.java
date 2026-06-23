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

package io.appform.dropwizard.sharding.dao.locktest;

import com.google.common.base.Strings;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import io.appform.dropwizard.sharding.DBShardingBundleBase;
import io.appform.dropwizard.sharding.ShardInfoProvider;
import io.appform.dropwizard.sharding.config.ShardingBundleOptions;
import io.appform.dropwizard.sharding.dao.LockedContext;
import io.appform.dropwizard.sharding.dao.LookupDao;
import io.appform.dropwizard.sharding.dao.MultiTenantLookupDao;
import io.appform.dropwizard.sharding.dao.MultiTenantRelationalDao;
import io.appform.dropwizard.sharding.dao.RelationalDao;
import io.appform.dropwizard.sharding.dao.UpdateOperationMeta;
import io.appform.dropwizard.sharding.dao.interceptors.DaoClassLocalObserver;
import io.appform.dropwizard.sharding.observers.internal.TerminalTransactionObserver;
import io.appform.dropwizard.sharding.query.QuerySpec;
import java.util.function.Function;
import io.appform.dropwizard.sharding.sharding.BalancedShardManager;
import io.appform.dropwizard.sharding.sharding.ShardManager;
import lombok.SneakyThrows;
import lombok.val;
import org.hibernate.SessionFactory;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.cfg.Configuration;
import org.hibernate.criterion.DetachedCriteria;
import org.hibernate.criterion.Order;
import org.hibernate.criterion.Restrictions;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Test locking behavior
 */
public class LockTest {
    private List<SessionFactory> sessionFactories = Lists.newArrayList();

    private LookupDao<SomeLookupObject> lookupDao;
    private RelationalDao<SomeOtherObject> relationDao;

    private SessionFactory buildSessionFactory(String dbName) {
        Configuration configuration = new Configuration();
        configuration.setProperty("hibernate.dialect", "org.hibernate.dialect.H2Dialect");
        configuration.setProperty("hibernate.connection.driver_class", "org.h2.Driver");
        configuration.setProperty("hibernate.connection.url", "jdbc:h2:mem:" + dbName);
        configuration.setProperty("hibernate.hbm2ddl.auto", "create");
        configuration.setProperty("hibernate.current_session_context_class", "managed");
        configuration.setProperty("hibernate.show_sql", "true");
//        configuration.setProperty("hibernate.format_sql", "true");

        configuration.addAnnotatedClass(SomeLookupObject.class);
        configuration.addAnnotatedClass(SomeOtherObject.class);

        StandardServiceRegistry serviceRegistry
                = new StandardServiceRegistryBuilder().applySettings(
                configuration.getProperties()).build();
        return configuration.buildSessionFactory(serviceRegistry);
    }

    @BeforeEach
    public void before() {
        for (int i = 0; i < 2; i++) {
            SessionFactory sessionFactory = buildSessionFactory(String.format("db_%d", i));
            sessionFactories.add(sessionFactory);
        }
        final ShardManager shardManager = new BalancedShardManager(sessionFactories.size());
        final ShardingBundleOptions shardingOptions = ShardingBundleOptions.builder().build();
        final ShardInfoProvider shardInfoProvider = new ShardInfoProvider("default");
        lookupDao = new LookupDao<>(DBShardingBundleBase.DEFAULT_NAMESPACE,
                new MultiTenantLookupDao<>(Map.of(DBShardingBundleBase.DEFAULT_NAMESPACE, sessionFactories),
                        SomeLookupObject.class, Map.of(DBShardingBundleBase.DEFAULT_NAMESPACE, shardManager),
                        Map.of(DBShardingBundleBase.DEFAULT_NAMESPACE, shardingOptions),
                        Map.of(DBShardingBundleBase.DEFAULT_NAMESPACE, shardInfoProvider),
                        new DaoClassLocalObserver(new TerminalTransactionObserver())));
        relationDao = new RelationalDao<>(DBShardingBundleBase.DEFAULT_NAMESPACE,
                new MultiTenantRelationalDao<>(Map.of(DBShardingBundleBase.DEFAULT_NAMESPACE, sessionFactories),
                        SomeOtherObject.class, Map.of(DBShardingBundleBase.DEFAULT_NAMESPACE, shardManager),
                        Map.of(DBShardingBundleBase.DEFAULT_NAMESPACE, shardingOptions),
                        Map.of(DBShardingBundleBase.DEFAULT_NAMESPACE, shardInfoProvider),
                        new DaoClassLocalObserver(new TerminalTransactionObserver())));
    }

    @Test
    public void testLocking() throws Exception {
        SomeLookupObject p1 = SomeLookupObject.builder()
                .myId("0")
                .name("Parent 1")
                .build();
        lookupDao.save(p1);
        saveEntity(lookupDao.lockAndGetExecutor("0"));

        assertEquals(p1.getMyId(), lookupDao.get("0").get().getMyId());
        assertEquals("Changed", lookupDao.get("0").get().getName());
        assertEquals(6, relationDao.select("0", DetachedCriteria.forClass(SomeOtherObject.class), 0, 10).size());
        assertEquals("Hello", relationDao.get("0", 1L).get().getValue());
    }

    @Test
    public void testLockingFail() throws Exception {
        SomeLookupObject p1 = SomeLookupObject.builder()
                .myId("0")
                .build();
        lookupDao.save(p1);
        assertThrows(IllegalArgumentException.class,
                () -> lookupDao.lockAndGetExecutor("0")
                        .filter(parent -> !Strings.isNullOrEmpty(parent.getName()))
                        .save(relationDao, parent -> {
                            SomeOtherObject result = SomeOtherObject.builder()
                                    .myId(parent.getMyId())
                                    .value("Hello")
                                    .build();
                            parent.setName("Changed");
                            return result;
                        })
                        .mutate(parent -> parent.setName("Changed"))
                        .execute());

    }

    @Test
    public void testPersist() throws Exception {
        SomeLookupObject p1 = SomeLookupObject.builder()
                .myId("0")
                .name("Parent 1")
                .build();

        lookupDao.saveAndGetExecutor(p1)
                .filter(parent -> !Strings.isNullOrEmpty(parent.getName()))
                .save(relationDao, parent -> SomeOtherObject.builder()
                        .myId(parent.getMyId())
                        .value("Hello")
                        .build())
                .mutate(parent -> parent.setName("Changed"))
                .execute();

        assertEquals(p1.getMyId(), lookupDao.get("0").get().getMyId());
        assertEquals("Changed", lookupDao.get("0").get().getName());
    }

    @Test
    public void testUpdateById() throws Exception {
        SomeLookupObject p1 = SomeLookupObject.builder()
                .myId("0")
                .name("Parent 1")
                .build();

        SomeOtherObject c1 = relationDao.save(p1.getMyId(), SomeOtherObject.builder()
                .myId(p1.getMyId())
                .value("Hello")
                .build()).get();


        lookupDao.saveAndGetExecutor(p1)
                .filter(parent -> !Strings.isNullOrEmpty(parent.getName()))
                .update(relationDao, c1.getId(), child -> {
                    child.setValue("Hello Changed");
                    return child;
                })
                .mutate(parent -> parent.setName("Changed"))
                .execute();

        assertEquals(p1.getMyId(), lookupDao.get("0").get().getMyId());
        assertEquals("Changed", lookupDao.get("0").get().getName());
        assertEquals("Hello Changed", relationDao.get("0", 1L).get().getValue());
    }

    @Test
    public void testUpdateByEntity() throws Exception {
        SomeLookupObject p1 = SomeLookupObject.builder()
                .myId("0")
                .name("Parent 1")
                .build();

        SomeOtherObject c1 = relationDao.save(p1.getMyId(), SomeOtherObject.builder()
                .myId(p1.getMyId())
                .value("Hello")
                .build()).get();


        lookupDao.saveAndGetExecutor(p1)
                .filter(parent -> !Strings.isNullOrEmpty(parent.getName()))
                .save(relationDao, c1, child -> {
                    child.setValue("Hello Changed");
                    return child;
                })
                .mutate(parent -> parent.setName("Changed"))
                .execute();

        assertEquals(p1.getMyId(), lookupDao.get("0").get().getMyId());
        assertEquals("Changed", lookupDao.get("0").get().getName());
        assertEquals("Hello Changed", relationDao.get("0", 1L).get().getValue());
    }

    @Test
    public void testPersist_alreadyExistingDifferent() throws Exception {
        SomeLookupObject p1 = SomeLookupObject.builder()
                .myId("0")
                .name("Parent 1")
                .build();

        lookupDao.save(p1);

        SomeLookupObject p2 = SomeLookupObject.builder()
                .myId("0")
                .name("Changed")
                .build();

        assertThrows(ConstraintViolationException.class, () -> lookupDao.saveAndGetExecutor(p2)
                .filter(parent -> !Strings.isNullOrEmpty(parent.getName()))
                .save(relationDao, parent -> SomeOtherObject.builder()
                        .myId(parent.getMyId())
                        .value("Hello")
                        .build())
                .execute());
    }

    @Test
    public void testPersist_alreadyExistingSame() throws Exception {
        SomeLookupObject p1 = SomeLookupObject.builder()
                .myId("0")
                .name("Parent 1")
                .build();

        lookupDao.save(p1);

        lookupDao.saveAndGetExecutor(p1)
                .filter(parent -> !Strings.isNullOrEmpty(parent.getName()))
                .save(relationDao, parent -> SomeOtherObject.builder()
                        .myId(parent.getMyId())
                        .value("Hello")
                        .build())
                .mutate(parent -> parent.setName("Changed"))
                .execute();

        assertEquals(p1.getMyId(), lookupDao.get("0").get().getMyId());
        assertEquals("Changed", lookupDao.get("0").get().getName());
    }

    @Test
    public void testCreateOrUpdate() throws Exception {
        final String parentId = "1";
        final SomeLookupObject parent = SomeLookupObject.builder()
                .myId(parentId)
                .name("Parent 1")
                .build();
        lookupDao.save(parent);

        final SomeOtherObject child = relationDao.save(parent.getMyId(), SomeOtherObject.builder()
                .myId(parent.getMyId())
                .value("Hello")
                .build()).get();


        //test existing entity update
        final String childModifiedValue = "Hello Modified";
        final String parentModifiedValue = "Changed";
        final DetachedCriteria updateCriteria = DetachedCriteria.forClass(SomeOtherObject.class)
                .add(Restrictions.eq("myId", parent.getMyId()));

        lookupDao.lockAndGetExecutor(parent.getMyId())
                .createOrUpdate(relationDao, updateCriteria, childObj -> {
                    childObj.setValue(childModifiedValue);
                    return childObj;
                }, () -> {
                    fail("New Entity is getting created. It should have been updated.");
                    return SomeOtherObject.builder()
                            .myId(parentId)
                            .value("test")
                            .build();
                })
                .mutate(parentObj -> parentObj.setName(parentModifiedValue))
                .execute();

        assertEquals(childModifiedValue, relationDao.get(parent.getMyId(), child.getId()).get().getValue());
        assertEquals(parentModifiedValue, lookupDao.get(parentId).get().getName());

        //test non existing entity creation
        final String newChildValue = "Newly created child";
        final String newParentValue = "New parent Value";
        final DetachedCriteria creationCriteria = DetachedCriteria.forClass(SomeOtherObject.class)
                .add(Restrictions.eq("value", newChildValue));

        lookupDao.lockAndGetExecutor(parent.getMyId())
                .createOrUpdate(relationDao, creationCriteria, childObj -> {
                    assertNotEquals(null, childObj);
                    fail("New Entity is getting updated. It should have been created.");

                    childObj.setValue("abcd");
                    return childObj;

                }, () -> SomeOtherObject.builder()
                        .myId(parentId)
                        .value(newChildValue)
                        .build())
                .mutate(parentObj -> parentObj.setName(newParentValue))
                .execute();

        final SomeOtherObject savedChild = relationDao.select(parent.getMyId(), creationCriteria, 0, 1)
                .stream()
                .findFirst()
                .get();
        assertEquals(newChildValue, savedChild.getValue());
        assertNotEquals(child.getId(), savedChild.getId());
        assertEquals(newParentValue, lookupDao.get(parentId).get().getName());
    }

    @Test
    public void testUpdateUsingQuery() throws Exception {
        val parentId = "1";
        val parent = SomeLookupObject.builder()
                .myId(parentId)
                .name("Parent 1")
                .build();
        lookupDao.save(parent);

        val child = relationDao.save(parent.getMyId(), SomeOtherObject.builder()
                .myId(parent.getMyId())
                .value("Hello")
                .build()).get();

        val childModifiedValue = "Hello Modified";

        lookupDao.lockAndGetExecutor(parent.getMyId())
                .updateUsingQuery(relationDao,
                        UpdateOperationMeta.builder()
                                .queryName("testUpdateUsingMyId")
                                .params(ImmutableMap.of("value",
                                        childModifiedValue,
                                        "myId",
                                        parent.getMyId()))
                                .build())
                .execute();

        val updatedChild = relationDao.get(parent.getMyId(), child.getId()).orElse(null);
        assertNotNull(updatedChild);
        assertEquals(childModifiedValue, updatedChild.getValue());
    }


    @Test
    public void testUpdateWithScroll() throws Exception {
        final String parent1Id = "0";
        final SomeLookupObject parent1 = SomeLookupObject.builder()
                .myId(parent1Id)
                .name("Parent 1")
                .build();

        final SomeOtherObject child1 = relationDao.save(parent1.getMyId(), SomeOtherObject.builder()
                .myId(parent1.getMyId())
                .value("Hello1")
                .build()).get();

        final SomeOtherObject child2 = relationDao.save(parent1.getMyId(), SomeOtherObject.builder()
                .myId(parent1.getMyId())
                .value("Hello2")
                .build()).get();

        final String parent2Id = "1";
        final SomeLookupObject parent2 = SomeLookupObject.builder()
                .myId(parent2Id)
                .name("Parent 2")
                .build();

        final SomeOtherObject child3 = relationDao.save(parent2.getMyId(), SomeOtherObject.builder()
                .myId(parent2.getMyId())
                .value("Hello3")
                .build()).get();

        lookupDao.save(parent1);
        lookupDao.save(parent2);

        //test full update
        final DetachedCriteria allSelectCriteria = DetachedCriteria.forClass(SomeOtherObject.class)
                .add(Restrictions.eq("myId", parent1.getMyId()))
                .addOrder(Order.asc("id"));

        final String childModifiedValue = "Hello Modified";
        final String parentModifiedValue = "Parent Changed";

        lookupDao.lockAndGetExecutor(parent1.getMyId())
                .update(relationDao, allSelectCriteria, entityObj -> {
                    entityObj.setValue(childModifiedValue);
                    return entityObj;
                }, () -> true)
                .mutate(parent -> parent.setName(parentModifiedValue))
                .execute();

        assertEquals(childModifiedValue, relationDao.get(parent1.getMyId(), child1.getId()).get().getValue());
        assertEquals(childModifiedValue, relationDao.get(parent1.getMyId(), child2.getId()).get().getValue());
        assertEquals(parentModifiedValue, lookupDao.get(parent1Id).get().getName());

        assertEquals("Hello3", relationDao.get(parent2.getMyId(), child3.getId()).get().getValue());
        assertEquals("Parent 2", lookupDao.get(parent2Id).get().getName());

        final boolean[] shouldUpdateNext = new boolean[1];
        shouldUpdateNext[0] = true;

        //test partial update
        final String childModifiedValue2 = "Hello Modified Partial";
        final String parentModifiedValue2 = "Parent Changed Partial";
        lookupDao.lockAndGetExecutor(parent1.getMyId())
                .update(relationDao, allSelectCriteria, entityObj -> {
                    entityObj.setValue(childModifiedValue2);

                    if (entityObj.getId() == child1.getId()) {
                        shouldUpdateNext[0] = false;
                    }

                    return entityObj;
                }, () -> shouldUpdateNext[0])
                .mutate(parent -> parent.setName(parentModifiedValue2))
                .execute();

        assertEquals(childModifiedValue2, relationDao.get(parent1Id, child1.getId()).get().getValue());
        assertEquals(childModifiedValue, relationDao.get(parent1Id, child2.getId()).get().getValue());
        assertEquals(parentModifiedValue2, lookupDao.get(parent1Id).get().getName());

        assertEquals("Hello3", relationDao.get(parent2.getMyId(), child3.getId()).get().getValue());
        assertEquals("Parent 2", lookupDao.get(parent2Id).get().getName());
    }

    @Test
    @SneakyThrows
    public void testReadMultiChild() {
        SomeLookupObject p1 = SomeLookupObject.builder()
                .myId("0")
                .name("Parent 1")
                .build();
        saveEntity(lookupDao.saveAndGetExecutor(p1));

        final DetachedCriteria allSelectCriteria = DetachedCriteria.forClass(SomeOtherObject.class)
                .add(Restrictions.eq("myId", p1.getMyId()))
                .addOrder(Order.asc("id"));
        val testExecuted = new AtomicBoolean();
        val res = lookupDao.readOnlyExecutor(p1.getMyId())
                .readAugmentParent(relationDao, allSelectCriteria, 0, Integer.MAX_VALUE, (parent, children) -> {
                    assertNull(parent.getChildren());
                    assertEquals(6, children.size());
                    assertNotNull(parent);
                    testExecuted.set(true);
                    parent.setChildren(children);
                })
                .execute();

        assertTrue(res.isPresent());
        assertEquals(6, res.get().getChildren().size());
        assertEquals(6, res.get().getChildren().size());
        assertTrue(testExecuted.get());
    }


    @Test
    public void testLockingSample() throws Exception {
        SomeOtherObject someOtherObject = SomeOtherObject.builder()
                .myId("11")
                .value("Hello")
                .build();

        SomeOtherObject someOtherObject2 = SomeOtherObject.builder()
                .myId("12")
                .value("Hello")
                .build();
        SomeOtherObject someOtherObject3 = SomeOtherObject.builder()
                .myId("12")
                .value("Hello")
                .build();

        // save
        LockedContext<SomeOtherObject> context = relationDao.saveAndGetExecutor(someOtherObject.getMyId(), someOtherObject);
        context.save(relationDao, parent -> {
            someOtherObject2.setMyId(String.valueOf(parent.getId()));
            return someOtherObject2;
        });
        context.save(relationDao, parent -> {
            someOtherObject3.setMyId(String.valueOf(parent.getId()));
            return someOtherObject3;
        });
        context.execute();

        // update
        LockedContext<SomeOtherObject> contextUpdate = relationDao.lockAndGetExecutor(someOtherObject.getMyId(),
                DetachedCriteria.forClass(SomeOtherObject.class)
                        .add(Restrictions.eq("myId", someOtherObject.getMyId())));
        contextUpdate.mutate(parent -> parent.setValue("UPDATE"));
        contextUpdate.execute();

        // get
        Optional<SomeOtherObject> resp = relationDao.get(someOtherObject.getMyId(), 1L);
        assertNotNull(resp.get());
        assertEquals("UPDATE", resp.get().getValue());
        Optional<SomeOtherObject> resp1 = relationDao.get(someOtherObject.getMyId(), 2L);
        assertNotNull(resp1.get());
    }

    @Test
    public void testLockingSampleWithQuerySpec() throws Exception {
        SomeOtherObject someOtherObject = SomeOtherObject.builder()
                .myId("11")
                .value("Hello")
                .build();

        SomeOtherObject someOtherObject2 = SomeOtherObject.builder()
                .myId("12")
                .value("Hello")
                .build();
        SomeOtherObject someOtherObject3 = SomeOtherObject.builder()
                .myId("12")
                .value("Hello")
                .build();

        // save
        LockedContext<SomeOtherObject> context = relationDao.saveAndGetExecutor(someOtherObject.getMyId(), someOtherObject);
        context.save(relationDao, parent -> {
            someOtherObject2.setMyId(String.valueOf(parent.getId()));
            return someOtherObject2;
        });
        context.save(relationDao, parent -> {
            someOtherObject3.setMyId(String.valueOf(parent.getId()));
            return someOtherObject3;
        });
        context.execute();

        // update
        val contextUpdate = relationDao.lockAndGetExecutor(someOtherObject.getMyId(),
                (queryRoot, query, criteriaBuilder) -> query.where(criteriaBuilder.equal(queryRoot.get("myId"), someOtherObject.getMyId())));
        contextUpdate.mutate(parent -> parent.setValue("UPDATE"));
        contextUpdate.execute();

        // get
        Optional<SomeOtherObject> resp = relationDao.get(someOtherObject.getMyId(), 1L);
        assertNotNull(resp.get());
        assertEquals("UPDATE", resp.get().getValue());
        Optional<SomeOtherObject> resp1 = relationDao.get(someOtherObject.getMyId(), 2L);
        assertNotNull(resp1.get());
    }

    @Test
    public void testLockingUpdateOneChild() throws Exception {
        SomeOtherObject someOtherObject = SomeOtherObject.builder()
                .myId("11")
                .value("Hello")
                .build();

        SomeOtherObject someOtherObject2 = SomeOtherObject.builder()
                .myId("12")
                .value("Hello")
                .build();

        // save
        LockedContext<SomeOtherObject> context = relationDao.saveAndGetExecutor(someOtherObject.getMyId(), someOtherObject);
        context.save(relationDao, parent -> {
            someOtherObject2.setMyId(String.valueOf(parent.getId()));
            return someOtherObject2;
        });
        context.execute();

        // update
        LockedContext<SomeOtherObject> contextUpdate = relationDao.lockAndGetExecutor(someOtherObject.getMyId(),
                DetachedCriteria.forClass(SomeOtherObject.class)
                        .add(Restrictions.eq("myId", someOtherObject.getMyId())));
        contextUpdate.mutate(parent -> parent.setValue("UPDATE"));

        contextUpdate.update(relationDao, someOtherObject2.getId(),
                child -> {
                    child.setValue("HELLO_UPDATED");
                    return child;
                });

        contextUpdate.execute();

        // get
        Optional<SomeOtherObject> resp = relationDao.get(someOtherObject.getMyId(), 1L);
        assertNotNull(resp.get());
        Optional<SomeOtherObject> resp1 = relationDao.get(someOtherObject.getMyId(), 2L);
        assertNotNull(resp1.get());
        assertEquals("HELLO_UPDATED", resp1.get().getValue());
    }

    @Test
    public void testLockingUpdateOneChildWithQuerySpec() throws Exception {
        SomeOtherObject someOtherObject = SomeOtherObject.builder()
                .myId("11")
                .value("Hello")
                .build();

        SomeOtherObject someOtherObject2 = SomeOtherObject.builder()
                .myId("12")
                .value("Hello")
                .build();

        // save
        LockedContext<SomeOtherObject> context = relationDao.saveAndGetExecutor(someOtherObject.getMyId(), someOtherObject);
        context.save(relationDao, parent -> {
            someOtherObject2.setMyId(String.valueOf(parent.getId()));
            return someOtherObject2;
        });
        context.execute();

        // update
        LockedContext<SomeOtherObject> contextUpdate = relationDao.lockAndGetExecutor(someOtherObject.getMyId(),
                (queryRoot, query, criteriaBuilder) ->
                        query.where(criteriaBuilder.equal(queryRoot.get("myId"), someOtherObject.getMyId())));

        contextUpdate.mutate(parent -> parent.setValue("UPDATE"));

        contextUpdate.update(relationDao, someOtherObject2.getId(),
                child -> {
                    child.setValue("HELLO_UPDATED");
                    return child;
                });

        contextUpdate.execute();

        // get
        Optional<SomeOtherObject> resp = relationDao.get(someOtherObject.getMyId(), 1L);
        assertNotNull(resp.get());
        Optional<SomeOtherObject> resp1 = relationDao.get(someOtherObject.getMyId(), 2L);
        assertNotNull(resp1.get());
        assertEquals("HELLO_UPDATED", resp1.get().getValue());
    }

    @Test
    public void testLockingUpdateMultipleChild() throws Exception {
        SomeOtherObject someOtherObject = SomeOtherObject.builder()
                .myId("11")
                .value("Hello")
                .build();

        SomeOtherObject someOtherObject2 = SomeOtherObject.builder()
                .myId("12")
                .value("Hello")
                .build();
        SomeOtherObject someOtherObject3 = SomeOtherObject.builder()
                .myId("12")
                .value("Hello")
                .build();

        // save
        LockedContext<SomeOtherObject> context = relationDao.saveAndGetExecutor(someOtherObject.getMyId(), someOtherObject);
        context.save(relationDao, parent -> {
            someOtherObject2.setMyId(String.valueOf(parent.getId()));
            return someOtherObject2;
        });
        context.save(relationDao, parent -> {
            someOtherObject3.setMyId(String.valueOf(parent.getId()));
            return someOtherObject3;
        });
        context.execute();

        // update
        LockedContext<SomeOtherObject> contextUpdate = relationDao.lockAndGetExecutor(someOtherObject.getMyId(),
                DetachedCriteria.forClass(SomeOtherObject.class)
                        .add(Restrictions.eq("myId", someOtherObject.getMyId())));
        contextUpdate.mutate(parent -> parent.setValue("UPDATE"));

        contextUpdate.update(relationDao, someOtherObject2.getId(),
                child1 -> {
                    child1.setValue("CHILD_ONE");
                    return child1;
                });
        contextUpdate.update(relationDao, someOtherObject3.getId(),
                child2 -> {
                    child2.setValue("CHILD_TWO");
                    return child2;
                });

        contextUpdate.execute();

        // get
        Optional<SomeOtherObject> resp = relationDao.get(someOtherObject.getMyId(), 1L);
        assertNotNull(resp.get());
        Optional<SomeOtherObject> resp1 = relationDao.get(someOtherObject.getMyId(), 2L);
        assertNotNull(resp1.get());
        assertEquals("CHILD_ONE", resp1.get().getValue());
        Optional<SomeOtherObject> resp2 = relationDao.get(someOtherObject.getMyId(), 3L);
        assertNotNull(resp2.get());
        assertEquals("CHILD_TWO", resp2.get().getValue());
    }

    @Test
    public void testLockingUpdateOneChildWithCriteria() throws Exception {
        SomeOtherObject p1 = SomeOtherObject.builder()
                .myId("11")
                .value("Hello")
                .build();

        SomeOtherObject c1 = SomeOtherObject.builder()
                .myId("12")
                .value("Hello")
                .build();

        // save
        LockedContext<SomeOtherObject> context = relationDao.saveAndGetExecutor(p1.getMyId(), p1);
        context.save(relationDao, parent -> {
            c1.setMyId(String.valueOf(parent.getId()));
            return c1;
        });
        context.execute();

        // update
        LockedContext<SomeOtherObject> contextUpdate = relationDao.lockAndGetExecutor(p1.getMyId(),
                DetachedCriteria.forClass(SomeOtherObject.class)
                        .add(Restrictions.eq("myId", p1.getMyId())));
        contextUpdate.mutate(parent -> parent.setValue("UPDATE"));

        contextUpdate.update(relationDao,
                DetachedCriteria.forClass(SomeOtherObject.class)
                        .add(Restrictions.eq("id", c1.getId())),
                child -> {
                    child.setValue("CHILD_ONE");
                    return child;
                }, () -> false);

        contextUpdate.execute();

        // get
        Optional<SomeOtherObject> resp = relationDao.get(p1.getMyId(), 1L);
        assertNotNull(resp.get());
        assertEquals("UPDATE", resp.get().getValue());

        Optional<SomeOtherObject> resp1 = relationDao.get(p1.getMyId(), 2L);
        assertNotNull(resp1.get());
        assertEquals("CHILD_ONE", resp1.get().getValue());
    }

    @Test
    public void testLockingUpdateOneChildWithCriteriaWithQuerySpec() throws Exception {
        SomeOtherObject p1 = SomeOtherObject.builder()
                .myId("11")
                .value("Hello")
                .build();

        SomeOtherObject c1 = SomeOtherObject.builder()
                .myId("12")
                .value("Hello")
                .build();

        // save
        LockedContext<SomeOtherObject> context = relationDao.saveAndGetExecutor(p1.getMyId(), p1);
        context.save(relationDao, parent -> {
            c1.setMyId(String.valueOf(parent.getId()));
            return c1;
        });
        context.execute();

        // update
        LockedContext<SomeOtherObject> contextUpdate = relationDao.lockAndGetExecutor(p1.getMyId(),
                (queryRoot, query, criteriaBuilder) ->
                        query.where(criteriaBuilder.equal(queryRoot.get("myId"), p1.getMyId())));
        contextUpdate.mutate(parent -> parent.setValue("UPDATE"));

        contextUpdate.update(relationDao,
                DetachedCriteria.forClass(SomeOtherObject.class)
                        .add(Restrictions.eq("id", c1.getId())),
                child -> {
                    child.setValue("CHILD_ONE");
                    return child;
                }, () -> false);

        contextUpdate.execute();

        // get
        Optional<SomeOtherObject> resp = relationDao.get(p1.getMyId(), 1L);
        assertNotNull(resp.get());
        assertEquals("UPDATE", resp.get().getValue());

        Optional<SomeOtherObject> resp1 = relationDao.get(p1.getMyId(), 2L);
        assertNotNull(resp1.get());
        assertEquals("CHILD_ONE", resp1.get().getValue());
    }

    @Test
    public void testLockingUpdateMultipleChildWithCriteria() throws Exception {
        SomeOtherObject p1 = SomeOtherObject.builder()
                .myId("11")
                .value("Hello")
                .build();

        SomeOtherObject c1 = SomeOtherObject.builder()
                .myId("12")
                .value("Hello")
                .build();

        SomeOtherObject c2 = SomeOtherObject.builder()
                .myId("13")
                .value("Hello")
                .build();

        // save
        LockedContext<SomeOtherObject> context = relationDao.saveAndGetExecutor(p1.getMyId(), p1);
        context.save(relationDao, parent -> {
            c1.setMyId(String.valueOf(parent.getId()));
            return c1;
        });
        context.save(relationDao, parent -> {
            c2.setMyId(String.valueOf(parent.getId()));
            return c2;
        });
        context.execute();

        // update
        LockedContext<SomeOtherObject> contextUpdate = relationDao.lockAndGetExecutor(p1.getMyId(),
                DetachedCriteria.forClass(SomeOtherObject.class)
                        .add(Restrictions.eq("myId", p1.getMyId())));
        contextUpdate.mutate(parent -> parent.setValue("UPDATE"));

        contextUpdate.update(relationDao,
                DetachedCriteria.forClass(SomeOtherObject.class)
                        .add(Restrictions.eq("id", c1.getId())),
                child -> {
                    child.setValue("CHILD_ONE");
                    return child;
                }, () -> false);

        contextUpdate.update(relationDao,
                DetachedCriteria.forClass(SomeOtherObject.class)
                        .add(Restrictions.eq("id", c2.getId())),
                child -> {
                    child.setValue("CHILD_TWO");
                    return child;
                }, () -> false);


        contextUpdate.execute();

        // get
        Optional<SomeOtherObject> resp = relationDao.get(p1.getMyId(), 1L);
        assertNotNull(resp.get());
        assertEquals("UPDATE", resp.get().getValue());

        Optional<SomeOtherObject> resp1 = relationDao.get(p1.getMyId(), 2L);
        assertNotNull(resp1.get());
        assertEquals("CHILD_ONE", resp1.get().getValue());

        Optional<SomeOtherObject> resp2 = relationDao.get(p1.getMyId(), 3L);
        assertNotNull(resp2.get());
        assertEquals("CHILD_TWO", resp2.get().getValue());
    }

    @Test
    public void testLockingUpdateMultipleChildWithCriteriaWithQuerySpec() throws Exception {
        SomeOtherObject p1 = SomeOtherObject.builder()
                .myId("11")
                .value("Hello")
                .build();

        SomeOtherObject c1 = SomeOtherObject.builder()
                .myId("12")
                .value("Hello")
                .build();

        SomeOtherObject c2 = SomeOtherObject.builder()
                .myId("13")
                .value("Hello")
                .build();

        // save
        LockedContext<SomeOtherObject> context = relationDao.saveAndGetExecutor(p1.getMyId(), p1);
        context.save(relationDao, parent -> {
            c1.setMyId(String.valueOf(parent.getId()));
            return c1;
        });
        context.save(relationDao, parent -> {
            c2.setMyId(String.valueOf(parent.getId()));
            return c2;
        });
        context.execute();

        // update
        LockedContext<SomeOtherObject> contextUpdate = relationDao.lockAndGetExecutor(p1.getMyId(),
                (queryRoot, query, criteriaBuilder) ->
                        query.where(criteriaBuilder.equal(queryRoot.get("myId"), p1.getMyId())));

        contextUpdate.mutate(parent -> parent.setValue("UPDATE"));

        contextUpdate.update(relationDao,
                (queryRoot, query, criteriaBuilder) ->
                        query.where(criteriaBuilder.equal(queryRoot.get("id"), c1.getId())),
                child -> {
                    child.setValue("CHILD_ONE");
                    return child;
                }, () -> false);

        contextUpdate.update(relationDao,
                (queryRoot, query, criteriaBuilder) ->
                        query.where(criteriaBuilder.equal(queryRoot.get("id"), c2.getId())),
                child -> {
                    child.setValue("CHILD_TWO");
                    return child;
                }, () -> false);


        contextUpdate.execute();

        // get
        Optional<SomeOtherObject> resp = relationDao.get(p1.getMyId(), 1L);
        assertNotNull(resp.get());
        assertEquals("UPDATE", resp.get().getValue());

        Optional<SomeOtherObject> resp1 = relationDao.get(p1.getMyId(), 2L);
        assertNotNull(resp1.get());
        assertEquals("CHILD_ONE", resp1.get().getValue());

        Optional<SomeOtherObject> resp2 = relationDao.get(p1.getMyId(), 3L);
        assertNotNull(resp2.get());
        assertEquals("CHILD_TWO", resp2.get().getValue());
    }

    @Test
    @SneakyThrows
    public void testReadMultiChildRetrieve() {
        SomeLookupObject p1 = SomeLookupObject.builder()
                .myId("0")
                .name("Parent 1")
                .build();


        final DetachedCriteria allSelectCriteria = DetachedCriteria.forClass(SomeOtherObject.class)
                .add(Restrictions.eq("myId", p1.getMyId()))
                .addOrder(Order.asc("id"));

        assertFalse(lookupDao.readOnlyExecutor(p1.getMyId()).execute().isPresent());

        val testExecuted = new AtomicBoolean();
        val res = lookupDao.readOnlyExecutor(p1.getMyId(),
                        () -> saveEntity(lookupDao.saveAndGetExecutor(p1)))
                .readAugmentParent(relationDao, allSelectCriteria, 0, Integer.MAX_VALUE, (parent, children) -> {
                    assertNull(parent.getChildren());
                    assertEquals(6, children.size());
                    assertNotNull(parent);
                    testExecuted.set(true);
                    parent.setChildren(children);
                })
                .execute();

        assertTrue(res.isPresent());
        assertEquals(6, res.get().getChildren().size());
        assertTrue(testExecuted.get());
    }

    @Test
    @SneakyThrows
    public void testReadMultiChildRetrieveWithQuerySpec() {
        SomeLookupObject p1 = SomeLookupObject.builder()
                .myId("0")
                .name("Parent 1")
                .build();

        final QuerySpec<SomeOtherObject, SomeOtherObject> querySpec = (queryRoot, query, criteriaBuilder) -> {
            query.where(
                    criteriaBuilder.equal(queryRoot.get("myId"), p1.getMyId())
            );
            query.orderBy(criteriaBuilder.asc(queryRoot.get("id")));
        };

        assertFalse(lookupDao.readOnlyExecutor(p1.getMyId()).execute().isPresent());

        val testExecuted = new AtomicBoolean();
        val res = lookupDao.readOnlyExecutor(p1.getMyId(),
                        () -> saveEntity(lookupDao.saveAndGetExecutor(p1)))
                .readAugmentParent(relationDao, querySpec, 0, Integer.MAX_VALUE, (parent, children) -> {
                    assertNull(parent.getChildren());
                    assertEquals(6, children.size());
                    assertNotNull(parent);
                    testExecuted.set(true);
                    parent.setChildren(children);
                })
                .execute();

        assertTrue(res.isPresent());
        assertEquals(6, res.get().getChildren().size());
        assertTrue(testExecuted.get());
    }

    @Test
    @SneakyThrows
    public void testReadMultiChildRetrieveNoPopulate() {
        final DetachedCriteria allSelectCriteria = DetachedCriteria.forClass(SomeOtherObject.class)
                .add(Restrictions.eq("myId", "0"))
                .addOrder(Order.asc("id"));
        assertFalse(lookupDao.readOnlyExecutor("0").execute().isPresent());

        assertFalse(lookupDao.readOnlyExecutor("0", () -> false)
                .readAugmentParent(relationDao,
                        allSelectCriteria,
                        0,
                        Integer.MAX_VALUE,
                        (parent, children) -> {
                        })
                .execute()
                .isPresent());
    }

    @Test
    @SneakyThrows
    public void testReadMultiChildRetrieveNoPopulateWithQuerySpec() {
        final QuerySpec<SomeOtherObject, SomeOtherObject> querySpec = (queryRoot, query, criteriaBuilder) -> {
            query.where(
                    criteriaBuilder.equal(queryRoot.get("myId"), "0")
            );
            query.orderBy(criteriaBuilder.asc(queryRoot.get("id")));
        };
        assertFalse(lookupDao.readOnlyExecutor("0").execute().isPresent());
        assertFalse(lookupDao.readOnlyExecutor("0", () -> false)
                .readAugmentParent(relationDao,
                        querySpec,
                        0,
                        Integer.MAX_VALUE,
                        (parent, children) -> {
                        })
                .execute()
                .isPresent());
    }

    @Test
    @SneakyThrows
    public void testReadMultiChildConditional() {
        SomeLookupObject p1 = SomeLookupObject.builder()
                .myId("0")
                .name("Parent 1")
                .build();
        saveEntity(lookupDao.saveAndGetExecutor(p1));
        SomeLookupObject p2 = SomeLookupObject.builder()
                .myId("1")
                .name("Parent 1")
                .build();
        saveEntity(lookupDao.saveAndGetExecutor(p2));

        final DetachedCriteria allSelectCriteria = DetachedCriteria.forClass(SomeOtherObject.class)
                .add(Restrictions.eq("myId", p1.getMyId()))
                .addOrder(Order.asc("id"));
        val testExecuted = new AtomicBoolean();
        val res = lookupDao.readOnlyExecutor(p1.getMyId())
                .readAugmentParent(relationDao, allSelectCriteria, 0, Integer.MAX_VALUE, (parent, children) -> {
                    assertNull(parent.getChildren());
                    assertEquals(6, children.size());
                    assertNotNull(parent);
                    testExecuted.set(true);
                    parent.setChildren(children);
                })
                .execute();

        assertTrue(res.isPresent());
        assertEquals(6, res.get().getChildren().size());
        assertTrue(testExecuted.get());


        testExecuted.set(false);
        val res2 = lookupDao.readOnlyExecutor(p2.getMyId())
                .readAugmentParent(relationDao, allSelectCriteria, 0, Integer.MAX_VALUE, (parent, children) -> {
                            testExecuted.set(true);
                        },
                        p -> !p.getMyId().equals("1")) //Don't read children if object id is blah
                .execute();

        assertTrue(res2.isPresent());
        assertFalse(testExecuted.get());
    }

    @Test
    @SneakyThrows
    public void testReadMultiChildConditionalWithQuerySpec() {
        SomeLookupObject p1 = SomeLookupObject.builder()
                .myId("0")
                .name("Parent 1")
                .build();
        saveEntity(lookupDao.saveAndGetExecutor(p1));
        SomeLookupObject p2 = SomeLookupObject.builder()
                .myId("1")
                .name("Parent 1")
                .build();
        saveEntity(lookupDao.saveAndGetExecutor(p2));

        final QuerySpec<SomeOtherObject, SomeOtherObject> querySpec = (queryRoot, query, criteriaBuilder) -> {
            query.where(
                    criteriaBuilder.equal(queryRoot.get("myId"), p1.getMyId())
            );
            query.orderBy(criteriaBuilder.asc(queryRoot.get("id")));
        };

        val testExecuted = new AtomicBoolean();
        val res = lookupDao.readOnlyExecutor(p1.getMyId())
                .readAugmentParent(relationDao, querySpec, 0, Integer.MAX_VALUE, (parent, children) -> {
                    assertNull(parent.getChildren());
                    assertEquals(6, children.size());
                    assertNotNull(parent);
                    testExecuted.set(true);
                    parent.setChildren(children);
                })
                .execute();

        assertTrue(res.isPresent());
        assertEquals(6, res.get().getChildren().size());
        assertTrue(testExecuted.get());

        testExecuted.set(false);
        val res2 = lookupDao.readOnlyExecutor(p2.getMyId())
                .readAugmentParent(relationDao, querySpec, 0, Integer.MAX_VALUE, (parent, children) -> {
                            testExecuted.set(true);
                        },
                        p -> !p.getMyId().equals("1")) //Don't read children if object id is blah
                .execute();

        assertTrue(res2.isPresent());
        assertFalse(testExecuted.get());
    }

    @Test
    @SneakyThrows
    void testReadMultiChildRetrieveWithQuerySpecFactory() {
        SomeLookupObject p1 = SomeLookupObject.builder()
                .myId("0")
                .name("Parent 1")
                .build();

        final Function<SomeLookupObject, QuerySpec<SomeOtherObject, SomeOtherObject>> querySpecFactory =
                parent -> (queryRoot, query, criteriaBuilder) -> {
                    query.where(
                            criteriaBuilder.equal(queryRoot.get("myId"), parent.getMyId())
                    );
                    query.orderBy(criteriaBuilder.asc(queryRoot.get("id")));
                };

        assertFalse(lookupDao.readOnlyExecutor(p1.getMyId()).execute().isPresent());

        val testExecuted = new AtomicBoolean();
        val res = lookupDao.readOnlyExecutor(p1.getMyId(),
                        () -> saveEntity(lookupDao.saveAndGetExecutor(p1)))
                .readAugmentParent(relationDao, querySpecFactory, 0, Integer.MAX_VALUE, (parent, children) -> {
                    assertNull(parent.getChildren());
                    assertEquals(6, children.size());
                    assertNotNull(parent);
                    testExecuted.set(true);
                    parent.setChildren(children);
                })
                .execute();

        assertTrue(res.isPresent());
        assertEquals(6, res.get().getChildren().size());
        assertTrue(testExecuted.get());
    }

    @Test
    @SneakyThrows
    void testReadMultiChildRetrieveNoPopulateWithQuerySpecFactory() {
        final Function<SomeLookupObject, QuerySpec<SomeOtherObject, SomeOtherObject>> querySpecFactory =
                parent -> (queryRoot, query, criteriaBuilder) -> {
                    query.where(
                            criteriaBuilder.equal(queryRoot.get("myId"), parent.getMyId())
                    );
                    query.orderBy(criteriaBuilder.asc(queryRoot.get("id")));
                };

        assertFalse(lookupDao.readOnlyExecutor("0").execute().isPresent());
        AtomicBoolean consumerInvoked = new AtomicBoolean(false);
        assertFalse(lookupDao.readOnlyExecutor("0", () -> false)
                .readAugmentParent(relationDao,
                        querySpecFactory,
                        0,
                        Integer.MAX_VALUE,
                        (parent, children) -> {
                            consumerInvoked.set(true);
                        })
                .execute()
                .isPresent());
        assertFalse(consumerInvoked.get());
    }

    @Test
    @SneakyThrows
    void testReadMultiChildConditionalWithQuerySpecFactory() {
        SomeLookupObject p1 = SomeLookupObject.builder()
                .myId("0")
                .name("Parent 1")
                .build();
        saveEntity(lookupDao.saveAndGetExecutor(p1));
        SomeLookupObject p2 = SomeLookupObject.builder()
                .myId("1")
                .name("Parent 1")
                .build();
        saveEntity(lookupDao.saveAndGetExecutor(p2));

        final Function<SomeLookupObject, QuerySpec<SomeOtherObject, SomeOtherObject>> querySpecFactory =
                parent -> (queryRoot, query, criteriaBuilder) -> {
                    query.where(
                            criteriaBuilder.equal(queryRoot.get("myId"), parent.getMyId())
                    );
                    query.orderBy(criteriaBuilder.asc(queryRoot.get("id")));
                };

        val testExecuted = new AtomicBoolean();
        val res = lookupDao.readOnlyExecutor(p1.getMyId())
                .readAugmentParent(relationDao, querySpecFactory, 0, Integer.MAX_VALUE, (parent, children) -> {
                    assertNull(parent.getChildren());
                    assertEquals(6, children.size());
                    assertNotNull(parent);
                    testExecuted.set(true);
                    parent.setChildren(children);
                })
                .execute();

        assertTrue(res.isPresent());
        assertEquals(6, res.get().getChildren().size());
        assertTrue(testExecuted.get());

        testExecuted.set(false);
        val res2 = lookupDao.readOnlyExecutor(p2.getMyId())
                .readAugmentParent(relationDao, querySpecFactory, 0, Integer.MAX_VALUE, (parent, children) -> {
                            testExecuted.set(true);
                        },
                        p -> !p.getMyId().equals("1"))
                .execute();

        assertTrue(res2.isPresent());
        assertFalse(testExecuted.get());
    }

    @Test
    @SneakyThrows
    void testReadOneAugmentParentWithQuerySpecFactory() {
        SomeLookupObject p1 = SomeLookupObject.builder()
                .myId("0")
                .name("Parent 1")
                .build();
        saveEntity(lookupDao.saveAndGetExecutor(p1));

        final Function<SomeLookupObject, QuerySpec<SomeOtherObject, SomeOtherObject>> querySpecFactory =
                parent -> (queryRoot, query, criteriaBuilder) -> {
                    query.where(
                            criteriaBuilder.equal(queryRoot.get("myId"), parent.getMyId())
                    );
                    query.orderBy(criteriaBuilder.asc(queryRoot.get("id")));
                };

        val testExecuted = new AtomicBoolean();
        val res = lookupDao.readOnlyExecutor(p1.getMyId())
                .readOneAugmentParent(relationDao, querySpecFactory, (parent, children) -> {
                    assertEquals(1, children.size());
                    testExecuted.set(true);
                    parent.setChildren(children);
                })
                .execute();

        assertTrue(res.isPresent());
        assertEquals(1, res.get().getChildren().size());
        assertTrue(testExecuted.get());
    }

    @Test
    @SneakyThrows
    void testReadOneAugmentParentWithQuerySpecFactoryAndFilter() {
        SomeLookupObject p1 = SomeLookupObject.builder()
                .myId("0")
                .name("Parent 1")
                .build();
        saveEntity(lookupDao.saveAndGetExecutor(p1));

        final Function<SomeLookupObject, QuerySpec<SomeOtherObject, SomeOtherObject>> querySpecFactory =
                parent -> (queryRoot, query, criteriaBuilder) -> {
                    query.where(
                            criteriaBuilder.equal(queryRoot.get("myId"), parent.getMyId())
                    );
                    query.orderBy(criteriaBuilder.asc(queryRoot.get("id")));
                };

        // Filter passes — child should be fetched
        val testExecuted = new AtomicBoolean();
        val res = lookupDao.readOnlyExecutor(p1.getMyId())
                .readOneAugmentParent(relationDao, querySpecFactory, (parent, children) -> {
                    assertEquals(1, children.size());
                    testExecuted.set(true);
                    parent.setChildren(children);
                }, p -> true)
                .execute();

        assertTrue(res.isPresent());
        assertEquals(1, res.get().getChildren().size());
        assertTrue(testExecuted.get());

        // Filter fails — consumer should NOT be invoked
        testExecuted.set(false);
        val res2 = lookupDao.readOnlyExecutor(p1.getMyId())
                .readOneAugmentParent(relationDao, querySpecFactory, (parent, children) -> {
                    testExecuted.set(true);
                }, p -> false)
                .execute();

        assertTrue(res2.isPresent());
        assertFalse(testExecuted.get());
    }

    @Test
    @SneakyThrows
    void testReadAugmentParentWithQuerySpecFactoryNullReturnsException() {
        SomeLookupObject p1 = SomeLookupObject.builder()
                .myId("0")
                .name("Parent 1")
                .build();
        saveEntity(lookupDao.saveAndGetExecutor(p1));

        final Function<SomeLookupObject, QuerySpec<SomeOtherObject, SomeOtherObject>> nullFactory =
                parent -> null;

        val readOnlyContext = lookupDao.readOnlyExecutor(p1.getMyId())
                        .readAugmentParent(relationDao, nullFactory, 0, Integer.MAX_VALUE,
                                (parent, children) -> {});
        assertThrows(RuntimeException.class, () -> readOnlyContext.execute());
    }

    @Test
    public void testUpdateWithLockAndExecuteConsumerAddsAnotherOperationInSameTransaction() throws Exception {
        SomeLookupObject p1 = SomeLookupObject.builder()
            .myId("0")
            .name("Parent 1")
            .build();

        SomeOtherObject c1 = relationDao.save(p1.getMyId(), SomeOtherObject.builder()
            .myId(p1.getMyId())
            .value("Hello")
            .build()).get();


        var lockedContext  = lookupDao.saveAndGetExecutor(p1)
            .filter(parent -> !Strings.isNullOrEmpty(parent.getName()));

        lockedContext.mutate(someLookupObject -> {
            someLookupObject.setName("Changed");
            lockedContext.save(relationDao, c1, child -> {
                c1.setValue(someLookupObject.getName());
                return c1;
            });
        }).execute();


        assertEquals(p1.getMyId(), lookupDao.get("0").get().getMyId());
        assertEquals("Changed", lookupDao.get("0").get().getName());
        assertEquals("Changed", relationDao.get("0", 1L).get().getValue());
    }

    @Test
    @SneakyThrows
    void testLockAndMutateEachUpdatesMultipleRelationalEntities() {
        // Arrange
        SomeLookupObject parent = SomeLookupObject.builder()
                .myId("0")
                .name("Parent")
                .build();
        lookupDao.save(parent);

        SomeOtherObject c1 = relationDao.save("0", SomeOtherObject.builder()
                .myId("0")
                .value("OriginalOne")
                .build()).get();
        SomeOtherObject c2 = relationDao.save("0", SomeOtherObject.builder()
                .myId("0")
                .value("OriginalTwo")
                .build()).get();

        List<DetachedCriteria> criteriaList = List.of(
                DetachedCriteria.forClass(SomeOtherObject.class).add(Restrictions.eq("id", c1.getId())),
                DetachedCriteria.forClass(SomeOtherObject.class).add(Restrictions.eq("id", c2.getId()))
        );

        // Act: one transaction locks parent, locks+mutates c1 and c2
        lookupDao.lockAndGetExecutor("0")
                .lockAndMutateEach(relationDao, criteriaList, child -> {
                    child.setValue("UPDATED");
                    return child;
                })
                .mutate(p -> p.setName("Changed"))
                .execute();

        // Assert parent name was changed
        assertEquals("Changed", lookupDao.get("0").get().getName());
        // Assert both relational children were mutated
        assertEquals("UPDATED", relationDao.get("0", c1.getId()).get().getValue());
        assertEquals("UPDATED", relationDao.get("0", c2.getId()).get().getValue());
    }

    @Test
    @SneakyThrows
    public void testLockAndMutateEachThrowsWhenEntityNotFound() {
        SomeLookupObject parent = SomeLookupObject.builder()
                .myId("0")
                .name("Parent")
                .build();
        lookupDao.save(parent);

        // Criteria that matches no row
        List<DetachedCriteria> criteriaList = List.of(
                DetachedCriteria.forClass(SomeOtherObject.class).add(Restrictions.eq("id", Long.MAX_VALUE))
        );

        assertThrows(RuntimeException.class, () ->
                lookupDao.lockAndGetExecutor("0")
                        .lockAndMutateEach(relationDao, criteriaList, child -> child)
                        .execute()
        );

        // TX rolled back: parent name unchanged
        assertEquals("Parent", lookupDao.get("0").get().getName());
    }

    @Test
    @SneakyThrows
    public void testLockAndMutateUpdatesLookupEntityWithinRelationalContext() {
        // Arrange: a lookup entity and a relational entity both on the shard for key "0"
        SomeLookupObject parent = SomeLookupObject.builder()
                .myId("0")
                .name("InitialName")
                .build();
        lookupDao.save(parent);

        SomeOtherObject relational = relationDao.save("0", SomeOtherObject.builder()
                .myId("0")
                .value("InitialValue")
                .build()).get();

        // Act: lock relational entity; within the same TX also lock+mutate the lookup entity
        relationDao.lockAndGetExecutor("0",
                        DetachedCriteria.forClass(SomeOtherObject.class)
                                .add(Restrictions.eq("id", relational.getId())))
                .lockAndMutate(lookupDao, "0", lookup -> lookup.setName("UpdatedName"))
                .mutate(r -> r.setValue("UpdatedValue"))
                .execute();

        // Assert relational entity value was changed
        assertEquals("UpdatedValue", relationDao.get("0", relational.getId()).get().getValue());
        // Assert lookup entity name was changed in the same transaction
        assertEquals("UpdatedName", lookupDao.get("0").get().getName());
    }

    @Test
    @SneakyThrows
    public void testLockAndMutateThrowsWhenLookupEntityNotFound() {
        SomeLookupObject parent = SomeLookupObject.builder()
                .myId("0")
                .name("Parent")
                .build();
        lookupDao.save(parent);

        SomeOtherObject relational = relationDao.save("0", SomeOtherObject.builder()
                .myId("0")
                .value("Hello")
                .build()).get();

        // "nonexistent-key" has no SomeLookupObject → lockAndMutate throws RuntimeException
        assertThrows(RuntimeException.class, () ->
                relationDao.lockAndGetExecutor("0",
                                DetachedCriteria.forClass(SomeOtherObject.class)
                                        .add(Restrictions.eq("id", relational.getId())))
                        .lockAndMutate(lookupDao, "nonexistent-key",
                                lookup -> lookup.setName("Updated"))
                        .mutate(r -> r.setValue("ShouldNotPersist"))
                        .execute()
        );

        // TX rolled back: relational entity value unchanged
        assertEquals("Hello", relationDao.get("0", relational.getId()).get().getValue());
    }

    @Test
    @SneakyThrows
    public void testLockAndMutateThrowsWhenLookupKeyMapsToDifferentShard() {
        // Anchor the LockedContext on shard for key "0" (maps to shard 0).
        SomeLookupObject parentShard0 = SomeLookupObject.builder()
                .myId("0")
                .name("Shard0Parent")
                .build();
        lookupDao.save(parentShard0);

        SomeOtherObject relational = relationDao.save("0", SomeOtherObject.builder()
                .myId("0")
                .value("Hello")
                .build()).get();

        // A real lookup entity exists for key "1" — but "1" maps to shard 1, a DIFFERENT shard.
        SomeLookupObject parentShard1 = SomeLookupObject.builder()
                .myId("1")
                .name("Shard1Parent")
                .build();
        lookupDao.save(parentShard1);

        // Cross-shard lockAndMutate must be rejected with IllegalArgumentException,
        // even though the entity exists (proves it's the shard guard, not "entity not found").
        assertThrows(IllegalArgumentException.class, () ->
                relationDao.lockAndGetExecutor("0",
                                DetachedCriteria.forClass(SomeOtherObject.class)
                                        .add(Restrictions.eq("id", relational.getId())))
                        .lockAndMutate(lookupDao, "1", lookup -> lookup.setName("ShouldNotPersist"))
                        .mutate(r -> r.setValue("ShouldNotPersist"))
                        .execute()
        );

        // TX rolled back: neither entity changed.
        assertEquals("Hello", relationDao.get("0", relational.getId()).get().getValue());
        assertEquals("Shard1Parent", lookupDao.get("1").get().getName());
    }

    @Test
    @SneakyThrows
    public void testLockAndMutateEachProtectsEachRowFromConcurrentAccess() {
        lookupDao.save(SomeLookupObject.builder().myId("0").name("Parent").build());

        SomeOtherObject c1 = relationDao.save("0", SomeOtherObject.builder()
                .myId("0").value("OriginalC1").build()).get();
        SomeOtherObject c2 = relationDao.save("0", SomeOtherObject.builder()
                .myId("0").value("OriginalC2").build()).get();

        List<DetachedCriteria> criteriaList = List.of(
                DetachedCriteria.forClass(SomeOtherObject.class).add(Restrictions.eq("id", c1.getId())),
                DetachedCriteria.forClass(SomeOtherObject.class).add(Restrictions.eq("id", c2.getId()))
        );

        CountDownLatch bothRowsLockedLatch = new CountDownLatch(1);
        CountDownLatch releaseLatch = new CountDownLatch(1);
        AtomicBoolean firstMutatorCalled = new AtomicBoolean(false);
        ExecutorService executor = Executors.newSingleThreadExecutor();

        // T1: locks c1, then c2 via lockAndMutateEach; signals only after c2 is locked
        Future<?> txn1 = executor.submit(() -> {
            try {
                lookupDao.lockAndGetExecutor("0")
                        .lockAndMutateEach(relationDao, criteriaList, child -> {
                            if (firstMutatorCalled.getAndSet(true)) {
                                // Second call: c2 is now locked — signal and hold
                                bothRowsLockedLatch.countDown();
                                try {
                                    releaseLatch.await(5, TimeUnit.SECONDS);
                                } catch (InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                }
                            }
                            child.setValue("T1_UPDATED");
                            return child;
                        })
                        .execute();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        // Wait until T1 holds locks on both c1 and c2
        assertTrue(bothRowsLockedLatch.await(5, TimeUnit.SECONDS), "T1 should lock both rows within 5s");

        // T2: independently lock c2 directly (bypasses parent — tests row-level lock specifically)
        assertThrows(RuntimeException.class, () ->
                relationDao.lockAndGetExecutor("0",
                                DetachedCriteria.forClass(SomeOtherObject.class)
                                        .add(Restrictions.eq("id", c2.getId())))
                        .execute()
        );

        releaseLatch.countDown();
        txn1.get(10, TimeUnit.SECONDS);
        executor.shutdown();

        assertEquals("T1_UPDATED", relationDao.get("0", c1.getId()).get().getValue());
        assertEquals("T1_UPDATED", relationDao.get("0", c2.getId()).get().getValue());
    }

    @Test
    @SneakyThrows
    public void testLockAndMutateEachFailsWhenRowAlreadyLockedByAnotherTransaction() {
        lookupDao.save(SomeLookupObject.builder().myId("0").name("Parent").build());

        SomeOtherObject c1 = relationDao.save("0", SomeOtherObject.builder()
                .myId("0").value("OriginalC1").build()).get();
        SomeOtherObject c2 = relationDao.save("0", SomeOtherObject.builder()
                .myId("0").value("OriginalC2").build()).get();

        CountDownLatch c1LockedLatch = new CountDownLatch(1);
        CountDownLatch releaseLatch = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();

        // T1: lock c1 directly via the traditional lockAndGetExecutor (single-row lock)
        // Parent (SomeLookupObject) is NOT locked by T1.
        Future<?> txn1 = executor.submit(() -> {
            try {
                relationDao.lockAndGetExecutor("0",
                                DetachedCriteria.forClass(SomeOtherObject.class)
                                        .add(Restrictions.eq("id", c1.getId())))
                        .mutate(child -> {
                            c1LockedLatch.countDown();
                            try {
                                releaseLatch.await(5, TimeUnit.SECONDS);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                            child.setValue("T1_UPDATED");
                        })
                        .execute();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        assertTrue(c1LockedLatch.await(5, TimeUnit.SECONDS), "T1 should lock c1 within 5s");

        // T2: lockAndMutateEach on [c1, c2] — parent lock succeeds (T1 doesn't hold it),
        // but the NOWAIT lock on c1 fails immediately because T1 holds it.
        assertThrows(RuntimeException.class, () ->
                lookupDao.lockAndGetExecutor("0")
                        .lockAndMutateEach(relationDao, List.of(
                                DetachedCriteria.forClass(SomeOtherObject.class).add(Restrictions.eq("id", c1.getId())),
                                DetachedCriteria.forClass(SomeOtherObject.class).add(Restrictions.eq("id", c2.getId()))
                        ), child -> child)
                        .execute()
        );

        releaseLatch.countDown();
        txn1.get(10, TimeUnit.SECONDS);
        executor.shutdown();

        assertEquals("T1_UPDATED", relationDao.get("0", c1.getId()).get().getValue());
    }

    @Test
    @SneakyThrows
    public void testSingleTableLockAndMutateEachProtectsRowsFromConcurrentAccess() {
        // Single-table scenario (analogous to user_balances sharded by user_id):
        // c0 = anchor row, c1 + c2 = "program balance" rows — all in some_other_data, no SomeLookupObject needed.
        SomeOtherObject c0 = relationDao.save("0", SomeOtherObject.builder()
                .myId("0").value("Anchor").build()).get();
        SomeOtherObject c1 = relationDao.save("0", SomeOtherObject.builder()
                .myId("0").value("OriginalC1").build()).get();
        SomeOtherObject c2 = relationDao.save("0", SomeOtherObject.builder()
                .myId("0").value("OriginalC2").build()).get();

        CountDownLatch bothRowsLockedLatch = new CountDownLatch(1);
        CountDownLatch releaseLatch = new CountDownLatch(1);
        AtomicBoolean firstMutatorCalled = new AtomicBoolean(false);
        ExecutorService executor = Executors.newSingleThreadExecutor();

        // T1: lock c0 as anchor, then lock c1 and c2 via lockAndMutateEach — all one table
        Future<?> txn1 = executor.submit(() -> {
            try {
                relationDao.lockAndGetExecutor("0",
                                DetachedCriteria.forClass(SomeOtherObject.class)
                                        .add(Restrictions.eq("id", c0.getId())))
                        .lockAndMutateEach(relationDao, List.of(
                                DetachedCriteria.forClass(SomeOtherObject.class).add(Restrictions.eq("id", c1.getId())),
                                DetachedCriteria.forClass(SomeOtherObject.class).add(Restrictions.eq("id", c2.getId()))
                        ), child -> {
                            if (firstMutatorCalled.getAndSet(true)) {
                                // Second call: c2 now locked — signal and hold
                                bothRowsLockedLatch.countDown();
                                try {
                                    releaseLatch.await(5, TimeUnit.SECONDS);
                                } catch (InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                }
                            }
                            child.setValue("T1_UPDATED");
                            return child;
                        })
                        .execute();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        assertTrue(bothRowsLockedLatch.await(5, TimeUnit.SECONDS), "T1 should lock both rows within 5s");

        // T2: independently try to lock c2 directly — same table, no parent involved
        assertThrows(RuntimeException.class, () ->
                relationDao.lockAndGetExecutor("0",
                                DetachedCriteria.forClass(SomeOtherObject.class)
                                        .add(Restrictions.eq("id", c2.getId())))
                        .execute()
        );

        releaseLatch.countDown();
        txn1.get(10, TimeUnit.SECONDS);
        executor.shutdown();

        assertEquals("T1_UPDATED", relationDao.get("0", c1.getId()).get().getValue());
        assertEquals("T1_UPDATED", relationDao.get("0", c2.getId()).get().getValue());
    }

    @Test
    @SneakyThrows
    public void testSingleTableLockAndMutateEachFailsWhenRowAlreadyLocked() {
        // Reverse: T1 holds c1 via the traditional lockAndGetExecutor (single-row lock).
        // T2 starts a lockAndMutateEach on [c1, c2] anchored at c0 — fails on c1.
        SomeOtherObject c0 = relationDao.save("0", SomeOtherObject.builder()
                .myId("0").value("Anchor").build()).get();
        SomeOtherObject c1 = relationDao.save("0", SomeOtherObject.builder()
                .myId("0").value("OriginalC1").build()).get();
        SomeOtherObject c2 = relationDao.save("0", SomeOtherObject.builder()
                .myId("0").value("OriginalC2").build()).get();

        CountDownLatch c1LockedLatch = new CountDownLatch(1);
        CountDownLatch releaseLatch = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();

        // T1: lock c1 via traditional lockAndGetExecutor — does NOT lock c0 or c2
        Future<?> txn1 = executor.submit(() -> {
            try {
                relationDao.lockAndGetExecutor("0",
                                DetachedCriteria.forClass(SomeOtherObject.class)
                                        .add(Restrictions.eq("id", c1.getId())))
                        .mutate(child -> {
                            c1LockedLatch.countDown();
                            try {
                                releaseLatch.await(5, TimeUnit.SECONDS);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                            child.setValue("T1_UPDATED");
                        })
                        .execute();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        assertTrue(c1LockedLatch.await(5, TimeUnit.SECONDS), "T1 should lock c1 within 5s");

        // T2: anchor at c0 (unlocked), then lockAndMutateEach [c1, c2] — fails on c1
        assertThrows(RuntimeException.class, () ->
                relationDao.lockAndGetExecutor("0",
                                DetachedCriteria.forClass(SomeOtherObject.class)
                                        .add(Restrictions.eq("id", c0.getId())))
                        .lockAndMutateEach(relationDao, List.of(
                                DetachedCriteria.forClass(SomeOtherObject.class).add(Restrictions.eq("id", c1.getId())),
                                DetachedCriteria.forClass(SomeOtherObject.class).add(Restrictions.eq("id", c2.getId()))
                        ), child -> child)
                        .execute()
        );

        releaseLatch.countDown();
        txn1.get(10, TimeUnit.SECONDS);
        executor.shutdown();

        assertEquals("T1_UPDATED", relationDao.get("0", c1.getId()).get().getValue());
    }

    private boolean saveEntity(LockedContext<SomeLookupObject> lockedContext) {
        return lockedContext
                .filter(parent -> !Strings.isNullOrEmpty(parent.getName()))
                .save(relationDao, parent -> SomeOtherObject.builder()
                        .myId(parent.getMyId())
                        .value("Hello")
                        .build())
                .saveAll(relationDao,
                        parent -> IntStream.range(1, 6)
                                .mapToObj(i -> SomeOtherObject.builder()
                                        .myId(parent.getMyId())
                                        .value(String.format("Hello_%s", i))
                                        .build())
                                .collect(Collectors.toList())
                )
                .mutate(parent -> parent.setName("Changed"))
                .execute() != null;
    }
}

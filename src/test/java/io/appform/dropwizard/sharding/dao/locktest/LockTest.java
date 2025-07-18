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
        final ShardingBundleOptions shardingOptions = ShardingBundleOptions.builder().skipReadOnlyTransaction(true).build();
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

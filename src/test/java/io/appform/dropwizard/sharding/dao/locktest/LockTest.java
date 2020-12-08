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
import io.appform.dropwizard.sharding.dao.LookupDao;
import io.appform.dropwizard.sharding.dao.RelationalDao;
import io.appform.dropwizard.sharding.dao.UpdateOperationMeta;
import io.appform.dropwizard.sharding.sharding.BalancedShardManager;
import io.appform.dropwizard.sharding.sharding.ShardManager;
import io.appform.dropwizard.sharding.utils.ShardCalculator;
import lombok.val;
import org.hibernate.SessionFactory;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.cfg.Configuration;
import org.hibernate.criterion.DetachedCriteria;
import org.hibernate.criterion.Order;
import org.hibernate.criterion.Restrictions;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;


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
        final ShardCalculator<String> shardCalculator = new ShardCalculator<>(shardManager, Integer::parseInt);
        lookupDao = new LookupDao<>(sessionFactories, SomeLookupObject.class, shardCalculator);
        relationDao = new RelationalDao<>(sessionFactories, SomeOtherObject.class, shardCalculator);
    }

    @Test
    public void testLocking() throws Exception {
        SomeLookupObject p1 = SomeLookupObject.builder()
                                .myId("0")
                                .name("Parent 1")
                                .build();
        lookupDao.save(p1);
        lookupDao.lockAndGetExecutor("0")
                .filter(parent -> !Strings.isNullOrEmpty(parent.getName()))
                .save(relationDao, parent -> SomeOtherObject.builder()
                        .myId(parent.getMyId())
                        .value("Hello")
                        .build())
                .saveAll(relationDao,
                        parent -> IntStream.range(1,6)
                            .mapToObj(i -> SomeOtherObject.builder()
                                    .myId(parent.getMyId())
                                    .value(String.format("Hello_%s", i))
                                    .build())
                        .collect(Collectors.toList())
                )
                .mutate(parent -> parent.setName("Changed"))
                .execute();

        Assertions.assertEquals(p1.getMyId(), lookupDao.get("0").get().getMyId());
        Assertions.assertEquals("Changed", lookupDao.get("0").get().getName());
        Assertions.assertEquals(6, relationDao.select("0", DetachedCriteria.forClass(SomeOtherObject.class), 0, 10).size());
        Assertions.assertEquals("Hello",relationDao.get("0", 1L).get().getValue());
    }

    @Test
    public void testLockingFail() throws Exception {
        Assertions.assertThrows(IllegalArgumentException.class,() -> {
                    SomeLookupObject p1 = SomeLookupObject.builder()
                            .myId("0")
                            .build();
                    lookupDao.save(p1);
                    lookupDao.lockAndGetExecutor("0")
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
                            .execute();
                });


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

        Assertions.assertEquals(p1.getMyId(), lookupDao.get("0").get().getMyId());
        Assertions.assertEquals("Changed", lookupDao.get("0").get().getName());
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

        Assertions.assertEquals(p1.getMyId(), lookupDao.get("0").get().getMyId());
        Assertions.assertEquals("Changed", lookupDao.get("0").get().getName());
        Assertions.assertEquals("Hello Changed",relationDao.get("0", 1L).get().getValue());
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

        Assertions.assertEquals(p1.getMyId(), lookupDao.get("0").get().getMyId());
        Assertions.assertEquals("Changed", lookupDao.get("0").get().getName());
        Assertions.assertEquals("Hello Changed",relationDao.get("0", 1L).get().getValue());
    }

    @Test
    public void testPersist_alreadyExistingDifferent() throws Exception {
        SomeLookupObject p1 = SomeLookupObject.builder()
                .myId("0")
                .name("Parent 1")
                .build();

        SomeLookupObject p2 = SomeLookupObject.builder()
                .myId("0")
                .name("Changed")
                .build();

        Assertions.assertThrows(ConstraintViolationException.class,()->{
            lookupDao.save(p1);

            lookupDao.saveAndGetExecutor(p2)
                    .filter(parent -> !Strings.isNullOrEmpty(parent.getName()))
                    .save(relationDao, parent -> SomeOtherObject.builder()
                            .myId(parent.getMyId())
                            .value("Hello")
                            .build())
                    .execute();
        });

        Assertions.assertEquals(p1.getMyId(), lookupDao.get("0").get().getMyId());
        Assertions.assertEquals("Changed", lookupDao.get("0").get().getName());
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

        Assertions.assertEquals(p1.getMyId(), lookupDao.get("0").get().getMyId());
        Assertions.assertEquals("Changed", lookupDao.get("0").get().getName());
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
                    Assertions.fail("New Entity is getting created. It should have been updated.");
                    return SomeOtherObject.builder()
                            .myId(parentId)
                            .value("test")
                            .build();
                })
                .mutate(parentObj -> parentObj.setName(parentModifiedValue))
                .execute();

        Assertions.assertEquals(childModifiedValue,relationDao.get(parent.getMyId(), child.getId()).get().getValue());
        Assertions.assertEquals(parentModifiedValue,lookupDao.get(parentId).get().getName());

        //test non existing entity creation
        final String newChildValue = "Newly created child";
        final String newParentValue = "New parent Value";
        final DetachedCriteria creationCriteria = DetachedCriteria.forClass(SomeOtherObject.class)
                .add(Restrictions.eq("value", newChildValue));

        lookupDao.lockAndGetExecutor(parent.getMyId())
                .createOrUpdate(relationDao, creationCriteria, childObj -> {
                    Assertions.assertNotEquals(null, childObj);
                    Assertions.fail("New Entity is getting updated. It should have been created.");

                    childObj.setValue("abcd");
                    return childObj;

                }, () -> SomeOtherObject.builder()
                            .myId(parentId)
                            .value(newChildValue)
                            .build())
                .mutate(parentObj -> parentObj.setName(newParentValue))
                .execute();

        final SomeOtherObject savedChild = relationDao.select(parent.getMyId(), creationCriteria, 0, 1).stream().findFirst().get();
        Assertions.assertEquals(newChildValue, savedChild.getValue());
        Assertions.assertNotEquals(child.getId(), savedChild.getId());
        Assertions.assertEquals(newParentValue,lookupDao.get(parentId).get().getName());
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
                                .params(ImmutableMap.of("value", childModifiedValue, "myId", parent.getMyId()))
                                .build())
                .execute();

        val updatedChild = relationDao.get(parent.getMyId(), child.getId()).orElse(null);
        Assertions.assertNotNull(updatedChild);
        Assertions.assertEquals(childModifiedValue, updatedChild.getValue());
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
                .update(relationDao,allSelectCriteria, entityObj -> {
                    entityObj.setValue(childModifiedValue);
                    return entityObj;
                }, () -> true)
                .mutate(parent -> parent.setName(parentModifiedValue))
                .execute();

        Assertions.assertEquals(childModifiedValue,relationDao.get(parent1.getMyId(), child1.getId()).get().getValue());
        Assertions.assertEquals(childModifiedValue,relationDao.get(parent1.getMyId(), child2.getId()).get().getValue());
        Assertions.assertEquals(parentModifiedValue,lookupDao.get(parent1Id).get().getName());

        Assertions.assertEquals("Hello3",relationDao.get(parent2.getMyId(), child3.getId()).get().getValue());
        Assertions.assertEquals("Parent 2",lookupDao.get(parent2Id).get().getName());

        final boolean[] shouldUpdateNext = new boolean[1];
        shouldUpdateNext[0] = true;

        //test partial update
        final String childModifiedValue2 = "Hello Modified Partial";
        final String parentModifiedValue2 = "Parent Changed Partial";
        lookupDao.lockAndGetExecutor(parent1.getMyId())
                .update(relationDao,allSelectCriteria, entityObj -> {
                    entityObj.setValue(childModifiedValue2);

                    if (entityObj.getId() == child1.getId()) {
                        shouldUpdateNext[0] = false;
                    }

                    return entityObj;
                }, () -> shouldUpdateNext[0])
                .mutate(parent -> parent.setName(parentModifiedValue2))
                .execute();

        Assertions.assertEquals(childModifiedValue2,relationDao.get(parent1Id, child1.getId()).get().getValue());
        Assertions.assertEquals(childModifiedValue,relationDao.get(parent1Id, child2.getId()).get().getValue());
        Assertions.assertEquals(parentModifiedValue2,lookupDao.get(parent1Id).get().getName());

        Assertions.assertEquals("Hello3",relationDao.get(parent2.getMyId(), child3.getId()).get().getValue());
        Assertions.assertEquals("Parent 2",lookupDao.get(parent2Id).get().getName());
    }
}

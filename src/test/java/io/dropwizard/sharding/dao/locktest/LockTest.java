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

package io.dropwizard.sharding.dao.locktest;

import com.google.common.base.Strings;
import com.google.common.collect.Lists;
import io.dropwizard.sharding.dao.LookupDao;
import io.dropwizard.sharding.dao.RelationalDao;
import io.dropwizard.sharding.sharding.ShardManager;
import org.hibernate.SessionFactory;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.cfg.Configuration;
import org.hibernate.criterion.DetachedCriteria;
import org.hibernate.criterion.Restrictions;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.util.List;

/**
 * Test locking behavior
 */
public class LockTest {
    private List<SessionFactory> sessionFactories = Lists.newArrayList();

    private LookupDao<SomeLookupObject> lookupDao;
    private RelationalDao<SomeOtherObject> relationDao;

    private SessionFactory buildSessionFactory(String dbName) {
        Configuration configuration = new Configuration();
        configuration.setProperty("hibernate.dialect",
                "org.hibernate.dialect.H2Dialect");
        configuration.setProperty("hibernate.connection.driver_class",
                "org.h2.Driver");
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

    @Before
    public void before() {
        for (int i = 0; i < 2; i++) {
            SessionFactory sessionFactory = buildSessionFactory(String.format("db_%d", i));
            sessionFactories.add(sessionFactory);
        }
        final ShardManager shardManager = new ShardManager(sessionFactories.size());
        lookupDao = new LookupDao<>(sessionFactories, SomeLookupObject.class, shardManager, Integer::parseInt);
        relationDao = new RelationalDao<>(sessionFactories, SomeOtherObject.class, shardManager, Integer::parseInt);
    }

    @Test
    public void testLocking() throws Exception {
        SomeLookupObject p1 = SomeLookupObject.builder()
                                .myId("0")
                                .name("Parent 1")
                                .build();
        lookupDao.save(p1);
        System.out.println(lookupDao.get("0").get().getName());

        lookupDao.lockedExecutor("0")
                .filter(parent -> !Strings.isNullOrEmpty(parent.getName()))
                .save(relationDao, parent -> {
                    SomeOtherObject result = SomeOtherObject.builder()
                            .my_id(parent.getMyId())
                            .value("Hello")
                            .build();
                    parent.setName("Changed");
                    return result;
                })
                .mutate(parent -> parent.setName("Changed"))
                .execute();

        Assert.assertEquals(p1.getMyId(), lookupDao.get("0").get().getMyId());
        System.out.println(lookupDao.get("0").get().getName());
        System.out.println(relationDao.get("0", 1L).get());
    }

    @Test(expected = IllegalArgumentException.class)
    public void testLockingFail() throws Exception {
        SomeLookupObject p1 = SomeLookupObject.builder()
                                .myId("0")
                                .build();
        lookupDao.save(p1);
        System.out.println(lookupDao.get("0").get().getName());

        lookupDao.lockedExecutor("0")
                .filter(parent -> !Strings.isNullOrEmpty(parent.getName()))
                .save(relationDao, parent -> {
                    SomeOtherObject result = SomeOtherObject.builder()
                            .my_id(parent.getMyId())
                            .value("Hello")
                            .build();
                    parent.setName("Changed");
                    return result;
                })
                .mutate(parent -> parent.setName("Changed"))
                .execute();

    }


    @Test
    public void testSelectInLocking() throws Exception {
        SomeLookupObject p1 = SomeLookupObject.builder()
                .myId("0")
                .name("Parent 1")
                .build();
        lookupDao.save(p1);
        System.out.println(lookupDao.get("0").get().getName());

        lookupDao.lockedExecutor("0")
                .save(relationDao, parent -> {
                    SomeOtherObject result = SomeOtherObject.builder()
                            .my_id(parent.getMyId())
                            .value("Hello")
                            .build();
                    return result;
                })
                .execute();


        lookupDao.lockedExecutor("0")
                .filter(parent -> !Strings.isNullOrEmpty(parent.getName()))
                .filter(parent -> {
                    try {
                        List<SomeOtherObject> obj = relationDao.select("0", DetachedCriteria.forClass(SomeOtherObject.class).add(Restrictions.eq("value", "Hello")), false);
                        System.out.println("fetched object inside locked context"  + obj.toString());
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                    return true;
                })
                .mutate(parent -> parent.setName("Changed"))
                .execute();

        Assert.assertEquals(p1.getMyId(), lookupDao.get("0").get().getMyId());
        System.out.println(lookupDao.get("0").get().getName());
        System.out.println(relationDao.get("0", 1L).get());
    }

}

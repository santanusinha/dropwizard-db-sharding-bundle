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

import com.codahale.metrics.MetricRegistry;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import io.appform.dropwizard.sharding.ShardInfoProvider;
import io.appform.dropwizard.sharding.dao.testdata.entities.RelationalEntity;
import io.appform.dropwizard.sharding.sharding.BalancedShardManager;
import io.appform.dropwizard.sharding.sharding.ShardManager;
import io.appform.dropwizard.sharding.sharding.impl.ConsistentHashBucketIdExtractor;
import io.appform.dropwizard.sharding.utils.ShardCalculator;
import lombok.val;
import org.hibernate.SessionFactory;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.cfg.Configuration;
import org.hibernate.criterion.DetachedCriteria;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

public class RelationalDaoTest {

    private List<SessionFactory> sessionFactories = Lists.newArrayList();
    private RelationalDao<RelationalEntity> relationalDao;

    private SessionFactory buildSessionFactory(String dbName) {
        Configuration configuration = new Configuration();
        configuration.setProperty("hibernate.dialect",
                                  "org.hibernate.dialect.H2Dialect");
        configuration.setProperty("hibernate.connection.driver_class",
                                  "org.h2.Driver");
        configuration.setProperty("hibernate.connection.url", "jdbc:h2:mem:" + dbName);
        configuration.setProperty("hibernate.hbm2ddl.auto", "create");
        configuration.setProperty("hibernate.current_session_context_class", "managed");
        configuration.addAnnotatedClass(RelationalEntity.class);

        StandardServiceRegistry serviceRegistry
                = new StandardServiceRegistryBuilder().applySettings(
                configuration.getProperties())
                .build();
        return configuration.buildSessionFactory(serviceRegistry);
    }

    @Before
    public void before() {
        for (int i = 0; i < 16; i++) {
            sessionFactories.add(buildSessionFactory(String.format("db_%d", i)));
        }
        final ShardManager shardManager = new BalancedShardManager(sessionFactories.size());
        relationalDao = new RelationalDao<>(new MetricRegistry(), new ShardInfoProvider("default"),
                sessionFactories, RelationalEntity.class,
                new ShardCalculator<>(shardManager,
                        new ConsistentHashBucketIdExtractor<>(shardManager)));
    }

    @After
    public void after() {
        sessionFactories.forEach(SessionFactory::close);
    }

    @Test
    public void testBulkSave() throws Exception {
        String key = "testPhone";
        RelationalEntity entityOne = RelationalEntity.builder()
                .key("1")
                .value("abcd")
                .build();
        RelationalEntity entityTwo = RelationalEntity.builder()
                .key("2")
                .value("abcd")
                .build();
        relationalDao.saveAll(key, Lists.newArrayList(entityOne, entityTwo));
        List<RelationalEntity> entities = relationalDao.select(key,
                                                               DetachedCriteria.forClass(RelationalEntity.class),
                                                               0,
                                                               10);
        assertEquals(2, entities.size());

    }

    @Test
    public void testUpdateUsingQuery() throws Exception {
        val relationalKey = UUID.randomUUID().toString();

        val entityOne = RelationalEntity.builder()
                .key("1")
                .keyTwo("1")
                .value(UUID.randomUUID().toString())
                .build();
        relationalDao.save(relationalKey, entityOne);

        val entityTwo = RelationalEntity.builder()
                .key("2")
                .keyTwo("2")
                .value(UUID.randomUUID().toString())
                .build();
        relationalDao.save(relationalKey, entityTwo);

        val entityThree = RelationalEntity.builder()
                .key("3")
                .keyTwo("2")
                .value(UUID.randomUUID().toString())
                .build();
        relationalDao.save(relationalKey, entityThree);


        val newValue = UUID.randomUUID().toString();
        int rowsUpdated = relationalDao.updateUsingQuery(relationalKey,
                UpdateOperationMeta.builder()
                        .queryName("testUpdateUsingKeyTwo")
                        .params(ImmutableMap.of("keyTwo", "2",
                                "value", newValue))
                        .build()
        );
        assertEquals(2, rowsUpdated);

        val persistedEntityTwo = relationalDao.get(relationalKey, "2").orElse(null);
        assertNotNull(persistedEntityTwo);
        assertEquals(newValue, persistedEntityTwo.getValue());

        val persistedEntityThree = relationalDao.get(relationalKey, "3").orElse(null);
        assertNotNull(persistedEntityThree);
        assertEquals(newValue, persistedEntityThree.getValue());


    }

    @Test
    public void testUpdateUsingQueryNoRowUpdated() throws Exception {
        val relationalKey = UUID.randomUUID().toString();

        val entityOne = RelationalEntity.builder()
                .key("1")
                .keyTwo("1")
                .value(UUID.randomUUID().toString())
                .build();
        relationalDao.save(relationalKey, entityOne);

        val entityTwo = RelationalEntity.builder()
                .key("2")
                .keyTwo("2")
                .value(UUID.randomUUID().toString())
                .build();
        relationalDao.save(relationalKey, entityTwo);

        val entityThree = RelationalEntity.builder()
                .key("3")
                .keyTwo("2")
                .value(UUID.randomUUID().toString())
                .build();
        relationalDao.save(relationalKey, entityThree);


        val newValue = UUID.randomUUID().toString();
        int rowsUpdated = relationalDao.updateUsingQuery(relationalKey,
                UpdateOperationMeta.builder()
                        .queryName("testUpdateUsingKeyTwo")
                        .params(ImmutableMap.of("keyTwo", UUID.randomUUID().toString(),
                                "value", newValue))
                        .build()
        );
        assertEquals(0, rowsUpdated);


        val persistedEntityOne = relationalDao.get(relationalKey, "1").orElse(null);
        assertNotNull(persistedEntityOne);
        assertEquals(entityOne.getValue(), persistedEntityOne.getValue());

        val persistedEntityTwo = relationalDao.get(relationalKey, "2").orElse(null);
        assertNotNull(persistedEntityTwo);
        assertEquals(entityTwo.getValue(), persistedEntityTwo.getValue());

        val persistedEntityThree = relationalDao.get(relationalKey, "3").orElse(null);
        assertNotNull(persistedEntityThree);
        assertEquals(entityThree.getValue(), persistedEntityThree.getValue());
    }
}
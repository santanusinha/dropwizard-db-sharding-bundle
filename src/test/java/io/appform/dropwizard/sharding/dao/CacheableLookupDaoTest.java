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
import io.appform.dropwizard.sharding.caching.LookupCache;
import io.appform.dropwizard.sharding.caching.RelationalCache;
import io.appform.dropwizard.sharding.dao.testdata.entities.Audit;
import io.appform.dropwizard.sharding.dao.testdata.entities.Phone;
import io.appform.dropwizard.sharding.dao.testdata.entities.TestEntity;
import io.appform.dropwizard.sharding.dao.testdata.entities.Transaction;
import io.appform.dropwizard.sharding.sharding.BalancedShardManager;
import io.appform.dropwizard.sharding.sharding.ShardManager;
import io.appform.dropwizard.sharding.sharding.impl.ConsistentHashBucketIdExtractor;
import io.appform.dropwizard.sharding.utils.ShardCalculator;
import org.apache.commons.lang3.StringUtils;
import org.hibernate.SessionFactory;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.cfg.Configuration;
import org.hibernate.criterion.DetachedCriteria;
import org.hibernate.criterion.Restrictions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;


public class CacheableLookupDaoTest {

    private List<SessionFactory> sessionFactories = Lists.newArrayList();
    private CacheableLookupDao<TestEntity> lookupDao;
    private CacheableLookupDao<Phone> phoneDao;
    private CacheableRelationalDao<Transaction> transactionDao;
    private CacheableRelationalDao<Audit> auditDao;

    private SessionFactory buildSessionFactory(String dbName) {
        Configuration configuration = new Configuration();
        configuration.setProperty("hibernate.dialect",
                                  "org.hibernate.dialect.H2Dialect");
        configuration.setProperty("hibernate.connection.driver_class",
                                  "org.h2.Driver");
        configuration.setProperty("hibernate.connection.url", "jdbc:h2:mem:" + dbName);
        configuration.setProperty("hibernate.hbm2ddl.auto", "create");
        configuration.setProperty("hibernate.current_session_context_class", "managed");
        configuration.addAnnotatedClass(TestEntity.class);
        configuration.addAnnotatedClass(Phone.class);
        configuration.addAnnotatedClass(Transaction.class);
        configuration.addAnnotatedClass(Audit.class);

        StandardServiceRegistry serviceRegistry
                = new StandardServiceRegistryBuilder().applySettings(
                configuration.getProperties())
                .build();
        return configuration.buildSessionFactory(serviceRegistry);
    }


    @BeforeEach
    public void before() {
        for (int i = 0; i < 2; i++) {
            sessionFactories.add(buildSessionFactory(String.format("db_%d", i)));
        }
        final ShardManager shardManager = new BalancedShardManager(sessionFactories.size());
        lookupDao = new CacheableLookupDao<>(
                sessionFactories,
                TestEntity.class,
                new ShardCalculator<>(shardManager, new ConsistentHashBucketIdExtractor<>(shardManager)),
                new LookupCache<TestEntity>() {

                    private Map<String, TestEntity> cache = new HashMap<>();

                    @Override
                    public void put(String key, TestEntity entity) {
                        cache.put(key, entity);
                    }

                    @Override
                    public boolean exists(String key) {
                        return cache.containsKey(key);
                    }

                    @Override
                    public TestEntity get(String key) {
                        return cache.get(key);
                    }
                });
        phoneDao = new CacheableLookupDao<>(sessionFactories,
                                            Phone.class,
                                            new ShardCalculator<>(shardManager,
                                                                  new ConsistentHashBucketIdExtractor<>(shardManager)),
                                            new LookupCache<Phone>() {

                                                private Map<String, Phone> cache = new HashMap<>();

                                                @Override
                                                public void put(String key, Phone entity) {
                                                    cache.put(key, entity);
                                                }

                                                @Override
                                                public boolean exists(String key) {
                                                    return cache.containsKey(key);
                                                }

                                                @Override
                                                public Phone get(String key) {
                                                    return cache.get(key);
                                                }
                                            });
        transactionDao = new CacheableRelationalDao<>(sessionFactories,
                                                      Transaction.class,
                                                      new ShardCalculator<>(shardManager,
                                                                            new ConsistentHashBucketIdExtractor<>(
                                                                                    shardManager)),
                                                      new RelationalCache<Transaction>() {

                                                          private Map<String, Object> cache = new HashMap<>();

                                                          @Override
                                                          public void put(
                                                                  String parentKey,
                                                                  Object key,
                                                                  Transaction entity) {
                                                              cache.put(StringUtils.join(parentKey, key, ':'), entity);
                                                          }

                                                          @Override
                                                          public void put(
                                                                  String parentKey,
                                                                  List<Transaction> entities) {
                                                              cache.put(parentKey, entities);
                                                          }

                                                          @Override
                                                          public void put(
                                                                  String parentKey,
                                                                  int first,
                                                                  int numResults,
                                                                  List<Transaction> entities) {
                                                              cache.put(StringUtils.join(parentKey,
                                                                                         first,
                                                                                         numResults,
                                                                                         ':'), entities);
                                                          }

                                                          @Override
                                                          public boolean exists(String parentKey, Object key) {
                                                              return cache.containsKey(StringUtils.join(parentKey,
                                                                                                        key,
                                                                                                        ':'));
                                                          }

                                                          @Override
                                                          public Transaction get(String parentKey, Object key) {
                                                              return (Transaction) cache.get(StringUtils.join(parentKey,
                                                                                                              key,
                                                                                                              ':'));
                                                          }

                                                          @Override
                                                          public List<Transaction> select(String parentKey) {
                                                              return (List<Transaction>) cache.get(parentKey);
                                                          }

                                                          @Override
                                                          public List<Transaction> select(
                                                                  String parentKey,
                                                                  int first,
                                                                  int numResults) {
                                                              return (List<Transaction>) cache.get(StringUtils.join(
                                                                      parentKey,
                                                                      first,
                                                                      numResults,
                                                                      ':'));
                                                          }
                                                      });
        auditDao = new CacheableRelationalDao<>(sessionFactories,
                                                Audit.class,
                                                new ShardCalculator<>(shardManager,
                                                                      new ConsistentHashBucketIdExtractor<>(shardManager)),
                                                new RelationalCache<Audit>() {

                                                    private Map<String, Object> cache = new HashMap<>();

                                                    @Override
                                                    public void put(String parentKey, Object key, Audit entity) {
                                                        cache.put(StringUtils.join(parentKey, key, ':'), entity);
                                                    }

                                                    @Override
                                                    public void put(String parentKey, List<Audit> entities) {
                                                        cache.put(parentKey, entities);
                                                    }

                                                    @Override
                                                    public void put(
                                                            String parentKey,
                                                            int first,
                                                            int numResults,
                                                            List<Audit> entities) {
                                                        cache.put(StringUtils.join(parentKey, first, numResults, ':'),
                                                                  entities);
                                                    }

                                                    @Override
                                                    public boolean exists(String parentKey, Object key) {
                                                        return cache.containsKey(StringUtils.join(parentKey, key, ':'));
                                                    }

                                                    @Override
                                                    public Audit get(String parentKey, Object key) {
                                                        return (Audit) cache.get(StringUtils.join(parentKey, key, ':'));
                                                    }

                                                    @Override
                                                    public List<Audit> select(String parentKey) {
                                                        return (List<Audit>) cache.get(parentKey);
                                                    }

                                                    @Override
                                                    public List<Audit> select(
                                                            String parentKey,
                                                            int first,
                                                            int numResults) {
                                                        return (List<Audit>) cache.get(StringUtils.join(parentKey,
                                                                                                        first,
                                                                                                        numResults,
                                                                                                        ':'));
                                                    }
                                                });
    }

    @AfterEach
    public void after() {
        sessionFactories.forEach(SessionFactory::close);
    }

    @Test
    public void testSave() throws Exception {
        TestEntity testEntity = TestEntity.builder()
                .externalId("testId")
                .text("Some Text")
                .build();
        lookupDao.save(testEntity);

        Assertions.assertEquals(true, lookupDao.exists("testId"));
        Assertions.assertEquals(false, lookupDao.exists("testId1"));
        Optional<TestEntity> result = lookupDao.get("testId");
        Assertions.assertEquals("Some Text",
                     result.get()
                             .getText());

        testEntity.setText("Some New Text");
        lookupDao.save(testEntity);
        result = lookupDao.get("testId");
        Assertions.assertEquals("Some New Text",
                     result.get()
                             .getText());

        boolean updateStatus = lookupDao.update("testId", entity -> {
            if (entity.isPresent()) {
                TestEntity e = entity.get();
                e.setText("Updated text");
                return e;
            }
            return null;
        });

        Assertions.assertTrue(updateStatus);
        result = lookupDao.get("testId");
        Assertions.assertEquals("Updated text",
                     result.get()
                             .getText());

        updateStatus = lookupDao.update("testIdxxx", entity -> {
            if (entity.isPresent()) {
                TestEntity e = entity.get();
                e.setText("Updated text");
                return e;
            }
            return null;
        });

        Assertions.assertFalse(updateStatus);
    }

    @Test
    public void testScatterGather() throws Exception {
        List<TestEntity> results = lookupDao.scatterGather(DetachedCriteria.forClass(TestEntity.class)
                                                                   .add(Restrictions.eq("externalId", "testId")));
        Assertions.assertTrue(results.isEmpty());

        TestEntity testEntity = TestEntity.builder()
                .externalId("testId")
                .text("Some Text")
                .build();
        lookupDao.save(testEntity);
        results = lookupDao.scatterGather(DetachedCriteria.forClass(TestEntity.class)
                                                  .add(Restrictions.eq("externalId", "testId")));
        Assertions.assertFalse(results.isEmpty());
        Assertions.assertEquals("Some Text",
                     results.get(0)
                             .getText());
    }

    @Test
    public void testSaveInParentBucket() throws Exception {
        final String phoneNumber = "9830968020";

        Phone phone = Phone.builder()
                .phone(phoneNumber)
                .build();

        Phone savedPhone = phoneDao.save(phone)
                .get();

        Transaction transaction = Transaction.builder()
                .transactionId("testTxn")
                .to("9830703153")
                .amount(100)
                .phone(savedPhone)
                .build();

        transactionDao.save(savedPhone.getPhone(), transaction)
                .get();
        {
            Transaction resultTx = transactionDao.get(phoneNumber, "testTxn")
                    .get();
            Assertions.assertEquals(phoneNumber,
                         resultTx.getPhone()
                                 .getPhone());
            Assertions.assertTrue(transactionDao.exists(phoneNumber, "testTxn"));
            Assertions.assertFalse(transactionDao.exists(phoneNumber, "testTxn1"));
        }
        {
            Optional<Transaction> resultTx = transactionDao.get(phoneNumber, "testTxn1");
            Assertions.assertFalse(resultTx.isPresent());
        }
        saveAudit(phoneNumber, "testTxn", "Started");
        saveAudit(phoneNumber, "testTxn", "Underway");
        saveAudit(phoneNumber, "testTxn", "Completed");

        Assertions.assertEquals(3, auditDao.count(phoneNumber, DetachedCriteria.forClass(Audit.class)
                .add(Restrictions.eq("transaction.transactionId", "testTxn"))));

        List<Audit> audits = auditDao.select(phoneNumber, DetachedCriteria.forClass(Audit.class)
                .add(Restrictions.eq("transaction.transactionId", "testTxn")), 0, 10);
        Assertions.assertEquals("Started",
                     audits.get(0)
                             .getText());

    }

    private void saveAudit(String phone, String transaction, String text) throws Exception {
        auditDao.save(phone, Audit.builder()
                .text(text)
                .transaction(Transaction.builder()
                                     .transactionId(transaction)
                                     .build())
                .build());
    }

    @Test
    public void testHierarchy() throws Exception {
        final String phoneNumber = "9986032019";
        saveHierarchy(phoneNumber);
        saveHierarchy("9986402019");

        List<Audit> audits = auditDao.select(phoneNumber, DetachedCriteria.forClass(Audit.class)
                .add(Restrictions.eq("transaction.transactionId", "newTxn-" + phoneNumber)), 0, 10);

        Assertions.assertEquals(2, audits.size());

        List<Audit> allAudits = auditDao.scatterGather(DetachedCriteria.forClass(Audit.class), 0, 10);
        Assertions.assertEquals(4, allAudits.size());
    }


    private void saveHierarchy(String phone) throws Exception {

        Transaction transaction = Transaction.builder()
                .transactionId("newTxn-" + phone)
                .amount(100)
                .to("9986402019")
                .build();

        Audit started = Audit.builder()
                .text("Started")
                .transaction(transaction)
                .build();

        Audit completed = Audit.builder()
                .text("Completed")
                .transaction(transaction)
                .build();

        transaction.setAudits(ImmutableList.of(started, completed));

        transactionDao.save(phone, transaction);
    }
}
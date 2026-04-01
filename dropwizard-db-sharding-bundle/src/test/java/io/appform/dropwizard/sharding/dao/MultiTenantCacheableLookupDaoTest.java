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
import io.appform.dropwizard.sharding.ShardInfoProvider;
import io.appform.dropwizard.sharding.caching.LookupCache;
import io.appform.dropwizard.sharding.caching.RelationalCache;
import io.appform.dropwizard.sharding.config.ShardingBundleOptions;
import io.appform.dropwizard.sharding.dao.testdata.entities.Audit;
import io.appform.dropwizard.sharding.dao.testdata.entities.Phone;
import io.appform.dropwizard.sharding.dao.testdata.entities.TestEntity;
import io.appform.dropwizard.sharding.dao.testdata.entities.Transaction;
import io.appform.dropwizard.sharding.observers.internal.TerminalTransactionObserver;
import io.appform.dropwizard.sharding.sharding.BalancedShardManager;
import io.appform.dropwizard.sharding.sharding.ShardManager;
import org.apache.commons.lang3.StringUtils;
import org.hibernate.SessionFactory;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.cfg.Configuration;
import org.hibernate.criterion.DetachedCriteria;
import org.hibernate.criterion.Restrictions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;


public class MultiTenantCacheableLookupDaoTest {

  private Map<String, ShardManager> shardManager = new HashMap<>();
  private Map<String, List<SessionFactory>> sessionFactories = new HashMap<>();
  private MultiTenantCacheableLookupDao<TestEntity> lookupDao;
  private MultiTenantCacheableLookupDao<Phone> phoneDao;
  private MultiTenantCacheableRelationalDao<Transaction> transactionDao;
  private MultiTenantCacheableRelationalDao<Audit> auditDao;

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
    sessionFactories = Map.of("TENANT1",
        List.of(buildSessionFactory("tenant1_1"), buildSessionFactory("tenant1_2")),
        "TENANT2",
        List.of(buildSessionFactory("tenant2_1"), buildSessionFactory("tenant2_2"),
            buildSessionFactory("tenant2_3"), buildSessionFactory("tenant2_4")));
    sessionFactories.forEach((tenant, sessionFactory) ->
        shardManager.put(tenant, new BalancedShardManager(sessionFactory.size())));
    final Map<String, ShardingBundleOptions> shardingOptions = Map.of("TENANT1",
        new ShardingBundleOptions(), "TENANT2", new ShardingBundleOptions());

    final Map<String, ShardInfoProvider> shardInfoProvider = Map.of("TENANT1",
        new ShardInfoProvider("TENANT1"),
        "TENANT2", new ShardInfoProvider("TENANT2"));
      lookupDao = new MultiTenantCacheableLookupDao<>(
              sessionFactories,
              TestEntity.class,
              shardManager,
              Map.of("TENANT1", new LookupCache<TestEntity>() {

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
                      }, "TENANT2",
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
                      }),
              shardingOptions, shardInfoProvider, new TerminalTransactionObserver());
    phoneDao = new MultiTenantCacheableLookupDao<>(sessionFactories,
        Phone.class,
        shardManager,
        Map.of("TENANT1",
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
            }, "TENANT2",
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
            }),
        shardingOptions, shardInfoProvider, new TerminalTransactionObserver());
    transactionDao = new MultiTenantCacheableRelationalDao<>(sessionFactories,
        Transaction.class,
        shardManager,
        Map.of("TENANT1",
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
            }, "TENANT2",
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
            }), shardingOptions, shardInfoProvider, new TerminalTransactionObserver());
    auditDao = new MultiTenantCacheableRelationalDao<>(sessionFactories,
        Audit.class,
        shardManager,
        Map.of("TENANT1", new RelationalCache<Audit>() {

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
        }, "TENANT2", new RelationalCache<Audit>() {

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
        }), shardingOptions, shardInfoProvider, new TerminalTransactionObserver());
  }

  @AfterEach
  public void after() {
    sessionFactories.forEach((tenantId, sessionFactory) -> sessionFactory.forEach(
        SessionFactory::close));
  }

  @Test
  public void testSave() throws Exception {
    TestEntity testEntity = TestEntity.builder()
        .externalId("testId")
        .text("Some Text")
        .build();
    lookupDao.save("TENANT1", testEntity);
    assertEquals(true, lookupDao.exists("TENANT1", "testId"));
    assertEquals(false, lookupDao.exists("TENANT1", "testId1"));
    Optional<TestEntity> result = lookupDao.get("TENANT1", "testId");
    assertEquals("Some Text",
        result.get()
            .getText());

    testEntity.setText("Some New Text");
    lookupDao.save("TENANT1", testEntity);
    result = lookupDao.get("TENANT1", "testId");
    assertEquals("Some New Text",
        result.get()
            .getText());

    boolean updateStatus = lookupDao.update("TENANT1", "testId", entity -> {
      if (entity.isPresent()) {
        TestEntity e = entity.get();
        e.setText("Updated text");
        return e;
      }
      return null;
    });

    assertTrue(updateStatus);
    result = lookupDao.get("TENANT1", "testId");
    assertEquals("Updated text",
        result.get()
            .getText());

    updateStatus = lookupDao.update("TENANT1", "testIdxxx", entity -> {
      if (entity.isPresent()) {
        TestEntity e = entity.get();
        e.setText("Updated text");
        return e;
      }
      return null;
    });

    assertFalse(updateStatus);
  }

  @Test
  public void testScatterGather() throws Exception {
    List<TestEntity> results = lookupDao.scatterGather("TENANT1",
        DetachedCriteria.forClass(TestEntity.class)
            .add(Restrictions.eq("externalId", "testId")));
    assertTrue(results.isEmpty());

    TestEntity testEntity = TestEntity.builder()
        .externalId("testId")
        .text("Some Text")
        .build();
    lookupDao.save("TENANT1", testEntity);
    results = lookupDao.scatterGather("TENANT1", DetachedCriteria.forClass(TestEntity.class)
        .add(Restrictions.eq("externalId", "testId")));
    assertFalse(results.isEmpty());
    assertEquals("Some Text",
        results.get(0)
            .getText());
  }

  @Test
  public void testSaveInParentBucket() throws Exception {
    final String phoneNumber = "9830968020";

    Phone phone = Phone.builder()
        .phone(phoneNumber)
        .build();

    Phone savedPhone = phoneDao.save("TENANT1", phone)
        .get();

    Transaction transaction = Transaction.builder()
        .transactionId("testTxn")
        .to("9830703153")
        .amount(100)
        .phone(savedPhone)
        .build();

    transactionDao.save("TENANT1", savedPhone.getPhone(), transaction)
        .get();
    {
      Transaction resultTx = transactionDao.get("TENANT1", phoneNumber, "testTxn")
          .get();
      assertEquals(phoneNumber,
          resultTx.getPhone()
              .getPhone());
      assertTrue(transactionDao.exists("TENANT1", phoneNumber, "testTxn"));
      assertFalse(transactionDao.exists("TENANT1", phoneNumber, "testTxn1"));
    }
    {
      Optional<Transaction> resultTx = transactionDao.get("TENANT1", phoneNumber, "testTxn1");
      assertFalse(resultTx.isPresent());
    }
    saveAudit(phoneNumber, "testTxn", "Started");
    saveAudit(phoneNumber, "testTxn", "Underway");
    saveAudit(phoneNumber, "testTxn", "Completed");

    assertEquals(3, auditDao.count("TENANT1", phoneNumber, DetachedCriteria.forClass(Audit.class)
        .add(Restrictions.eq("transaction.transactionId", "testTxn"))));

    List<Audit> audits = auditDao.select("TENANT1", phoneNumber,
        DetachedCriteria.forClass(Audit.class)
            .add(Restrictions.eq("transaction.transactionId", "testTxn")), 0, 10);
    assertEquals("Started",
        audits.get(0)
            .getText());

  }

  private void saveAudit(String phone, String transaction, String text) throws Exception {
    auditDao.save("TENANT1", phone, Audit.builder()
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

    List<Audit> audits = auditDao.select("TENANT1", phoneNumber,
        DetachedCriteria.forClass(Audit.class)
            .add(Restrictions.eq("transaction.transactionId", "newTxn-" + phoneNumber)), 0, 10);

    assertEquals(2, audits.size());

    List<Audit> allAudits = auditDao.scatterGather("TENANT1",
        DetachedCriteria.forClass(Audit.class), 0, 10);
    assertEquals(4, allAudits.size());
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

    transactionDao.save("TENANT1", phone, transaction);
  }
}
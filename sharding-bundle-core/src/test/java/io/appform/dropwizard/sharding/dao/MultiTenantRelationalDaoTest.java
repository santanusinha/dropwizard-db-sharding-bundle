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

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import io.appform.dropwizard.sharding.ShardInfoProvider;
import io.appform.dropwizard.sharding.config.ShardingBundleOptions;
import io.appform.dropwizard.sharding.dao.interceptors.DaoClassLocalObserver;
import io.appform.dropwizard.sharding.dao.interceptors.EntityClassThreadLocalObserver;
import io.appform.dropwizard.sharding.dao.interceptors.InterceptorTestUtil;
import io.appform.dropwizard.sharding.dao.testdata.entities.RelationalEntity;
import io.appform.dropwizard.sharding.dao.testdata.entities.RelationalEntityWithAIKey;
import io.appform.dropwizard.sharding.observers.TransactionObserver;
import io.appform.dropwizard.sharding.observers.internal.TerminalTransactionObserver;
import io.appform.dropwizard.sharding.scroll.ScrollResult;
import io.appform.dropwizard.sharding.sharding.BalancedShardManager;
import io.appform.dropwizard.sharding.sharding.ShardManager;
import io.appform.dropwizard.sharding.sharding.impl.ConsistentHashBucketIdExtractor;
import io.appform.dropwizard.sharding.utils.ShardCalculator;
import lombok.val;
import org.apache.commons.lang3.RandomUtils;
import org.hibernate.SessionFactory;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.cfg.Configuration;
import org.hibernate.criterion.DetachedCriteria;
import org.hibernate.criterion.Property;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

public class MultiTenantRelationalDaoTest {

  private final Map<String, List<SessionFactory>> sessionFactories = new HashMap<>();
  private MultiTenantRelationalDao<RelationalEntity> relationalDao;
  private MultiTenantRelationalDao<RelationalEntityWithAIKey> relationalWithAIDao;

  private Map<String, ShardManager> shardManager = new HashMap<>();

  private ShardCalculator<String> shardCalculator;

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
    configuration.addAnnotatedClass(RelationalEntityWithAIKey.class);
    StandardServiceRegistry serviceRegistry
        = new StandardServiceRegistryBuilder().applySettings(
            configuration.getProperties())
        .build();
    return configuration.buildSessionFactory(serviceRegistry);
  }

  @BeforeEach
  public void before() {
    sessionFactories.put("TENANT1",
        IntStream.range(0, 16).mapToObj(i -> buildSessionFactory(String.format("tenant1_%d", i)))
            .collect(Collectors.toList()));
    sessionFactories.put("TENANT2",
        IntStream.range(0, 8).mapToObj(i -> buildSessionFactory(String.format("tenant2_%d", i)))
            .collect(Collectors.toList()));
    this.shardManager = Map.of("TENANT1",
        new BalancedShardManager(sessionFactories.get("TENANT1").size()),
        "TENANT2", new BalancedShardManager(sessionFactories.get("TENANT2").size()));
    this.shardCalculator = new ShardCalculator<>(shardManager,
        new ConsistentHashBucketIdExtractor<>(shardManager));
    final Map<String, ShardingBundleOptions> shardingOptions = Map.of("TENANT1",
        new ShardingBundleOptions(), "TENANT2", new ShardingBundleOptions());
    final Map<String, ShardInfoProvider> shardInfoProvider = Map.of("TENANT1",
        new ShardInfoProvider("TENANT1"),
        "TENANT2", new ShardInfoProvider("TENANT2"));
    final TransactionObserver observer = new EntityClassThreadLocalObserver(
        new DaoClassLocalObserver(new TerminalTransactionObserver()));
    relationalDao = new MultiTenantRelationalDao<>(sessionFactories, RelationalEntity.class,
        this.shardCalculator,
        shardingOptions, shardInfoProvider, observer);
    relationalWithAIDao = new MultiTenantRelationalDao<>(sessionFactories,
        RelationalEntityWithAIKey.class,
        this.shardCalculator,
        shardingOptions, shardInfoProvider, observer);
  }

  @AfterEach
  public void after() {
    sessionFactories.forEach((tenantId, sessionFactory) -> sessionFactory.forEach(
        SessionFactory::close));
  }

  @Test
  public void testBulkSave() throws Exception {
    String key = "testPhone";
    RelationalEntity entityOne = RelationalEntity.builder()
        .keyOne("1")
        .val("abcd")
        .build();
    RelationalEntity entityTwo = RelationalEntity.builder()
        .keyOne("2")
        .val("abcd")
        .build();
    relationalDao.saveAll("TENANT1", key, Lists.newArrayList(entityOne, entityTwo));
    relationalDao.saveAll("TENANT1", key, Lists.newArrayList(entityOne, entityTwo));
    List<RelationalEntity> entities = relationalDao.select("TENANT1", key,
        DetachedCriteria.forClass(RelationalEntity.class),
        0,
        10);
    assertEquals(2, entities.size());

  }

  @Test
  public void testCreateOrUpdate() throws Exception {
    val saved = relationalWithAIDao.createOrUpdate("TENANT1", "parent",
            DetachedCriteria.forClass(RelationalEntityWithAIKey.class)
                .add(Property.forName("key").eq("testId")),
            e -> e.setVal("Some Other Text"),
            () -> RelationalEntityWithAIKey.builder()
                .keyOne("testId")
                .val("Some New Text")
                .build())
        .orElse(null);
    assertNotNull(saved);
    assertEquals("Some New Text", saved.getVal());

    val updated = relationalWithAIDao.createOrUpdate("TENANT1", "parent",
            DetachedCriteria.forClass(RelationalEntityWithAIKey.class)
                .add(Property.forName("key").eq("testId")),
            e -> e.setVal("Some Other Text"),
            () -> RelationalEntityWithAIKey.builder()
                .keyOne("testId")
                .val("Some New Text")
                .build())
        .orElse(null);
    ;
    assertNotNull(updated);
    assertEquals(saved.getId(), updated.getId());
    assertEquals("Some Other Text", updated.getVal());
  }

  @Test
  public void testUpdateUsingQuery() throws Exception {
    val relationalKey = UUID.randomUUID().toString();

    val entityOne = RelationalEntity.builder()
        .keyOne("1")
        .keyTwo("1")
        .val(UUID.randomUUID().toString())
        .build();
    relationalDao.save("TENANT1", relationalKey, entityOne);

    val entityTwo = RelationalEntity.builder()
        .keyOne("2")
        .keyTwo("2")
        .val(UUID.randomUUID().toString())
        .build();
    relationalDao.save("TENANT1", relationalKey, entityTwo);

    val entityThree = RelationalEntity.builder()
        .keyOne("3")
        .keyTwo("2")
        .val(UUID.randomUUID().toString())
        .build();
    relationalDao.save("TENANT1", relationalKey, entityThree);

    val newValue = UUID.randomUUID().toString();
    int rowsUpdated = relationalDao.updateUsingQuery("TENANT1", relationalKey,
        UpdateOperationMeta.builder()
            .queryName("testUpdateUsingKeyTwo")
            .params(ImmutableMap.of("keyTwo", "2",
                "val", newValue))
            .build()
    );
    assertEquals(2, rowsUpdated);

    val persistedEntityTwo = relationalDao.get("TENANT1", relationalKey, "2").orElse(null);
    assertNotNull(persistedEntityTwo);
    assertEquals(newValue, persistedEntityTwo.getVal());

    val persistedEntityThree = relationalDao.get("TENANT1", relationalKey, "3").orElse(null);
    assertNotNull(persistedEntityThree);
    assertEquals(newValue, persistedEntityThree.getVal());


  }

  @Test
  public void testUpdateUsingQueryNoRowUpdated() throws Exception {
    val relationalKey = UUID.randomUUID().toString();

    val entityOne = RelationalEntity.builder()
        .keyOne("1")
        .keyTwo("1")
        .val(UUID.randomUUID().toString())
        .build();
    relationalDao.save("TENANT1", relationalKey, entityOne);

    val entityTwo = RelationalEntity.builder()
        .keyOne("2")
        .keyTwo("2")
        .val(UUID.randomUUID().toString())
        .build();
    relationalDao.save("TENANT1", relationalKey, entityTwo);

    val entityThree = RelationalEntity.builder()
        .keyOne("3")
        .keyTwo("2")
        .val(UUID.randomUUID().toString())
        .build();
    relationalDao.save("TENANT1", relationalKey, entityThree);

    val newValue = UUID.randomUUID().toString();
    int rowsUpdated = relationalDao.updateUsingQuery("TENANT1", relationalKey,
        UpdateOperationMeta.builder()
            .queryName("testUpdateUsingKeyTwo")
            .params(ImmutableMap.of("keyTwo",
                UUID.randomUUID().toString(),
                "val",
                newValue))
            .build()
    );
    assertEquals(0, rowsUpdated);

    val persistedEntityOne = relationalDao.get("TENANT1", relationalKey, "1").orElse(null);
    assertNotNull(persistedEntityOne);
    assertEquals(entityOne.getVal(), persistedEntityOne.getVal());

    val persistedEntityTwo = relationalDao.get("TENANT1", relationalKey, "2").orElse(null);
    assertNotNull(persistedEntityTwo);
    assertEquals(entityTwo.getVal(), persistedEntityTwo.getVal());

    val persistedEntityThree = relationalDao.get("TENANT1", relationalKey, "3").orElse(null);
    assertNotNull(persistedEntityThree);
    assertEquals(entityThree.getVal(), persistedEntityThree.getVal());
  }

  @Test
  public void testSaveWithInterceptors() throws Exception {
    val relationalKey = UUID.randomUUID().toString();

    val entityOne = RelationalEntity.builder()
        .keyOne("1")
        .keyTwo("1")
        .val(UUID.randomUUID().toString())
        .build();
    MDC.clear();
    relationalDao.save("TENANT1", relationalKey, entityOne);
    InterceptorTestUtil.validateThreadLocal(RelationalDao.class.getCanonicalName(), RelationalEntity.class);
  }

  @Test
  public void testScrolling() {
    val ids = new HashSet<String>();
    IntStream.range(1, 1_000)
        .forEach(i -> {
          try {
            val id = Integer.toString(i);
            ids.add(id);
            relationalDao.save("TENANT1", UUID.randomUUID().toString(),
                RelationalEntity.builder()
                    .keyOne(id)
                    .val("abcd" + i)
                    .build());
          } catch (Exception e) {
            throw new RuntimeException(e);
          }
        });
    {
      var nextPtr = (ScrollResult<RelationalEntity>) null;
      val receivedIds = new HashSet<String>();

      do {
        nextPtr = relationalDao.scrollUp("TENANT1",
            DetachedCriteria.forClass(RelationalEntity.class),
            null == nextPtr ? null : nextPtr.getPointer(), 5, "key");
        nextPtr.getResult().forEach(e -> receivedIds.add(e.getKeyOne()));
      }
      while (!nextPtr.getResult().isEmpty());
      assertEquals(ids, receivedIds);
    }
    {
      var nextPtr = (ScrollResult<RelationalEntity>) null;
      val receivedIds = new HashSet<String>();

      do {
        nextPtr = relationalDao.scrollDown("TENANT1",
            DetachedCriteria.forClass(RelationalEntity.class),
            null == nextPtr ? null : nextPtr.getPointer(), 5, "key");
        nextPtr.getResult().forEach(e -> receivedIds.add(e.getKeyOne()));
      }
      while (!nextPtr.getResult().isEmpty());
      assertEquals(ids, receivedIds);
    }
  }

  @Test
  public void testMultiShardRun() {
    val ids = new HashSet<String>();
    IntStream.range(1, 1_000)
        .forEach(i -> {
          try {
            val id = Integer.toString(i);
            ids.add(id);
            relationalDao.save("TENANT1", UUID.randomUUID().toString(),
                RelationalEntity.builder()
                    .keyOne(id)
                    .val("abcd" + i)
                    .build());
          } catch (Exception e) {
            throw new RuntimeException(e);
          }
        });
    assertEquals(ids,
        relationalDao.run("TENANT1", DetachedCriteria.forClass(RelationalEntity.class))
            .values()
            .stream()
            .flatMap(Collection::stream)
            .map(v -> ((RelationalEntity) v).getKeyOne())
            .collect(Collectors.toSet()));
  }


  @Test
  public void testPersistenceAndQueryOnSameShard() throws Exception {
    val numOfRecords = 100;
    val relationalKeys = generateIdsInSameShard(numOfRecords, "TENANT1");
    for (String relationalKey : relationalKeys) {
      relationalDao.save("TENANT1", relationalKey, RelationalEntity.builder()
          .keyOne(relationalKey)
          .keyTwo(relationalKey)
          .val(UUID.randomUUID().toString())
          .build());
    }

        /* Test that the same shard has all the records by selecting any key at random and using it to fetch all
         records */

    val randomRelationalKey = relationalKeys.get(RandomUtils.nextInt(0, relationalKeys.size()));
    long count = relationalDao.count("TENANT1", randomRelationalKey,
        (queryRoot, query, criteriaBuilder) -> {
        });
    Assertions.assertEquals(numOfRecords, count);

        /*
        Basic filter test to check equality constraint through QuerySpec
         */
    List<RelationalEntity> queryResultOne = relationalDao.select("TENANT1", randomRelationalKey,
        (queryRoot, query, criteriaBuilder)
            -> query.where(criteriaBuilder.equal(queryRoot.get("keyTwo"), randomRelationalKey)), 0,
        numOfRecords);
    Assertions.assertEquals(1, queryResultOne.size());

        /*
        Basic filter test to check in-equality constraint through QuerySpec
         */
    List<RelationalEntity> queryResultTwo = relationalDao.select("TENANT1", randomRelationalKey,
        (queryRoot, query, criteriaBuilder)
            -> query.where(criteriaBuilder.notEqual(queryRoot.get("keyTwo"), randomRelationalKey)),
        0, numOfRecords);
    Assertions.assertEquals(numOfRecords - 1, queryResultTwo.size());

        /*
        Basic filter test to check multiple predicates through QuerySpec
         */
    List<RelationalEntity> queryResultThree = relationalDao.select("TENANT1", randomRelationalKey,
        (queryRoot, query, criteriaBuilder) ->
            query.where(
                criteriaBuilder.and(
                    criteriaBuilder.equal(queryRoot.get("key"), randomRelationalKey),
                    criteriaBuilder.notEqual(queryRoot.get("keyTwo"), randomRelationalKey)
                )
            ), 0, numOfRecords);
    Assertions.assertEquals(0, queryResultThree.size());
  }


  private List<String> generateIdsInSameShard(final int numIdsToBeGenerated, String tenantId) {
    int expectedShardIndex = RandomUtils.nextInt(0, sessionFactories.get(tenantId).size());
    return generateIdsInSameShard(expectedShardIndex, numIdsToBeGenerated, tenantId);
  }

  private List<String> generateIdsInSameShard(int expectedShardIndex, int numIdsToBeGenerated, String tenantId) {
    return IntStream.range(0, numIdsToBeGenerated)
        .mapToObj(value -> {
          while (true) {
            String id = UUID.randomUUID().toString();
            if (shardCalculator.shardId(tenantId, id) == expectedShardIndex) {
              return id;
            }
          }
        })
        .collect(Collectors.toList());
  }
}
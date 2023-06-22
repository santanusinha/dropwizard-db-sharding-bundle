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

import io.appform.dropwizard.sharding.caching.LookupCache;
import io.appform.dropwizard.sharding.caching.RelationalCache;
import io.appform.dropwizard.sharding.config.ShardedHibernateFactory;
import io.appform.dropwizard.sharding.dao.LookupDao;
import io.appform.dropwizard.sharding.dao.RelationalDao;
import io.appform.dropwizard.sharding.dao.testdata.entities.TestEntity;
import io.appform.dropwizard.sharding.dao.testdata.multi.MultiPackageTestEntity;
import io.appform.dropwizard.sharding.sharding.impl.ConsistentHashBucketIdExtractor;
import org.apache.commons.lang3.StringUtils;
import org.hibernate.criterion.DetachedCriteria;
import org.hibernate.criterion.Restrictions;
import org.junit.Assert;
import org.junit.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class BalancedDbShardingBundleWithMultipleClassPath extends DBShardingBundleTestBase {


    public static final LookupCache<TestEntity> LOOKUP_CACHE = new LookupCache<TestEntity>() {

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
    };
    private static final RelationalCache<TestEntity> RELATIONAL_CACHE = new RelationalCache<TestEntity>() {
        private Map<String, Object> cache = new HashMap<>();
        @Override
        public void put(String parentKey, Object key, TestEntity entity) {
            cache.put(StringUtils.join(parentKey, key, ':'), entity);
        }

        @Override
        public void put(String parentKey, List<TestEntity> entities) {
            cache.put(parentKey, entities);
        }

        @Override
        public void put(String parentKey, int first, int numResults, List<TestEntity> entities) {
            cache.put(StringUtils.join(parentKey, first, numResults,':'), entities);
        }

        @Override
        public boolean exists(String parentKey, Object key) {
            return cache.containsKey(StringUtils.join(parentKey, key, ':'));
        }

        @Override
        public TestEntity get(String parentKey, Object key) {
            return (TestEntity) cache.get(StringUtils.join(parentKey, key,':'));
        }

        @Override
        public List<TestEntity> select(String parentKey) {
            return (List<TestEntity>) cache.get(parentKey);
        }

        @Override
        public List<TestEntity> select(String parentKey, int first, int numResults) {
            return (List<TestEntity>)cache.get(StringUtils.join(parentKey, first, numResults, ':'));
        }
    };

    @Override
    protected DBShardingBundleBase<TestConfig> getBundle() {
        return new BalancedDBShardingBundle<TestConfig>("io.appform.dropwizard.sharding.dao.testdata.entities", "io.appform.dropwizard.sharding.dao.testdata.multi") {
            @Override
            protected ShardedHibernateFactory getConfig(TestConfig config) {
                return testConfig.getShards();
            }

        };
    }


    @Test
    public void testMultiPackage() throws Exception {

        DBShardingBundleBase<TestConfig> bundle = getBundle();

        bundle.initialize(bootstrap);
        bundle.initBundles(bootstrap);
        bundle.runBundles(testConfig, environment);
        bundle.run(testConfig, environment);
        LookupDao<MultiPackageTestEntity> lookupDao = bundle.createParentObjectDao(MultiPackageTestEntity.class);

        MultiPackageTestEntity multiPackageTestEntity = MultiPackageTestEntity.builder()
                .text("Testing multi package scanning")
                .lookup("123")
                .build();

        Optional<MultiPackageTestEntity> saveMultiPackageTestEntity = lookupDao.save(multiPackageTestEntity);
        Assert.assertEquals(multiPackageTestEntity.getText(), saveMultiPackageTestEntity.get().getText());

        Optional<MultiPackageTestEntity> fetchedMultiPackageTestEntity = lookupDao.get(multiPackageTestEntity.getLookup());
        Assert.assertEquals(saveMultiPackageTestEntity.get().getText(), fetchedMultiPackageTestEntity.get().getText());

        LookupDao<TestEntity> testEntityLookupDao = bundle.createParentObjectDao(TestEntity.class);

        TestEntity testEntity = TestEntity.builder()
                .externalId("E123")
                .text("Test Second Package")
                .build();
        Optional<TestEntity> savedTestEntity = testEntityLookupDao.save(testEntity);
        Assert.assertEquals(testEntity.getText(), savedTestEntity.get().getText());

        Optional<TestEntity> fetchedTestEntity = testEntityLookupDao.get(testEntity.getExternalId());
        Assert.assertEquals(savedTestEntity.get().getText(), fetchedTestEntity.get().getText());

        // Cacheble
        LookupDao<TestEntity> testEntityLookupDaoCacheble = bundle.createParentObjectDao(TestEntity.class, LOOKUP_CACHE);
        Optional<TestEntity> savedTestEntityCacheble = testEntityLookupDaoCacheble.save(testEntity);
        Assert.assertEquals(testEntity.getText(), savedTestEntityCacheble.get().getText());

        Optional<TestEntity> fetchTestEntityCacheble = testEntityLookupDaoCacheble.get(testEntity.getExternalId());
        Assert.assertEquals(savedTestEntityCacheble.get().getText(), fetchTestEntityCacheble.get().getText());

        // Bucketizer
        LookupDao<TestEntity> testEntityLookupDaoBucketizer = bundle.createParentObjectDao(TestEntity.class, new ConsistentHashBucketIdExtractor<>(bundle.getShardManager()));
        Optional<TestEntity> savedEntityLookupDaoBucketizer = testEntityLookupDaoBucketizer.save(testEntity);
        Assert.assertEquals(testEntity.getText(), savedEntityLookupDaoBucketizer.get().getText());

        Optional<TestEntity> fetchEntityLookupDaoBucketizer = testEntityLookupDaoBucketizer.get(testEntity.getExternalId());
        Assert.assertEquals(savedEntityLookupDaoBucketizer.get().getText(), fetchEntityLookupDaoBucketizer.get().getText());

        // Cacheble + Bucketizer
        LookupDao<TestEntity> testEntityLookupDaoCachebleAndBucketizer = bundle.createParentObjectDao(TestEntity.class, new ConsistentHashBucketIdExtractor<>(bundle.getShardManager()), LOOKUP_CACHE);
        Optional<TestEntity> savedEntityLookupDaoCachebleAndBucketizer = testEntityLookupDaoCachebleAndBucketizer.save(testEntity);
        Assert.assertEquals(testEntity.getText(), savedEntityLookupDaoCachebleAndBucketizer.get().getText());

        Optional<TestEntity> fetchEntityLookupDaoCachebleAndBucketizer = testEntityLookupDaoCachebleAndBucketizer.get(testEntity.getExternalId());
        Assert.assertEquals(savedEntityLookupDaoCachebleAndBucketizer.get().getText(), fetchEntityLookupDaoCachebleAndBucketizer.get().getText());

        // Relation DAO
        DetachedCriteria criteria = DetachedCriteria.forClass(TestEntity.class)
                .add(Restrictions.eq("externalId", testEntity.getExternalId()));

        // Basic
        RelationalDao<TestEntity> testEntityRelationalDao = bundle.createRelatedObjectDao(TestEntity.class);
        Optional<TestEntity> savedEntityRelationDao = testEntityRelationalDao.save(testEntity.getExternalId(), testEntity);
        Assert.assertEquals(testEntity.getText(), savedEntityRelationDao.get().getText());

        List<TestEntity> fetchRelationList = testEntityRelationalDao.select(testEntity.getExternalId(),criteria, 0, Integer.MAX_VALUE);
        Assert.assertEquals(1, fetchRelationList.size());
        Assert.assertEquals(savedEntityRelationDao.get().getText(), fetchRelationList.get(0).getText());

        // Cacheble
        RelationalDao<TestEntity> testEntityRelationalDaoCacheble = bundle.createRelatedObjectDao(TestEntity.class, RELATIONAL_CACHE);
        Optional<TestEntity> savedEntityRelationDaoCacheble = testEntityRelationalDaoCacheble.save(testEntity.getExternalId(), testEntity);
        Assert.assertEquals(testEntity.getText(), savedEntityRelationDaoCacheble.get().getText());

        List<TestEntity> fetchRelationListCacheble = testEntityRelationalDaoCacheble.select(testEntity.getExternalId(),criteria, 0, Integer.MAX_VALUE);
        Assert.assertEquals(1, fetchRelationListCacheble.size());
        Assert.assertEquals(savedEntityRelationDaoCacheble.get().getText(), fetchRelationListCacheble.get(0).getText());

        // Cacheble
        RelationalDao<TestEntity> testEntityRelationalDaoBucketizer = bundle.createRelatedObjectDao(TestEntity.class,
                new ConsistentHashBucketIdExtractor<>(bundle.getShardManager()));
        Optional<TestEntity> savedEntityRelationDaoBucketizer = testEntityRelationalDaoBucketizer.save(testEntity.getExternalId(), testEntity);
        Assert.assertEquals(testEntity.getText(), savedEntityRelationDaoBucketizer.get().getText());

        List<TestEntity> fetchRelationListBucketizer = testEntityRelationalDaoBucketizer.select(testEntity.getExternalId(),criteria, 0, Integer.MAX_VALUE);
        Assert.assertEquals(1, fetchRelationListBucketizer.size());
        Assert.assertEquals(savedEntityRelationDaoBucketizer.get().getText(), fetchRelationListBucketizer.get(0).getText());

        // Cacheble + Bucketizer
        RelationalDao<TestEntity> testEntityRelationalDaoCachebleAndBucketizer = bundle.createRelatedObjectDao(TestEntity.class,
                new ConsistentHashBucketIdExtractor<>(bundle.getShardManager()), RELATIONAL_CACHE);
        Optional<TestEntity> savedEntityRelationDaoCachebleAndBucketizer = testEntityRelationalDaoCachebleAndBucketizer.save(testEntity.getExternalId(), testEntity);
        Assert.assertEquals(testEntity.getText(), savedEntityRelationDaoCachebleAndBucketizer.get().getText());

        List<TestEntity> fetchRelationListCachebleAndBucketizer = testEntityRelationalDaoCachebleAndBucketizer.select(testEntity.getExternalId(),criteria, 0, Integer.MAX_VALUE);
        Assert.assertEquals(1, fetchRelationListCachebleAndBucketizer.size());
        Assert.assertEquals(savedEntityRelationDaoCachebleAndBucketizer.get().getText(), fetchRelationListCachebleAndBucketizer.get(0).getText());

    }
}

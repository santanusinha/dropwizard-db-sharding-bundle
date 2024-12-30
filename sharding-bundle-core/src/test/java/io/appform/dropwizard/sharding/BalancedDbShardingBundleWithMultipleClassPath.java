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
import io.appform.dropwizard.sharding.config.ShardedHibernateFactory;
import io.appform.dropwizard.sharding.dao.LookupDao;
import io.appform.dropwizard.sharding.dao.testdata.entities.TestEntity;
import io.appform.dropwizard.sharding.dao.testdata.multi.MultiPackageTestEntity;
import io.appform.dropwizard.sharding.sharding.impl.ConsistentHashBucketIdExtractor;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class BalancedDbShardingBundleWithMultipleClassPath extends DBShardingBundleTestBase {


    public static final LookupCache<TestEntity> CACHE_MANAGER = new LookupCache<TestEntity>() {

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
        assertEquals(multiPackageTestEntity.getText(), saveMultiPackageTestEntity.get().getText());

        Optional<MultiPackageTestEntity> fetchedMultiPackageTestEntity = lookupDao.get(multiPackageTestEntity.getLookup());
        assertEquals(saveMultiPackageTestEntity.get().getText(), fetchedMultiPackageTestEntity.get().getText());

        LookupDao<TestEntity> testEntityLookupDao = bundle.createParentObjectDao(TestEntity.class);

        TestEntity testEntity = TestEntity.builder()
                .externalId("E123")
                .text("Test Second Package")
                .build();
        Optional<TestEntity> savedTestEntity = testEntityLookupDao.save(testEntity);
        assertEquals(testEntity.getText(), savedTestEntity.get().getText());

        Optional<TestEntity> fetchedTestEntity = testEntityLookupDao.get(testEntity.getExternalId());
        assertEquals(savedTestEntity.get().getText(), fetchedTestEntity.get().getText());

        // Cacheble
        LookupDao<TestEntity> testEntityLookupDaoCacheble = bundle.createParentObjectDao(TestEntity.class, CACHE_MANAGER);
        Optional<TestEntity> savedTestEntityCacheble = testEntityLookupDaoCacheble.save(testEntity);
        assertEquals(testEntity.getText(), savedTestEntityCacheble.get().getText());

        Optional<TestEntity> fetchTestEntityCacheble = testEntityLookupDaoCacheble.get(testEntity.getExternalId());
        assertEquals(savedTestEntityCacheble.get().getText(), fetchTestEntityCacheble.get().getText());

        // Bucketizer
        LookupDao<TestEntity> testEntityLookupDaoBucketizer = bundle.createParentObjectDao(TestEntity.class, new ConsistentHashBucketIdExtractor<>(bundle.getShardManager()));
        Optional<TestEntity> savedEntityLookupDaoBucketizer = testEntityLookupDaoBucketizer.save(testEntity);
        assertEquals(testEntity.getText(), savedEntityLookupDaoBucketizer.get().getText());

        Optional<TestEntity> fetchEntityLookupDaoBucketizer = testEntityLookupDaoBucketizer.get(testEntity.getExternalId());
        assertEquals(savedEntityLookupDaoBucketizer.get().getText(), fetchEntityLookupDaoBucketizer.get().getText());

        // Cacheble + Bucketizer
        LookupDao<TestEntity> testEntityLookupDaoCachebleAndBucketizer = bundle.createParentObjectDao(TestEntity.class, new ConsistentHashBucketIdExtractor<>(bundle.getShardManager()), CACHE_MANAGER);
        Optional<TestEntity> savedEntityLookupDaoCachebleAndBucketizer = testEntityLookupDaoCachebleAndBucketizer.save(testEntity);
        assertEquals(testEntity.getText(), savedEntityLookupDaoCachebleAndBucketizer.get().getText());

        Optional<TestEntity> fetchEntityLookupDaoCachebleAndBucketizer = testEntityLookupDaoCachebleAndBucketizer.get(testEntity.getExternalId());
        assertEquals(savedEntityLookupDaoCachebleAndBucketizer.get().getText(), fetchEntityLookupDaoCachebleAndBucketizer.get().getText());
    }
}

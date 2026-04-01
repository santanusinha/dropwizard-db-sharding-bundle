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
import io.appform.dropwizard.sharding.config.MultiTenantShardedHibernateFactory;
import io.appform.dropwizard.sharding.dao.MultiTenantCacheableLookupDao;
import io.appform.dropwizard.sharding.dao.MultiTenantLookupDao;
import io.appform.dropwizard.sharding.dao.testdata.entities.TestEntity;
import io.appform.dropwizard.sharding.dao.testdata.multi.MultiPackageTestEntity;
import io.appform.dropwizard.sharding.sharding.InMemoryLocalShardBlacklistingStore;
import io.appform.dropwizard.sharding.sharding.ShardBlacklistingStore;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class MultiTenantBalancedDbShardingBundleWithMultipleClassPath extends MultiTenantDBShardingBundleTestBase {


    public static final Map<String, LookupCache<TestEntity>> CACHE_MANAGER = Map.of("TENANT1", new LookupCache<TestEntity>() {

        private Map<String, TestEntity> cache = new HashMap<>();

        @Override
        public void put(String key, TestEntity entity) {
            cache.put(key, entity);
        }

        @Override
        public TestEntity get(String key) {
            if (!cache.containsKey(key)) {
                return null;
            }
            return cache.get(key);
        }

        @Override
        public boolean exists(String key) {
            return cache.containsKey(key);
        }
    }, "TENANT2", new LookupCache<TestEntity>() {

        private Map<String, TestEntity> cache = new HashMap<>();

        @Override
        public void put(String key, TestEntity entity) {
            cache.put(key, entity);
        }

        @Override
        public TestEntity get(String key) {
            if (!cache.containsKey(key)) {
                return null;
            }
            return cache.get(key);
        }

        @Override
        public boolean exists(String key) {
            return cache.containsKey(key);
        }
    });

    @Override
    protected MultiTenantDBShardingBundleBase<TestConfig> getBundle() {
        return new MultiTenantBalancedDBShardingBundle<TestConfig>("io.appform.dropwizard.sharding.dao.testdata.entities", "io.appform.dropwizard.sharding.dao.testdata.multi") {
            @Override
            protected MultiTenantShardedHibernateFactory getConfig(TestConfig config) {
                return testConfig.getShards();
            }

            @Override
            protected ShardBlacklistingStore getBlacklistingStore() {
                return new InMemoryLocalShardBlacklistingStore();
            }

        };
    }


    @Test
    public void testMultiPackage() throws Exception {

        MultiTenantDBShardingBundleBase<TestConfig> bundle = getBundle();

        bundle.initialize(bootstrap);
        bundle.run(testConfig, environment);
        MultiTenantLookupDao<MultiPackageTestEntity> lookupDao = bundle.createParentObjectDao(MultiPackageTestEntity.class);

        MultiPackageTestEntity multiPackageTestEntity = MultiPackageTestEntity.builder()
                .text("Testing multi package scanning")
                .lookup("123")
                .build();

        Optional<MultiPackageTestEntity> saveMultiPackageTestEntity = lookupDao.save("TENANT1", multiPackageTestEntity);
        assertEquals(multiPackageTestEntity.getText(), saveMultiPackageTestEntity.get().getText());

        Optional<MultiPackageTestEntity> fetchedMultiPackageTestEntity = lookupDao.get("TENANT1", multiPackageTestEntity.getLookup());
        assertEquals(saveMultiPackageTestEntity.get().getText(), fetchedMultiPackageTestEntity.get().getText());

        MultiTenantLookupDao<TestEntity> testEntityLookupDao = bundle.createParentObjectDao(TestEntity.class);

        TestEntity testEntity = TestEntity.builder()
                .externalId("E123")
                .text("Test Second Package")
                .build();
        Optional<TestEntity> savedTestEntity = testEntityLookupDao.save("TENANT2", testEntity);
        assertEquals(testEntity.getText(), savedTestEntity.get().getText());

        Optional<TestEntity> fetchedTestEntity = testEntityLookupDao.get("TENANT2", testEntity.getExternalId());
        assertEquals(savedTestEntity.get().getText(), fetchedTestEntity.get().getText());

        // Cacheble
        MultiTenantCacheableLookupDao<TestEntity> testEntityLookupDaoCacheble = bundle.createParentObjectDao(TestEntity.class, CACHE_MANAGER);
        Optional<TestEntity> savedTestEntityCacheble = testEntityLookupDaoCacheble.save("TENANT1", testEntity);
        assertEquals(testEntity.getText(), savedTestEntityCacheble.get().getText());

        Optional<TestEntity> fetchTestEntityCacheble = testEntityLookupDaoCacheble.get("TENANT1", testEntity.getExternalId());
        assertEquals(savedTestEntityCacheble.get().getText(), fetchTestEntityCacheble.get().getText());

    }
}

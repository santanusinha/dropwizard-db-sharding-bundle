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

import io.appform.dropwizard.sharding.config.MultiTenantShardedHibernateFactory;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class MultiTenantBalancedDbShardingBundleWithNoopBlacklistingStoreTest extends MultiTenantBundleBasedTestBase {

    @Override
    protected MultiTenantDBShardingBundleBase<TestConfig> getBundle() {
        return new MultiTenantBalancedDBShardingBundle<TestConfig>("io.appform.dropwizard.sharding.dao.testdata.entities", "io.appform.dropwizard.sharding.dao.testdata.multi") {
            @Override
            protected MultiTenantShardedHibernateFactory getConfig(TestConfig config) {
                return testConfig.getShards();
            }
        };
    }

    @Test
    public void testHealthCheck() throws Exception {
        MultiTenantDBShardingBundleBase<TestConfig> bundle = getBundle();
        bundle.initialize(bootstrap);
        testConfig.getShards().getTenants().forEach((tenant, factory)
                -> factory.getShardingOptions().setSkipNativeHealthcheck(false));
        bundle.run(testConfig, environment);
        //one for each tenant
        assertEquals(2, bundle.healthStatus().size());
        //2 shards for each tenant
        assertEquals(2, bundle.healthStatus().get("TENANT1").size());
        assertEquals(2, bundle.healthStatus().get("TENANT2").size());
    }
}

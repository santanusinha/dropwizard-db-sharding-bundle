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
import io.appform.dropwizard.sharding.dao.testdata.entities.Order;
import io.appform.dropwizard.sharding.dao.testdata.entities.OrderItem;
import io.appform.dropwizard.sharding.dao.testdata.entities.TestEncryptedEntity;

public class MultiTenantLegacyDBShardingBundleWithEntityTest extends MultiTenantDBShardingBundleTestBase {

    @Override
    protected MultiTenantDBShardingBundleBase<TestConfig> getBundle() {
        return new MultiTenantDBShardingBundle<TestConfig>(Order.class, OrderItem.class,
            TestEncryptedEntity.class) {
            @Override
            protected MultiTenantShardedHibernateFactory getConfig(TestConfig config) {
                config.getShards().getTenants().forEach((tenant, factory) -> {
                    factory.getShardingOptions().setEncryptionSupportEnabled(true);
                    factory.getShardingOptions().setEncryptionAlgorithm("PBEWithHmacSHA256AndAES_256");
                    factory.getShardingOptions().setEncryptionPassword("eBhjVFN5LtP6hpwzWdjSkBQg");
                    factory.getShardingOptions().setEncryptionIv("8SCaDgvH5xMD3KFE");
                });
                return testConfig.getShards();
            }
        };
    }
}

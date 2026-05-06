/*
 * Copyright 2024 Santanu Sinha <santanu.sinha@gmail.com>
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

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Maps;
import io.appform.dropwizard.sharding.config.ShardedHibernateFactory;
import io.appform.dropwizard.sharding.dao.testdata.entities.Order;
import io.appform.dropwizard.sharding.dao.testdata.entities.OrderItem;
import io.dropwizard.db.DataSourceFactory;

import java.util.Map;

public class SingleShardDBShardingBundleTest extends DBShardingBundleTestBase {

    private DataSourceFactory createSingleConfig() {
        Map<String, String> properties = Maps.newHashMap();
        properties.put("hibernate.dialect", "org.hibernate.dialect.H2Dialect");
        properties.put("hibernate.hbm2ddl.auto", "create");

        DataSourceFactory shard = new DataSourceFactory();
        shard.setDriverClass("org.h2.Driver");
        shard.setUrl("jdbc:h2:mem:single");
        shard.setValidationQuery("select 1");
        shard.setProperties(properties);

        return shard;
    }

    @Override
    protected DBShardingBundleBase<TestConfig> getBundle() {
        testConfig.getShards().setShards(ImmutableList.of(createSingleConfig()));
        return new DBShardingBundle<TestConfig>(Order.class, OrderItem.class) {
            @Override
            protected ShardedHibernateFactory getConfig(TestConfig config) {
                return testConfig.getShards();
            }
        };
    }
}

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

import io.appform.dropwizard.sharding.sharding.BalancedShardManager;
import io.appform.dropwizard.sharding.sharding.ShardBlacklistingStore;
import io.appform.dropwizard.sharding.sharding.ShardManager;
import io.dropwizard.Configuration;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * A dropwizard bundle that provides sharding over normal RDBMS.
 */
@Slf4j
public abstract class BalancedDBShardingBundle<T extends Configuration> extends DBShardingBundleBase<T> {

    public BalancedDBShardingBundle(
            String dbNamespace,
            Class<?> entity,
            Class<?>... entities) {
        super(dbNamespace, entity, entities);
    }

    public BalancedDBShardingBundle(String dbNamespace, List<String> classPathPrefixList) {
        super(dbNamespace, classPathPrefixList);
    }

    public BalancedDBShardingBundle(Class<?> entity, Class<?>... entities) {
        super(entity, entities);
    }

    public BalancedDBShardingBundle(String... classPathPrefixes) {
        super(classPathPrefixes);
    }

    @Override
    protected ShardManager createShardManager(int numShards, ShardBlacklistingStore shardBlacklistingStore) {
        return new BalancedShardManager(numShards, shardBlacklistingStore);
    }

}

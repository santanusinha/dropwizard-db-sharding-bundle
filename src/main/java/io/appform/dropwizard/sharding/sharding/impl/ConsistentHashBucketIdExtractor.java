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

package io.appform.dropwizard.sharding.sharding.impl;

import com.google.common.hash.Hashing;
import io.appform.dropwizard.sharding.DBShardingBundleBase;
import io.appform.dropwizard.sharding.sharding.BucketIdExtractor;
import io.appform.dropwizard.sharding.sharding.ShardManager;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Generates bucket id on the basis of murmur128 of the key.
 */
public class ConsistentHashBucketIdExtractor<T> implements BucketIdExtractor<T> {

    private final Map<String, ShardManager> shardManagers;

    public ConsistentHashBucketIdExtractor(ShardManager shardManager) {
        this(Map.of(DBShardingBundleBase.DEFAULT_NAMESPACE, shardManager));
    }

    public ConsistentHashBucketIdExtractor(Map<String, ShardManager> shardManagers) {
        this.shardManagers = shardManagers;
    }


    @Override
    public int bucketId(T id) {
        int hashKey = Hashing.murmur3_128().hashString(id.toString(), StandardCharsets.UTF_8).asInt();
        hashKey *= hashKey < 0 ? -1 : 1;
        return hashKey % shardManagers.get(DBShardingBundleBase.DEFAULT_NAMESPACE).numBuckets();
    }

    @Override
    public int bucketId(String tenantId, T id) {
        int hashKey = Hashing.murmur3_128().hashString(id.toString(), StandardCharsets.UTF_8).asInt();
        hashKey *= hashKey < 0 ? -1 : 1;
        return hashKey % shardManagers.get(tenantId).numBuckets();
    }
}

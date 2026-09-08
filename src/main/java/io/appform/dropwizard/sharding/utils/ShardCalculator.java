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

package io.appform.dropwizard.sharding.utils;

import io.appform.dropwizard.sharding.DBShardingBundleBase;
import io.appform.dropwizard.sharding.sharding.BucketIdExtractor;
import io.appform.dropwizard.sharding.sharding.ShardManager;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.Objects;

/**
 * Utility class for calculating shards.
 */
@Slf4j
public class ShardCalculator<T> {

    private final String tenantId;
    private final Map<String, ShardManager> shardManagers;
    private final BucketIdExtractor<T> extractor;

    @Deprecated
    public ShardCalculator(Map<String, ShardManager> shardManagers, BucketIdExtractor<T> extractor) {
        this.tenantId = DBShardingBundleBase.DEFAULT_NAMESPACE;
        this.shardManagers = shardManagers;
        this.extractor = extractor;
    }

    public ShardCalculator(
            String tenantId,
            ShardManager shardManager,
            BucketIdExtractor<T> extractor) {
        this.tenantId = Objects.requireNonNull(tenantId, "tenantId");
        this.shardManagers = Map.of(
                tenantId,
                Objects.requireNonNull(shardManager, "shardManager"));
        this.extractor = Objects.requireNonNull(extractor, "extractor");
    }

    public int shardId(T key) {
        return shardId(tenantId, key);
    }

    @Deprecated
    public int shardId(String tenantId, T key) {
        int bucketId = extractor.bucketId(tenantId, key);
        return shardManagers.get(tenantId).shardForBucket(bucketId);
    }

    public boolean isOnValidShard(T key) {
        return isOnValidShard(tenantId, key);
    }

    @Deprecated
    public boolean isOnValidShard(String tenantId, T key) {
        int bucketId = extractor.bucketId(tenantId, key);
        return shardManagers.get(tenantId).isMappedToValidShard(bucketId);
    }
}

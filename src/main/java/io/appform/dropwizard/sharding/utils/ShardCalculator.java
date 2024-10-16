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

import io.appform.dropwizard.sharding.sharding.BucketIdExtractor;
import io.appform.dropwizard.sharding.sharding.ShardManager;
import lombok.extern.slf4j.Slf4j;

import java.util.Collections;
import java.util.Map;

/**
 * Utility class for calculating shards.
 */
@Slf4j
public class ShardCalculator<T> {

    private final ShardManager shardManager;
    private final Map<String, ShardManager> shardManagers;
    private final BucketIdExtractor<T> extractor;

    public ShardCalculator(ShardManager shardManager, BucketIdExtractor<T> extractor) {
        this.shardManager = shardManager;
        this.shardManagers = Collections.emptyMap();
        this.extractor = extractor;
    }

    public ShardCalculator(Map<String, ShardManager> shardManagers, BucketIdExtractor<T> extractor) {
        this.shardManagers = shardManagers;
        this.shardManager = null;
        this.extractor = extractor;
    }

    public int shardId(T key) {
        int bucketId = extractor.bucketId(key);
        return shardManager.shardForBucket(bucketId);
    }

    public int shardId(String tenantId, T key) {
        int bucketId = extractor.bucketId(tenantId, key);
        return shardManagers.get(tenantId).shardForBucket(bucketId);
    }

    public boolean isOnValidShard(T key) {
        int bucketId = extractor.bucketId(key);
        return shardManager.isMappedToValidShard(bucketId);
    }
}

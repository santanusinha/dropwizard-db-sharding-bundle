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

import io.appform.dropwizard.sharding.sharding.BucketIdExtractor;
import io.appform.dropwizard.sharding.sharding.ShardManager;
import io.appform.dropwizard.sharding.sharding.impl.ConsistentHashBucketIdExtractor;

import java.util.Map;
import java.util.Objects;

final class ConsistentHashShardCalculator implements ShardCalculator {

    private final String tenantId;
    private final ShardManager shardManager;
    private final BucketIdExtractor<String> extractor;

    ConsistentHashShardCalculator(String tenantId, ShardManager shardManager) {
        this.tenantId = Objects.requireNonNull(tenantId, "tenantId");
        this.shardManager = Objects.requireNonNull(shardManager, "shardManager");
        this.extractor = new ConsistentHashBucketIdExtractor<>(Map.of(tenantId, shardManager));
    }

    @Override
    public String tenantId() {
        return tenantId;
    }

    @Override
    public int shardId(String key) {
        return shardManager.shardForBucket(bucketId(key));
    }

    @Override
    public boolean isOnValidShard(String key) {
        return shardManager.isMappedToValidShard(bucketId(key));
    }

    int bucketId(String key) {
        return extractor.bucketId(tenantId, key);
    }

    @Override
    public String toString() {
        return "ShardCalculator[tenant=" + tenantId + "]";
    }
}

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

import io.appform.dropwizard.sharding.sharding.ShardManager;

import java.util.function.ToIntFunction;

final class ShardCalculators {

    private ShardCalculators() {
        throw new AssertionError("No instances");
    }

    static ShardCalculator forTenant(String tenantId, ShardManager shardManager) {
        return new ConsistentHashShardCalculator(tenantId, shardManager);
    }

    static ToIntFunction<String> bucketIdFunction(ShardCalculator calculator) {
        if (calculator instanceof ConsistentHashShardCalculator consistentHash) {
            return consistentHash::bucketId;
        }
        throw new IllegalArgumentException("Unsupported shard calculator: " + calculator);
    }
}

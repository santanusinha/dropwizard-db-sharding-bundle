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

/**
 * Maps a sharding key to a shard for one tenant.
 * Instances are created and owned by the sharding bundle; obtain them from
 * {@link ShardCalculatorRegistry} or the bundle's {@code getShardCalculator} methods.
 */
public sealed interface ShardCalculator permits ConsistentHashShardCalculator {

    String tenantId();

    int shardId(String key);

    boolean isOnValidShard(String key);
}

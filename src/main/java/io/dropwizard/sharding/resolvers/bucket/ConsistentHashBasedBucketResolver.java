/*
 * Copyright 2016 Santanu Sinha <santanu.sinha@gmail.com>
 * Modifications copyright (C) 2018 Cleartax
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

package io.dropwizard.sharding.resolvers.bucket;

import com.google.common.hash.Hashing;
import io.dropwizard.sharding.resolvers.shard.HashBasedShardResolver;

import java.nio.charset.StandardCharsets;

public class ConsistentHashBasedBucketResolver<T> implements BucketResolver<T> {
    @Override
    public int resolve(T id) {
        int hashKey = Hashing.murmur3_128().hashString(id.toString(), StandardCharsets.UTF_8).asInt();
        hashKey *= hashKey < 0 ? -1 : 1;

        return hashKey % (HashBasedShardResolver.MAX_BUCKET + 1);
    }
}

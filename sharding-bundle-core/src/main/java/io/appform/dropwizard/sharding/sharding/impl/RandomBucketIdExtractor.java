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

import io.appform.dropwizard.sharding.sharding.BucketIdExtractor;
import io.appform.dropwizard.sharding.sharding.ShardManager;

import java.security.SecureRandom;
import java.util.Random;

/**
 * Generates a random bucket id.
 */
public class RandomBucketIdExtractor<T> implements BucketIdExtractor<T> {
    private Random random = new SecureRandom(Long.toBinaryString(System.currentTimeMillis()).getBytes());
    private final ShardManager shardManager;

    public RandomBucketIdExtractor(ShardManager shardManager) {
        this.shardManager = shardManager;
    }

    @Override
    public int bucketId(T key) {
        return random.nextInt(shardManager.numBuckets());
    }
}

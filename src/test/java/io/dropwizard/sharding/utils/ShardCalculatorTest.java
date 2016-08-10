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
package io.dropwizard.sharding.utils;


import io.dropwizard.sharding.sharding.ShardManager;
import io.dropwizard.sharding.sharding.impl.EncodedBucketIdExtractor;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class ShardCalculatorTest {
    private EncodedBucketIdExtractor encodedBucketIdExtractor = new EncodedBucketIdExtractor(4,3);
    private ShardManager shardManager = new ShardManager(16);

    @Test
    public void testShardId() throws Exception {
        assertEquals(ShardCalculator.shardId(shardManager, "shar001", encodedBucketIdExtractor), 0);
        assertEquals(ShardCalculator.shardId(shardManager, "shar003", encodedBucketIdExtractor), 0);
        assertEquals(ShardCalculator.shardId(shardManager, "shar070", encodedBucketIdExtractor), 1);
        assertEquals(ShardCalculator.shardId(shardManager, "shar130", encodedBucketIdExtractor), 2);
        assertEquals(ShardCalculator.shardId(shardManager, "shar999", encodedBucketIdExtractor), 15);
    }

}
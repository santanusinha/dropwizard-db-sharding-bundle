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

package io.appform.dropwizard.sharding.sharding;

import io.appform.dropwizard.sharding.exceptions.ShardBlacklistedException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

public class ShardManagerTest {

    @Test
    public void testShardForBucket() throws Exception {
        ShardManager shardManager = new LegacyShardManager(8);
        assertEquals(0, shardManager.shardForBucket(100));
    }

    @Test
    public void testBlacklisting() throws Exception {
        ShardManager shardManager = new LegacyShardManager(8);
        assertEquals(0, shardManager.shardForBucket(100));
        shardManager.blacklistShard(0);
        Thread.sleep(5000); //Sleeping for 5 seconds
        try {
            shardManager.shardForBucket(100);
            fail("Should have errored out");
        } catch (ShardBlacklistedException e) {

        }
        shardManager.unblacklistShard(0);
        Thread.sleep(5000); //Sleeping for 5 seconds
        assertEquals(0, shardManager.shardForBucket(100));
    }
}
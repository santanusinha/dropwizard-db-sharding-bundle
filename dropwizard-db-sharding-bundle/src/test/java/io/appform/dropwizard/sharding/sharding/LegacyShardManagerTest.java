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


import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class LegacyShardManagerTest {

    @Test
    public void testShardForOddBucket() {
        assertThrows(IllegalArgumentException.class,
                () -> new LegacyShardManager(9));
    }

    @Test
    public void testShardForEvenNon2PowerBucket() throws Exception {
        assertThrows(IllegalArgumentException.class,
                () -> new LegacyShardManager(40));
    }

    @Test
    public void testShardFor64Bucket() throws Exception {
        LegacyShardManager shardManager = new LegacyShardManager(64);
        assertEquals(63, shardManager.shardForBucket(999));
    }

    @Test
    public void testShardFor32Bucket() throws Exception {
        LegacyShardManager shardManager = new LegacyShardManager(32);
        assertEquals(31, shardManager.shardForBucket(999));
    }

    @Test
    public void testShardFor16Bucket() throws Exception {
        LegacyShardManager shardManager = new LegacyShardManager(16);
        assertEquals(15, shardManager.shardForBucket(999));
    }
}
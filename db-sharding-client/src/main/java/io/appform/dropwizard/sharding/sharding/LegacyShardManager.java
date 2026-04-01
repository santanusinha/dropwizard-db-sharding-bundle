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

import com.google.common.base.Preconditions;
import com.google.common.collect.Range;
import com.google.common.collect.RangeMap;
import com.google.common.collect.TreeRangeMap;
import java.util.Map;
import lombok.Builder;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;
import lombok.val;

/**
 * Manages shard to bucket mapping.
 */
@ToString
@Slf4j
public class LegacyShardManager extends ShardManager {
    private static final int MIN_BUCKET = 0;
    private static final int MAX_BUCKET = 999;
    private final int numShards;

    private RangeMap<Integer, Integer> buckets = TreeRangeMap.create();

    public LegacyShardManager(int numShards) {
        this(numShards, new InMemoryLocalShardBlacklistingStore());
    }

    @Builder
    public LegacyShardManager(int numShards, ShardBlacklistingStore shardBlacklistingStore) {
        super(shardBlacklistingStore);
        Preconditions.checkArgument(
                numShards > 1 && ((numShards & (numShards - 1)) == 0),
                "Shard manager only support 2^n shards." +
                        " Also it is senseless to use anything other than 2^n shards for scale out.");
        this.numShards = numShards;
        final RangeMap<Integer, Integer> assignedBuckets = TreeRangeMap.create();
        int interval = MAX_BUCKET / numShards;
        log.trace("Interval: {}", interval);
        int shardCounter = 0;
        boolean endReached = false;
        boolean altAssignment = ((MAX_BUCKET - (interval * numShards)) > interval);
        for (int start = MIN_BUCKET; !endReached; start += interval, shardCounter++) {
            int remaining = MAX_BUCKET - start;
            log.trace("Remaining: {}. Interval: {}, 2x Interval: {}", remaining, interval, 2 * interval);
            int end = start + interval - 1;
            endReached = shardCounter == numShards - 1;
            end = endReached
                    ? MAX_BUCKET
                    : end;
            log.trace("Assigning {} elements, from {} to {} into shard {}", end - start + 1, start, end, shardCounter);
            //End is reached when remaining items (max -
            assignedBuckets.put(Range.closed(start, end), shardCounter);
        }
        Preconditions.checkArgument(assignedBuckets.asMapOfRanges().size() == numShards,
                "There is an issue in shard allocation. " +
                        "Not all shards have been allocated to. Please contact devs.");
        buckets.putAll(assignedBuckets);
        log.info("Buckets to shard allocation: {}", buckets);
    }


    @Override
    public int numBuckets() {
        return 1000;
    }

    @Override
    public int numShards() {
        return numShards;
    }

    @Override
    protected int shardForBucketImpl(int bucketId) {
        Map.Entry<Range<Integer>, Integer> entry;
        if (bucketId >= MIN_BUCKET) {
          entry = buckets.getEntry(bucketId);
        } else { // Fix: https://github.com/santanusinha/dropwizard-db-sharding-bundle/issues/140
          entry = buckets.getEntry(MAX_BUCKET);
        }
        if (null == entry) {
            throw new IllegalAccessError("Bucket not mapped to any shard");
        }
        return entry.getValue();
    }
}

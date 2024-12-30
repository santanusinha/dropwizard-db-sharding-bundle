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

package io.appform.dropwizard.sharding.sharding;

import com.google.common.base.Preconditions;
import com.google.common.collect.Range;
import com.google.common.collect.RangeMap;
import com.google.common.collect.TreeRangeMap;
import lombok.Builder;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;
import lombok.val;

/**
 * Manages shard to bucket mapping.
 */
@ToString
@Slf4j
public class BalancedShardManager extends ShardManager {
    private static final int MIN_BUCKET = 0;
    private static final int MAX_BUCKET = 1023;
    private final int numShards;

    private RangeMap<Integer, Integer> buckets = TreeRangeMap.create();

    @Builder
    public BalancedShardManager(int numShards) {
        this(numShards, new InMemoryLocalShardBlacklistingStore());
    }

    public BalancedShardManager(int numShards, ShardBlacklistingStore shardBlacklistingStore) {
        super(shardBlacklistingStore);
        Preconditions.checkArgument(
                numShards > 1 && ((numShards & (numShards - 1)) == 0),
                "Shard manager only support 2^n shards." +
                        " Also it is senseless to use anything other than 2^n shards for scale out.");
        this.numShards = numShards;
        final RangeMap<Integer, Integer> assignedBuckets = TreeRangeMap.create();
        int interval = (MAX_BUCKET + 1) / numShards;
        log.trace("Interval: {}", interval);
        int shardCounter = 0;
        boolean endReached = false;
        for (int start = MIN_BUCKET; !endReached; start += interval, shardCounter++) {
            int end = start + interval - 1;
            end = (shardCounter == numShards - 1)
                    ? MAX_BUCKET
                    : end;
            endReached = end == MAX_BUCKET;
            log.trace("Assigning {} to {} to shard {}", start, end, shardCounter);
            assignedBuckets.put(Range.closed(start, end), shardCounter);
        }
        Preconditions.checkArgument(assignedBuckets.asMapOfRanges()
                .keySet()
                .stream()
                .allMatch(range -> (range.upperEndpoint() - range.lowerEndpoint() + 1) == interval));
        buckets.putAll(assignedBuckets);
        log.info("Buckets to shard allocation: {}", buckets);
    }

    @Override
    public int numBuckets() {
        return 1024;
    }

    @Override
    public int numShards() {
        return numShards;
    }

    @Override
    protected int shardForBucketImpl(int bucketId) {
        Preconditions.checkArgument(bucketId >= MIN_BUCKET && bucketId <= MAX_BUCKET,
                "Bucket id can only be in the range of [1-1000] (inclusive)");
        val entry = buckets.getEntry(bucketId);
        if (null == entry) {
            throw new IllegalAccessError("Bucket not mapped to any shard");
        }
        return entry.getValue();
    }


}

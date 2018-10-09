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

package in.cleartax.dropwizard.sharding.resolvers.shard;

import com.google.common.collect.ImmutableRangeMap;
import com.google.common.collect.Range;
import com.google.common.collect.RangeMap;
import lombok.Builder;
import lombok.extern.slf4j.Slf4j;
import lombok.val;

/**
 * Created on 28/09/18
 */
@Slf4j
public class HashBasedShardResolver implements ShardResolver {
    public static final int MIN_BUCKET = 0;
    public static final int MAX_BUCKET = 999;

    private final RangeMap<String, String> buckets;

    @Builder
    public HashBasedShardResolver(int numBuckets) {
        int interval = MAX_BUCKET / numBuckets;
        int shardCounter = 0;
        boolean endReached = false;
        ImmutableRangeMap.Builder<String, String> builder = new ImmutableRangeMap.Builder<>();
        for (int start = MIN_BUCKET; !endReached; start += interval, shardCounter++) {
            int end = start + interval - 1;
            endReached = !((MAX_BUCKET - start) > (2 * interval));
            end = endReached ? end + MAX_BUCKET - end : end;
            builder.put(Range.closed(String.valueOf(start), String.valueOf(end)),
                    String.valueOf(shardCounter));
        }
        this.buckets = builder.build();
        log.info("Buckets to shard allocation: {}", buckets);
    }

    @Override
    public String resolve(String bucketId) {
        val entry = buckets.getEntry(bucketId);
        if (null == entry) {
            throw new IllegalAccessError("Bucket not mapped to any shard");
        }
        return entry.getValue();
    }
}

/*
 * Copyright 2018 Cleartax
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

import com.google.common.collect.Range;
import com.google.common.collect.RangeMap;
import lombok.RequiredArgsConstructor;

import java.util.Map;

@RequiredArgsConstructor
public class RangeBasedShardResolver implements ShardResolver {

    private final RangeMap<String, String> bucketToShardMap;

    @Override
    public String resolve(String bucketId) {
        Map.Entry<Range<String>, String> entry = bucketToShardMap.getEntry(bucketId);
        if (entry == null) {
            throw new IllegalAccessError(String.format("%s bucket not mapped to any shard", bucketId));
        }
        return entry.getValue();
    }
}

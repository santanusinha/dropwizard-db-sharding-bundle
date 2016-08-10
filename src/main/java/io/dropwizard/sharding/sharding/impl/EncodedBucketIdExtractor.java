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
package io.dropwizard.sharding.sharding.impl;

import com.google.common.base.Preconditions;
import io.dropwizard.sharding.sharding.BucketIdExtractor;

public class EncodedBucketIdExtractor implements BucketIdExtractor<String> {
    private final int firstCharIndex;
    private final int length;

    public EncodedBucketIdExtractor(int firstCharIndex, int length) {
        this.firstCharIndex = firstCharIndex;
        this.length = length;
    }

    @Override
    public int bucketId(String key) {
        Preconditions.checkArgument(length == 3, "Length should be three");
        Preconditions.checkArgument(firstCharIndex + length <= key.length(), "Key is shorter than expected");
        return Integer.parseInt(key.substring(firstCharIndex, firstCharIndex + length));
    }
}

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

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class EncodedBucketIdExtractorTest {

    @Test(expected = IllegalArgumentException.class)
    public void testbucketId_moreBuckets() throws Exception {
        EncodedBucketIdExtractor encodedBucketIdExtractor = new EncodedBucketIdExtractor(4,4);
        encodedBucketIdExtractor.bucketId("1234567890");
    }


    @Test(expected = IllegalArgumentException.class)
    public void testbucketId_keyShorter() throws Exception {
        EncodedBucketIdExtractor encodedBucketIdExtractor = new EncodedBucketIdExtractor(4,3);
        encodedBucketIdExtractor.bucketId("012345");
    }

    @Test
    public void testbucketId() throws Exception {
        EncodedBucketIdExtractor encodedBucketIdExtractor = new EncodedBucketIdExtractor(4,3);
        assertEquals(encodedBucketIdExtractor.bucketId("1234567890"), 567);
        assertEquals(encodedBucketIdExtractor.bucketId("1234567"), 567);
    }



}
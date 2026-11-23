package io.appform.dropwizard.sharding.sharding.impl;

import com.google.common.hash.Hashing;
import io.appform.dropwizard.sharding.sharding.BalancedShardManager;
import io.appform.dropwizard.sharding.sharding.LegacyShardManager;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConsistentHashBucketIdExtractorTest {

    @Test
    void testBucketIdWithLegacyShardManager() {
        var shardManager = new LegacyShardManager(32);
        ConsistentHashBucketIdExtractor<String> extractor = new ConsistentHashBucketIdExtractor<>(shardManager);
        var shardId = shardManager.shardForBucket(extractor.bucketId("MRT2509051351592369928055")) + 1;
        assertTrue(shardId > 0 && shardId <= 32);
    }

    @Test
    void testBucketIdWithBalancedShardManager() {
        var shardManager = new BalancedShardManager(32);
        ConsistentHashBucketIdExtractor<String> extractor = new ConsistentHashBucketIdExtractor<>(shardManager);
        var shardId = shardManager.shardForBucket(extractor.bucketId("MRT2509051351592369928055")) + 1;
        assertTrue(shardId > 0 && shardId <= 32);
    }

    @Test
    void bucketIdIsUnchangedMurmurModNumBuckets() {
        var shardManager = new BalancedShardManager(32);
        var extractor = new ConsistentHashBucketIdExtractor<String>(shardManager);
        for (String key : List.of("a", "customer1", "MRT2509051351592369928055")) {
            int hash = Hashing.murmur3_128().hashString(key, StandardCharsets.UTF_8).asInt();
            hash *= hash < 0 ? -1 : 1;
            assertEquals(hash % shardManager.numBuckets(), extractor.bucketId(key));
        }
    }
}

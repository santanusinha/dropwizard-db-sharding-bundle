package io.appform.dropwizard.sharding.sharding.impl;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.appform.dropwizard.sharding.sharding.BalancedShardManager;
import io.appform.dropwizard.sharding.sharding.LegacyShardManager;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ConsistentHashBucketIdExtractorTest {

  @Test
  void testBucketIdWithLegacyShardManager() {
    var shardManager =  new LegacyShardManager(32);
    ConsistentHashBucketIdExtractor<String> extractor = new ConsistentHashBucketIdExtractor<>(
        Map.of("tenant1", shardManager));
    var shardId = shardManager.shardForBucket(extractor.bucketId("tenant1", "MRT2509051351592369928055")) + 1;
    assertTrue(shardId > 0 && shardId <= 32);
  }

  @Test
  void testBucketIdWithBalancedShardManager() {
    var shardManager =  new BalancedShardManager(32);
    ConsistentHashBucketIdExtractor<String> extractor = new ConsistentHashBucketIdExtractor<>(
        Map.of("tenant1", shardManager));
    var shardId = shardManager.shardForBucket(extractor.bucketId("tenant1", "MRT2509051351592369928055")) + 1;
    assertTrue(shardId > 0 && shardId <= 32);
  }
}

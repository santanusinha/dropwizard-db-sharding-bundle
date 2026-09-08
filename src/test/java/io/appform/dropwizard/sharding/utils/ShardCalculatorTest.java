package io.appform.dropwizard.sharding.utils;

import io.appform.dropwizard.sharding.sharding.BalancedShardManager;
import io.appform.dropwizard.sharding.sharding.impl.ConsistentHashBucketIdExtractor;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShardCalculatorTest {

    @Test
    void routesUsingBoundTenant() {
        var manager = new BalancedShardManager(4);
        var extractor = new ConsistentHashBucketIdExtractor<String>(
                Map.of("TENANT1", manager));
        var calculator = new ShardCalculator<>(
                "TENANT1",
                manager,
                extractor);

        int expectedBucket = extractor.bucketId("TENANT1", "customer-1");

        assertEquals(
                manager.shardForBucket(expectedBucket),
                calculator.shardId("customer-1"));
    }

    @Test
    void validatesUsingBoundTenant() {
        var manager = new BalancedShardManager(2);
        var calculator = new ShardCalculator<>(
                "TENANT1",
                manager,
                new ConsistentHashBucketIdExtractor<>(Map.of("TENANT1", manager)));

        int shardId = calculator.shardId("customer-1");
        assertTrue(calculator.isOnValidShard("customer-1"));

        manager.blacklistShard(shardId);

        assertFalse(calculator.isOnValidShard("customer-1"));
    }
}

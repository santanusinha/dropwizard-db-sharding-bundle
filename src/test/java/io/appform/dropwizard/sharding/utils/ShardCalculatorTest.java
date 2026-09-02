package io.appform.dropwizard.sharding.utils;

import io.appform.dropwizard.sharding.sharding.BalancedShardManager;
import io.appform.dropwizard.sharding.sharding.BucketIdExtractor;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShardCalculatorTest {

    @Test
    void routesWithBoundTenantAndManager() {
        BucketIdExtractor<String> extractor = (tenantId, key) -> {
            assertEquals("TENANT1", tenantId);
            assertEquals("key", key);
            return 750;
        };
        var calculator = new ShardCalculator<>(
                "TENANT1",
                new BalancedShardManager(4),
                extractor);

        assertEquals(2, calculator.shardId("key"));
        assertTrue(calculator.isOnValidShard("key"));
    }

    @Test
    void calculatorsUseIndependentManagers() {
        BucketIdExtractor<String> tenant1Extractor = (tenantId, key) -> {
            assertEquals("TENANT1", tenantId);
            assertEquals("key", key);
            return 750;
        };
        BucketIdExtractor<String> tenant2Extractor = (tenantId, key) -> {
            assertEquals("TENANT2", tenantId);
            assertEquals("key", key);
            return 750;
        };
        var twoShardCalculator = new ShardCalculator<>(
                "TENANT1",
                new BalancedShardManager(2),
                tenant1Extractor);
        var fourShardCalculator = new ShardCalculator<>(
                "TENANT2",
                new BalancedShardManager(4),
                tenant2Extractor);

        assertEquals(1, twoShardCalculator.shardId("key"));
        assertEquals(2, fourShardCalculator.shardId("key"));
        assertTrue(twoShardCalculator.isOnValidShard("key"));
        assertTrue(fourShardCalculator.isOnValidShard("key"));
    }
}

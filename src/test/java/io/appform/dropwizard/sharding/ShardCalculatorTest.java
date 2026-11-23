package io.appform.dropwizard.sharding;

import com.google.common.hash.Hashing;
import io.appform.dropwizard.sharding.sharding.BalancedShardManager;
import io.appform.dropwizard.sharding.sharding.LegacyShardManager;
import io.appform.dropwizard.sharding.sharding.ShardManager;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShardCalculatorTest {

    private static final List<String> KEYS = List.of("a", "customer1", "MRT2509051351592369928055", "x-42", "");

    // Reference implementation of the pre-refactor algorithm, kept here to prove placement is unchanged.
    private static int expectedBucket(String key, ShardManager shardManager) {
        int hashKey = Hashing.murmur3_128().hashString(key, StandardCharsets.UTF_8).asInt();
        hashKey *= hashKey < 0 ? -1 : 1;
        return hashKey % shardManager.numBuckets();
    }

    @Test
    void shardIdMatchesLegacyAlgorithmForBalancedManager() {
        final var shardManager = new BalancedShardManager(16);
        final var calculator = ShardCalculators.forTenant("t1", shardManager);
        for (String key : KEYS) {
            assertEquals(shardManager.shardForBucket(expectedBucket(key, shardManager)), calculator.shardId(key), key);
        }
    }

    @Test
    void shardIdMatchesLegacyAlgorithmForLegacyManager() {
        final var shardManager = new LegacyShardManager(4);
        final var calculator = ShardCalculators.forTenant("t1", shardManager);
        for (String key : KEYS) {
            assertEquals(shardManager.shardForBucket(expectedBucket(key, shardManager)), calculator.shardId(key), key);
        }
    }

    @Test
    void isOnValidShardReflectsBlacklisting() {
        final var key = "customer1";
        final var healthy = ShardCalculators.forTenant("t1", new BalancedShardManager(2));
        assertTrue(healthy.isOnValidShard(key));
        // ShardManager refreshes its blacklist cache asynchronously, so blacklist before the first lookup.
        final var shardManager = new BalancedShardManager(2);
        shardManager.blacklistShard(healthy.shardId(key));
        assertFalse(ShardCalculators.forTenant("t1", shardManager).isOnValidShard(key));
    }

    @Test
    void exposesTenant() {
        assertEquals("t1", ShardCalculators.forTenant("t1", new BalancedShardManager(2)).tenantId());
    }

    @Test
    void rejectsNullArguments() {
        assertThrows(NullPointerException.class, () -> ShardCalculators.forTenant(null, new BalancedShardManager(2)));
        assertThrows(NullPointerException.class, () -> ShardCalculators.forTenant("t1", null));
    }

    @Test
    void bucketIdFunctionMatchesLegacyBucket() {
        final var shardManager = new BalancedShardManager(8);
        final var fn = ShardCalculators.bucketIdFunction(ShardCalculators.forTenant("t1", shardManager));
        for (String key : KEYS) {
            assertEquals(expectedBucket(key, shardManager), fn.applyAsInt(key), key);
        }
    }
}

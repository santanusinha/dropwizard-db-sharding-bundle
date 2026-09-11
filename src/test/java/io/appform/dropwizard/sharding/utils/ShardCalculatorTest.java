package io.appform.dropwizard.sharding.utils;

import io.appform.dropwizard.sharding.sharding.BalancedShardManager;
import io.appform.dropwizard.sharding.sharding.InMemoryLocalShardBlacklistingStore;
import io.appform.dropwizard.sharding.sharding.ShardManager;
import io.appform.dropwizard.sharding.sharding.impl.ConsistentHashBucketIdExtractor;
import org.junit.jupiter.api.Test;

import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShardCalculatorTest {

    @Test
    void shardIdIsWithinShardCountAndStable() {
        final ShardManager shardManager = new BalancedShardManager(8);
        final ShardCalculator<String> calculator =
                new ShardCalculator<>(shardManager, new ConsistentHashBucketIdExtractor<>(shardManager));

        final int first = calculator.shardId("customer-1");
        assertTrue(first >= 0 && first < 8);
        assertEquals(first, calculator.shardId("customer-1"));
    }

    @Test
    void keysAreDistributedAcrossShards() {
        final ShardManager shardManager = new BalancedShardManager(4);
        final ShardCalculator<String> calculator =
                new ShardCalculator<>(shardManager, new ConsistentHashBucketIdExtractor<>(shardManager));

        final long distinctShards = IntStream.range(0, 200)
                .mapToObj(i -> "customer-" + i)
                .map(calculator::shardId)
                .distinct()
                .count();
        assertTrue(distinctShards > 1, "expected keys to spread over more than one shard");
    }

    @Test
    void blacklistedShardIsReportedAsInvalid() throws InterruptedException {
        final InMemoryLocalShardBlacklistingStore blacklistingStore = new InMemoryLocalShardBlacklistingStore();
        final ShardManager shardManager = new BalancedShardManager(4, blacklistingStore);
        final ShardCalculator<String> calculator =
                new ShardCalculator<>(shardManager, new ConsistentHashBucketIdExtractor<>(shardManager));

        final String key = "customer-1";
        assertTrue(calculator.isOnValidShard(key));

        shardManager.blacklistShard(calculator.shardId(key));
        // ShardManager caches shard blacklist state and refreshes it asynchronously,
        // so poll until the refresh lands rather than asserting immediately.
        for (int i = 0; i < 50 && calculator.isOnValidShard(key); i++) {
            Thread.sleep(100);
        }
        assertFalse(calculator.isOnValidShard(key));
    }
}

package io.appform.dropwizard.sharding.dao;

import io.appform.dropwizard.sharding.sharding.BalancedShardManager;
import io.appform.dropwizard.sharding.sharding.ShardManager;
import io.appform.dropwizard.sharding.sharding.impl.ConsistentHashBucketIdExtractor;
import io.appform.dropwizard.sharding.utils.ShardCalculator;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class PerTenantShardCalculatorTest {

    @Test
    void eachTenantGetsItsOwnShardCalculatorInstance() {
        ShardManager tenant1Manager = new BalancedShardManager(4);
        ShardManager tenant2Manager = new BalancedShardManager(2);

        ShardCalculator<String> tenant1Calculator = new ShardCalculator<>("TENANT1", tenant1Manager,
                new ConsistentHashBucketIdExtractor<>(Map.of("TENANT1", tenant1Manager)));
        ShardCalculator<String> tenant2Calculator = new ShardCalculator<>("TENANT2", tenant2Manager,
                new ConsistentHashBucketIdExtractor<>(Map.of("TENANT2", tenant2Manager)));

        assertNotSame(tenant1Calculator, tenant2Calculator);

        String key = "some-consistent-routing-key";
        int tenant1Shard = tenant1Calculator.shardId(key);
        int tenant2Shard = tenant2Calculator.shardId(key);

        // Both must be valid indices into their own (differently sized) shard pools.
        assertTrue(tenant1Shard >= 0 && tenant1Shard < 4);
        assertTrue(tenant2Shard >= 0 && tenant2Shard < 2);
    }
}

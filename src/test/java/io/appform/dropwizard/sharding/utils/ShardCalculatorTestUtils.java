package io.appform.dropwizard.sharding.utils;

import io.appform.dropwizard.sharding.sharding.ShardManager;
import io.appform.dropwizard.sharding.sharding.impl.ConsistentHashBucketIdExtractor;

import java.util.HashMap;
import java.util.Map;

public final class ShardCalculatorTestUtils {

    private ShardCalculatorTestUtils() {
    }

    public static void register(Map<String, ShardManager> shardManagers) {
        Map<String, ShardCalculator<String>> calculators = new HashMap<>();
        shardManagers.forEach((tenantId, shardManager) -> calculators.put(
                tenantId,
                new ShardCalculator<>(
                        tenantId,
                        shardManager,
                        new ConsistentHashBucketIdExtractor<>(Map.of(tenantId, shardManager)))));
        ShardCalculatorRegistry.register(calculators);
    }
}

package io.appform.dropwizard.sharding.utils;

import io.appform.dropwizard.sharding.sharding.ShardManager;
import io.appform.dropwizard.sharding.sharding.impl.ConsistentHashBucketIdExtractor;

import java.util.Map;

public final class ShardCalculatorTestUtils {

    private ShardCalculatorTestUtils() {
    }

    public static void register(Map<String, ShardManager> shardManagers) {
        shardManagers.forEach((tenantId, shardManager) -> ShardCalculatorRegistry.register(
                tenantId,
                new ShardCalculator<>(
                        tenantId,
                        shardManager,
                        new ConsistentHashBucketIdExtractor<>(Map.of(tenantId, shardManager)))));
    }
}

package io.appform.dropwizard.sharding.testutils;

import io.appform.dropwizard.sharding.sharding.ShardManager;
import io.appform.dropwizard.sharding.sharding.impl.ConsistentHashBucketIdExtractor;
import io.appform.dropwizard.sharding.utils.ShardCalculator;

import java.util.Map;
import java.util.stream.Collectors;

/**
 * Builds shard calculators the way the bundle does, for tests that wire DAOs by hand.
 */
public final class ShardCalculators {

    private ShardCalculators() {
    }

    public static ShardCalculator<String> calculator(final ShardManager shardManager) {
        return new ShardCalculator<>(shardManager, new ConsistentHashBucketIdExtractor<>(shardManager));
    }

    public static Map<String, ShardCalculator<String>> forTenant(final String tenantId,
                                                                 final ShardManager shardManager) {
        return Map.of(tenantId, calculator(shardManager));
    }

    public static Map<String, ShardCalculator<String>> forTenants(
            final Map<String, ShardManager> shardManagers) {
        return shardManagers.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, entry -> calculator(entry.getValue())));
    }
}

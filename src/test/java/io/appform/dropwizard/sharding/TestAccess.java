package io.appform.dropwizard.sharding;

import io.appform.dropwizard.sharding.sharding.ShardManager;

/**
 * Test-only bridge to package-private shard calculator internals.
 */
public final class TestAccess {

    private TestAccess() {
    }

    public static ShardCalculator calculator(String tenantId, ShardManager shardManager) {
        return ShardCalculators.forTenant(tenantId, shardManager);
    }

    public static ShardCalculator register(String tenantId, ShardManager shardManager) {
        final var calculator = calculator(tenantId, shardManager);
        ShardCalculatorRegistry.register(calculator);
        return calculator;
    }

    public static void reset() {
        ShardCalculatorRegistry.clear();
    }
}

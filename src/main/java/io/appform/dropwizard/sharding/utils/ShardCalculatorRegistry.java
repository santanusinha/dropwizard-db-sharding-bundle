package io.appform.dropwizard.sharding.utils;

import com.google.common.annotations.VisibleForTesting;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Registry for tenant-specific shard calculators.
 */
public final class ShardCalculatorRegistry {

    private static final ConcurrentMap<String, ShardCalculator<String>> CALCULATORS =
            new ConcurrentHashMap<>();

    private ShardCalculatorRegistry() {
    }

    public static void register(String tenantId, ShardCalculator<String> calculator) {
        CALCULATORS.put(
                Objects.requireNonNull(tenantId, "tenantId"),
                Objects.requireNonNull(calculator, "calculator"));
    }

    public static ShardCalculator<String> get(String tenantId) {
        ShardCalculator<String> calculator = CALCULATORS.get(Objects.requireNonNull(tenantId, "tenantId"));
        if (calculator == null) {
            throw new IllegalStateException("ShardCalculator has not been registered for tenant: " + tenantId);
        }
        return calculator;
    }

    @VisibleForTesting
    public static void clear() {
        CALCULATORS.clear();
    }
}

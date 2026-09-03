package io.appform.dropwizard.sharding.utils;

import com.google.common.annotations.VisibleForTesting;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Registry for tenant-specific shard calculators.
 * Publishes each batch as a single immutable snapshot.
 */
public final class ShardCalculatorRegistry {

    private volatile Map<String, ShardCalculator<String>> calculators = Map.of();

    public synchronized void register(Map<String, ShardCalculator<String>> calculators) {
        Objects.requireNonNull(calculators, "calculators");
        calculators.forEach((tenantId, calculator) -> {
            Objects.requireNonNull(tenantId, "tenantId");
            Objects.requireNonNull(calculator, "calculator");
        });
        Map<String, ShardCalculator<String>> current = this.calculators;
        calculators.keySet().forEach(tenantId -> {
            if (current.containsKey(tenantId)) {
                throw new IllegalStateException("ShardCalculator already registered for tenant: " + tenantId);
            }
        });
        Map<String, ShardCalculator<String>> updated = new HashMap<>(current);
        updated.putAll(calculators);
        this.calculators = Map.copyOf(updated);
    }

    public ShardCalculator<String> get(String tenantId) {
        Objects.requireNonNull(tenantId, "tenantId");
        Map<String, ShardCalculator<String>> snapshot = calculators;
        ShardCalculator<String> calculator = snapshot.get(tenantId);
        if (calculator == null) {
            throw new IllegalStateException("ShardCalculator has not been registered for tenant: " + tenantId);
        }
        return calculator;
    }

    @VisibleForTesting
    public synchronized void clear() {
        calculators = Map.of();
    }
}

package io.appform.dropwizard.sharding.utils;

import com.google.common.annotations.VisibleForTesting;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class ShardCalculatorRegistry {

    private static final ConcurrentMap<String, ShardCalculator<String>> REGISTRY = new ConcurrentHashMap<>();

    private ShardCalculatorRegistry() {
    }

    public static synchronized void register(Map<String, ShardCalculator<String>> calculators) {
        Objects.requireNonNull(calculators, "calculators");
        calculators.forEach((tenantId, calculator) -> {
            Objects.requireNonNull(tenantId, "tenantId");
            Objects.requireNonNull(calculator, "calculator");
        });
        calculators.keySet().forEach(tenantId -> {
            if (REGISTRY.containsKey(tenantId)) {
                throw new IllegalStateException("ShardCalculator already registered for tenant: " + tenantId);
            }
        });
        REGISTRY.putAll(calculators);
    }

    public static ShardCalculator<String> get(String tenantId) {
        ShardCalculator<String> calculator = REGISTRY.get(tenantId);
        if (calculator == null) {
            throw new IllegalStateException("ShardCalculator has not been registered for tenant: " + tenantId);
        }
        return calculator;
    }

    @VisibleForTesting
    public static synchronized void clear() {
        REGISTRY.clear();
    }
}

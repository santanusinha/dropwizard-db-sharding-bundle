package io.appform.dropwizard.sharding.utils;

import io.appform.dropwizard.sharding.sharding.BalancedShardManager;
import io.appform.dropwizard.sharding.sharding.ShardManager;
import io.appform.dropwizard.sharding.sharding.impl.ConsistentHashBucketIdExtractor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

@org.junit.jupiter.api.parallel.ResourceLock("ShardCalculatorRegistry")
class ShardCalculatorRegistryTest {

    @AfterEach
    void tearDown() {
        ShardCalculatorRegistry.clear();
    }

    @Test
    void returnsCalculatorForEachTenant() {
        var tenant1 = calculatorFor("TENANT1", 2);
        var tenant2 = calculatorFor("TENANT2", 4);

        ShardCalculatorRegistry.register("TENANT1", tenant1);
        ShardCalculatorRegistry.register("TENANT2", tenant2);

        assertSame(tenant1, ShardCalculatorRegistry.get("TENANT1"));
        assertSame(tenant2, ShardCalculatorRegistry.get("TENANT2"));
    }

    @Test
    void duplicateRegistrationOverwritesCalculator() {
        var first = calculatorFor("TENANT1", 2);
        var second = calculatorFor("TENANT1", 4);

        ShardCalculatorRegistry.register("TENANT1", first);
        ShardCalculatorRegistry.register("TENANT1", second);

        assertSame(second, ShardCalculatorRegistry.get("TENANT1"));
    }

    @Test
    void missingTenantIncludesTenantId() {
        var error = assertThrows(
                IllegalStateException.class,
                () -> ShardCalculatorRegistry.get("UNKNOWN"));

        assertEquals(
                "ShardCalculator has not been registered for tenant: UNKNOWN",
                error.getMessage());
    }

    @Test
    void clearRemovesEveryTenant() {
        ShardCalculatorRegistry.register(
                "TENANT1",
                calculatorFor("TENANT1", 2));

        ShardCalculatorRegistry.clear();

        assertThrows(
                IllegalStateException.class,
                () -> ShardCalculatorRegistry.get("TENANT1"));
    }

    @Test
    void concurrentReadsReturnRegisteredCalculator() throws Exception {
        var calculator = calculatorFor("TENANT1", 2);
        ShardCalculatorRegistry.register("TENANT1", calculator);
        var executor = Executors.newFixedThreadPool(4);
        try {
            var reads = IntStream.range(0, 20)
                    .mapToObj(ignored -> executor.submit(
                            () -> ShardCalculatorRegistry.get("TENANT1")))
                    .collect(Collectors.toList());
            for (var read : reads) {
                assertSame(calculator, read.get(5, TimeUnit.SECONDS));
            }
        } finally {
            executor.shutdownNow();
        }
    }

    private ShardCalculator<String> calculatorFor(
            String tenantId,
            int shardCount) {
        ShardManager manager = new BalancedShardManager(shardCount);
        return new ShardCalculator<>(
                tenantId,
                manager,
                new ConsistentHashBucketIdExtractor<>(
                        Map.of(tenantId, manager)));
    }
}

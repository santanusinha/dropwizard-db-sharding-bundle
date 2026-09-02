package io.appform.dropwizard.sharding.utils;

import io.appform.dropwizard.sharding.sharding.BalancedShardManager;
import io.appform.dropwizard.sharding.sharding.ShardManager;
import io.appform.dropwizard.sharding.sharding.impl.ConsistentHashBucketIdExtractor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class ShardCalculatorRegistryTest {

    @AfterEach
    void tearDown() {
        ShardCalculatorRegistry.clear();
    }

    @Test
    void registerTwoCalculatorsAndReturnSameInstancePerTenant() {
        var tenant1 = calculatorFor("TENANT1", 4);
        var tenant2 = calculatorFor("TENANT2", 2);

        ShardCalculatorRegistry.register(Map.of(
                "TENANT1", tenant1,
                "TENANT2", tenant2));

        assertSame(tenant1, ShardCalculatorRegistry.get("TENANT1"));
        assertSame(tenant2, ShardCalculatorRegistry.get("TENANT2"));
    }

    @Test
    void missingTenantThrowsExactError() {
        var exception = assertThrows(IllegalStateException.class, () -> ShardCalculatorRegistry.get("TENANT1"));

        assertEquals("ShardCalculator has not been registered for tenant: TENANT1", exception.getMessage());
    }

    @Test
    void nullMapRejectsWithoutChangingRegistry() {
        var tenant1 = calculatorFor("TENANT1", 4);
        ShardCalculatorRegistry.register(Map.of("TENANT1", tenant1));

        assertThrows(NullPointerException.class, () -> ShardCalculatorRegistry.register(null));

        assertSame(tenant1, ShardCalculatorRegistry.get("TENANT1"));
    }

    @Test
    void nullTenantIdRejectsBeforePublishingAnyEntry() {
        var valid = calculatorFor("TENANT2", 2);
        var calculators = new HashMap<String, ShardCalculator<String>>();
        calculators.put("TENANT1", calculatorFor("TENANT1", 4));
        calculators.put(null, valid);

        assertThrows(NullPointerException.class, () -> ShardCalculatorRegistry.register(calculators));

        assertThrows(IllegalStateException.class, () -> ShardCalculatorRegistry.get("TENANT1"));
        assertThrows(IllegalStateException.class, () -> ShardCalculatorRegistry.get("TENANT2"));
    }

    @Test
    void nullCalculatorRejectsBeforePublishingAnyEntry() {
        var calculators = new HashMap<String, ShardCalculator<String>>();
        calculators.put("TENANT1", calculatorFor("TENANT1", 4));
        calculators.put("TENANT2", null);

        assertThrows(NullPointerException.class, () -> ShardCalculatorRegistry.register(calculators));

        assertThrows(IllegalStateException.class, () -> ShardCalculatorRegistry.get("TENANT1"));
        assertThrows(IllegalStateException.class, () -> ShardCalculatorRegistry.get("TENANT2"));
    }

    @Test
    void duplicateBatchDoesNotPublishAnyNewTenant() {
        var existing = calculatorFor("TENANT1", 4);
        ShardCalculatorRegistry.register(Map.of("TENANT1", existing));

        var duplicateBatch = new HashMap<String, ShardCalculator<String>>();
        duplicateBatch.put("TENANT1", calculatorFor("TENANT1", 2));
        duplicateBatch.put("TENANT2", calculatorFor("TENANT2", 8));

        var exception = assertThrows(IllegalStateException.class, () -> ShardCalculatorRegistry.register(duplicateBatch));

        assertEquals("ShardCalculator already registered for tenant: TENANT1", exception.getMessage());
        assertSame(existing, ShardCalculatorRegistry.get("TENANT1"));
        assertThrows(IllegalStateException.class, () -> ShardCalculatorRegistry.get("TENANT2"));
    }

    @Test
    void clearRemovesAllTenants() {
        ShardCalculatorRegistry.register(Map.of(
                "TENANT1", calculatorFor("TENANT1", 4),
                "TENANT2", calculatorFor("TENANT2", 2)));

        ShardCalculatorRegistry.clear();

        assertThrows(IllegalStateException.class, () -> ShardCalculatorRegistry.get("TENANT1"));
        assertThrows(IllegalStateException.class, () -> ShardCalculatorRegistry.get("TENANT2"));
    }

    @Test
    void concurrentReadsAfterPublicationReturnSameCalculator() throws Exception {
        var calculator = calculatorFor("TENANT1", 4);
        ShardCalculatorRegistry.register(Map.of("TENANT1", calculator));

        ExecutorService executor = Executors.newFixedThreadPool(4);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Callable<ShardCalculator<String>>> tasks = List.of(
                    readerTask(start),
                    readerTask(start),
                    readerTask(start),
                    readerTask(start));
            List<Future<ShardCalculator<String>>> futures = tasks.stream()
                    .map(executor::submit)
                    .collect(Collectors.toList());

            start.countDown();

            for (Future<ShardCalculator<String>> future : futures) {
                assertSame(calculator, future.get(5, TimeUnit.SECONDS));
            }
        } finally {
            executor.shutdownNow();
        }
    }

    private Callable<ShardCalculator<String>> readerTask(CountDownLatch start) {
        return () -> {
            start.await(5, TimeUnit.SECONDS);
            return ShardCalculatorRegistry.get("TENANT1");
        };
    }

    private ShardCalculator<String> calculatorFor(String tenantId, int shardCount) {
        ShardManager shardManager = new BalancedShardManager(shardCount);
        return new ShardCalculator<>(
                tenantId,
                shardManager,
                new ConsistentHashBucketIdExtractor<>(Map.of(tenantId, shardManager)));
    }
}

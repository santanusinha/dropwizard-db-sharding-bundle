package io.appform.dropwizard.sharding.utils;

import io.appform.dropwizard.sharding.sharding.BalancedShardManager;
import io.appform.dropwizard.sharding.sharding.BucketIdExtractor;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShardCalculatorTest {

    @Test
    void exposesOnlyTenantBoundRoutingApi() {
        Constructor<?>[] constructors = ShardCalculator.class.getConstructors();
        assertEquals(1, constructors.length);
        assertIterableEquals(
                List.of(String.class, io.appform.dropwizard.sharding.sharding.ShardManager.class, BucketIdExtractor.class),
                Arrays.asList(constructors[0].getParameterTypes()));

        Method[] declaredMethods = ShardCalculator.class.getDeclaredMethods();
        assertEquals(2, declaredMethods.length);
        assertTrue(Arrays.stream(declaredMethods).anyMatch(method ->
                method.getName().equals("shardId")
                        && method.getParameterCount() == 1));
        assertTrue(Arrays.stream(declaredMethods).anyMatch(method ->
                method.getName().equals("isOnValidShard")
                        && method.getParameterCount() == 1));
        assertFalse(Arrays.stream(declaredMethods).anyMatch(method ->
                method.getName().equals("shardId")
                        && method.getParameterCount() == 2));
        assertFalse(Arrays.stream(declaredMethods).anyMatch(method ->
                method.getName().equals("isOnValidShard")
                        && method.getParameterCount() == 2));
    }

    @Test
    void routesWithBoundTenantAndManager() {
        BucketIdExtractor<String> extractor = (tenantId, key) -> {
            assertEquals("TENANT1", tenantId);
            assertEquals("key", key);
            return 750;
        };
        var calculator = new ShardCalculator<>(
                "TENANT1",
                new BalancedShardManager(4),
                extractor);

        assertEquals(2, calculator.shardId("key"));
        assertTrue(calculator.isOnValidShard("key"));
    }

    @Test
    void calculatorsUseIndependentManagers() {
        BucketIdExtractor<String> tenant1Extractor = (tenantId, key) -> {
            assertEquals("TENANT1", tenantId);
            assertEquals("key", key);
            return 750;
        };
        BucketIdExtractor<String> tenant2Extractor = (tenantId, key) -> {
            assertEquals("TENANT2", tenantId);
            assertEquals("key", key);
            return 750;
        };
        var twoShardCalculator = new ShardCalculator<>(
                "TENANT1",
                new BalancedShardManager(2),
                tenant1Extractor);
        var fourShardCalculator = new ShardCalculator<>(
                "TENANT2",
                new BalancedShardManager(4),
                tenant2Extractor);

        assertEquals(1, twoShardCalculator.shardId("key"));
        assertEquals(2, fourShardCalculator.shardId("key"));
        assertTrue(twoShardCalculator.isOnValidShard("key"));
        assertTrue(fourShardCalculator.isOnValidShard("key"));
    }
}

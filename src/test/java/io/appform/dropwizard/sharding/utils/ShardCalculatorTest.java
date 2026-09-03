package io.appform.dropwizard.sharding.utils;

import io.appform.dropwizard.sharding.sharding.BalancedShardManager;
import io.appform.dropwizard.sharding.sharding.BucketIdExtractor;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ShardCalculatorTest {

    @Test
    void exposesOnlyTenantBoundRoutingApi() {
        Constructor<ShardCalculator> constructor = assertDoesNotThrow(() ->
                ShardCalculator.class.getConstructor(
                        String.class,
                        io.appform.dropwizard.sharding.sharding.ShardManager.class,
                        BucketIdExtractor.class));
        assertIterableEquals(
                List.of(String.class, io.appform.dropwizard.sharding.sharding.ShardManager.class, BucketIdExtractor.class),
                Arrays.asList(constructor.getParameterTypes()));
        assertEquals(1, ShardCalculator.class.getConstructors().length);

        Method shardId = assertDoesNotThrow(() -> ShardCalculator.class.getMethod("shardId", Object.class));
        assertEquals(int.class, shardId.getReturnType());
        assertTrue(Modifier.isPublic(shardId.getModifiers()));
        assertFalse(Modifier.isStatic(shardId.getModifiers()));

        Method isOnValidShard = assertDoesNotThrow(() -> ShardCalculator.class.getMethod("isOnValidShard", Object.class));
        assertEquals(boolean.class, isOnValidShard.getReturnType());
        assertTrue(Modifier.isPublic(isOnValidShard.getModifiers()));
        assertFalse(Modifier.isStatic(isOnValidShard.getModifiers()));

        assertThrows(NoSuchMethodException.class, () -> ShardCalculator.class.getMethod("shardId", String.class, Object.class));
        assertThrows(NoSuchMethodException.class, () -> ShardCalculator.class.getMethod("isOnValidShard", String.class, Object.class));
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

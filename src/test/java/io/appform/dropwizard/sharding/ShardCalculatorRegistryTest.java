package io.appform.dropwizard.sharding;

import io.appform.dropwizard.sharding.sharding.BalancedShardManager;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShardCalculatorRegistryTest {

    @Test
    void getReturnsRegisteredCalculator() {
        final var calculator = TestAccess.register("t1", new BalancedShardManager(2));
        assertSame(calculator, ShardCalculatorRegistry.get("t1"));
        assertTrue(ShardCalculatorRegistry.contains("t1"));
    }

    @Test
    void unknownTenantFails() {
        final var ex = assertThrows(IllegalArgumentException.class, () -> ShardCalculatorRegistry.get("nope"));
        assertEquals("Unknown tenant: nope", ex.getMessage());
        assertThrows(IllegalArgumentException.class, () -> ShardCalculatorRegistry.get(null));
        assertFalse(ShardCalculatorRegistry.contains("nope"));
        assertFalse(ShardCalculatorRegistry.contains(null));
    }

    @Test
    void duplicateRegistrationFailsAndKeepsOriginal() {
        final var original = TestAccess.register("t1", new BalancedShardManager(2));
        final var ex = assertThrows(IllegalStateException.class,
                () -> TestAccess.register("t1", new BalancedShardManager(4)));
        assertEquals("Shard calculator already registered for tenant: t1", ex.getMessage());
        assertSame(original, ShardCalculatorRegistry.get("t1"));
    }

    @Test
    void allIsImmutableSnapshot() {
        TestAccess.register("t1", new BalancedShardManager(2));
        final var all = ShardCalculatorRegistry.all();
        assertThrows(UnsupportedOperationException.class, all::clear);
        TestAccess.register("t2", new BalancedShardManager(2));
        assertEquals(1, all.size());
        assertEquals(2, ShardCalculatorRegistry.all().size());
    }

    @Test
    void concurrentDuplicateRegistrationHasExactlyOneWinner() throws Exception {
        final int threads = 16;
        final ExecutorService pool = Executors.newFixedThreadPool(threads);
        final var start = new CountDownLatch(1);
        final List<Future<Boolean>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            results.add(pool.submit(() -> {
                start.await();
                try {
                    TestAccess.register("t1", new BalancedShardManager(2));
                    return true;
                } catch (IllegalStateException e) {
                    return false;
                }
            }));
        }
        start.countDown();
        int winners = 0;
        for (Future<Boolean> f : results) {
            winners += f.get(10, TimeUnit.SECONDS) ? 1 : 0;
        }
        pool.shutdownNow();
        assertEquals(1, winners);
    }
}

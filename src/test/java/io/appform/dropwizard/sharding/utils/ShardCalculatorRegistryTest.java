package io.appform.dropwizard.sharding.utils;

import io.appform.dropwizard.sharding.sharding.BalancedShardManager;
import io.appform.dropwizard.sharding.sharding.ShardManager;
import io.appform.dropwizard.sharding.sharding.impl.ConsistentHashBucketIdExtractor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    void batchRegistrationIsPublishedAtomically() throws Exception {
        var oldCalculator = calculatorFor("OLD", 4);
        ShardCalculatorRegistry.register(Map.of("OLD", oldCalculator));

        var batch = new LinkedHashMap<String, ShardCalculator<String>>();
        for (int i = 0; i < 64; i++) {
            var tenantId = "NEW-" + i;
            batch.put(tenantId, calculatorFor(tenantId, 1 << (i % 4)));
        }
        var batchTenantIds = List.copyOf(batch.keySet());
        var firstEntryRequested = new CountDownLatch(1);
        var releaseBatch = new CountDownLatch(1);
        var readersStart = new CountDownLatch(1);
        var partialObserved = new AtomicBoolean(false);
        var blockingBatch = new BlockingBatchMap(batch, firstEntryRequested, releaseBatch);

        ExecutorService executor = Executors.newFixedThreadPool(5);
        try {
            Future<?> registerFuture = executor.submit(() -> {
                try {
                    ShardCalculatorRegistry.register(blockingBatch);
                } finally {
                    releaseBatch.countDown();
                }
            });

            assertTrue(firstEntryRequested.await(5, TimeUnit.SECONDS));

            List<Future<?>> readerFutures = List.of(
                    executor.submit(snapshotReaderTask(readersStart, releaseBatch, batchTenantIds, partialObserved)),
                    executor.submit(snapshotReaderTask(readersStart, releaseBatch, batchTenantIds, partialObserved)),
                    executor.submit(snapshotReaderTask(readersStart, releaseBatch, batchTenantIds, partialObserved)),
                    executor.submit(snapshotReaderTask(readersStart, releaseBatch, batchTenantIds, partialObserved)));

            readersStart.countDown();

            for (int i = 0; i < 100_000 && !partialObserved.get(); i++) {
                Thread.onSpinWait();
            }

            assertFalse(partialObserved.get(), "Expected readers to see only complete snapshots while registration was in progress");

            releaseBatch.countDown();
            registerFuture.get(5, TimeUnit.SECONDS);
            for (Future<?> future : readerFutures) {
                future.get(5, TimeUnit.SECONDS);
            }

            batch.forEach((tenantId, calculator) -> assertSame(calculator, ShardCalculatorRegistry.get(tenantId)));
            assertSame(oldCalculator, ShardCalculatorRegistry.get("OLD"));
        } finally {
            executor.shutdownNow();
        }
    }

    private ShardCalculator<String> calculatorFor(String tenantId, int shardCount) {
        ShardManager shardManager = new BalancedShardManager(shardCount);
        return new ShardCalculator<>(
                tenantId,
                shardManager,
                new ConsistentHashBucketIdExtractor<>(Map.of(tenantId, shardManager)));
    }

    private Callable<ShardCalculator<String>> readerTask(CountDownLatch start) {
        return () -> {
            start.await(5, TimeUnit.SECONDS);
            return ShardCalculatorRegistry.get("TENANT1");
        };
    }

    private Callable<Void> snapshotReaderTask(
            CountDownLatch start,
            CountDownLatch stop,
            List<String> tenantIds,
            AtomicBoolean partialObserved) {
        return () -> {
            start.await(5, TimeUnit.SECONDS);
            var snapshot = registrySnapshot();
            while (stop.getCount() > 0 && !partialObserved.get()) {
                int visibleCount = 0;
                for (String tenantId : tenantIds) {
                    if (snapshot.containsKey(tenantId)) {
                        visibleCount++;
                    }
                }
                if (visibleCount > 0 && visibleCount < tenantIds.size()) {
                    partialObserved.set(true);
                    break;
                }
                Thread.onSpinWait();
            }
            return null;
        };
    }

    @SuppressWarnings("unchecked")
    private Map<String, ShardCalculator<String>> registrySnapshot() {
        try {
            var field = ShardCalculatorRegistry.class.getDeclaredField("calculators");
            field.setAccessible(true);
            return (Map<String, ShardCalculator<String>>) field.get(null);
        } catch (NoSuchFieldException e) {
            try {
                var field = ShardCalculatorRegistry.class.getDeclaredField("REGISTRY");
                field.setAccessible(true);
                return (Map<String, ShardCalculator<String>>) field.get(null);
            } catch (ReflectiveOperationException inner) {
                throw new AssertionError("Unable to inspect registry snapshot", inner);
            }
        } catch (IllegalAccessException e) {
            throw new AssertionError("Unable to inspect registry snapshot", e);
        }
    }

    private static final class BlockingBatchMap extends AbstractMap<String, ShardCalculator<String>> {
        private final LinkedHashMap<String, ShardCalculator<String>> delegate;
        private final CountDownLatch firstEntryRequested;
        private final CountDownLatch releaseBatch;
        private final AtomicInteger entrySetCalls = new AtomicInteger();

        private BlockingBatchMap(
                LinkedHashMap<String, ShardCalculator<String>> delegate,
                CountDownLatch firstEntryRequested,
                CountDownLatch releaseBatch) {
            this.delegate = delegate;
            this.firstEntryRequested = firstEntryRequested;
            this.releaseBatch = releaseBatch;
        }

        @Override
        public Set<Entry<String, ShardCalculator<String>>> entrySet() {
            if (entrySetCalls.incrementAndGet() == 1) {
                return delegate.entrySet();
            }
            return new AbstractSet<>() {
                @Override
                public Iterator<Entry<String, ShardCalculator<String>>> iterator() {
                    var entries = List.copyOf(delegate.entrySet());
                    return new Iterator<>() {
                        private int index;

                        @Override
                        public boolean hasNext() {
                            return index < entries.size();
                        }

                        @Override
                        public Entry<String, ShardCalculator<String>> next() {
                            if (index == 1) {
                                firstEntryRequested.countDown();
                                try {
                                    assertTrue(releaseBatch.await(5, TimeUnit.SECONDS));
                                } catch (InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                    throw new AssertionError(e);
                                }
                            }
                            return entries.get(index++);
                        }
                    };
                }

                @Override
                public int size() {
                    return delegate.size();
                }
            };
        }

        @Override
        public int size() {
            return delegate.size();
        }
    }
}

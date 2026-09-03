package io.appform.dropwizard.sharding.utils;

import io.appform.dropwizard.sharding.sharding.BalancedShardManager;
import io.appform.dropwizard.sharding.sharding.ShardManager;
import io.appform.dropwizard.sharding.sharding.impl.ConsistentHashBucketIdExtractor;
import org.junit.jupiter.api.Test;

import java.util.AbstractMap;
import java.util.AbstractSet;
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
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ShardCalculatorRegistryTest {

    @Test
    void registerTwoCalculatorsAndReturnSameInstancePerTenant() {
        var registry = new ShardCalculatorRegistry();
        var tenant1 = calculatorFor("TENANT1", 4);
        var tenant2 = calculatorFor("TENANT2", 2);

        registry.register(Map.of(
                "TENANT1", tenant1,
                "TENANT2", tenant2));

        assertSame(tenant1, registry.get("TENANT1"));
        assertSame(tenant2, registry.get("TENANT2"));
    }

    @Test
    void separateRegistriesCanRegisterSameTenantIndependently() {
        var firstRegistry = new ShardCalculatorRegistry();
        var secondRegistry = new ShardCalculatorRegistry();
        var firstCalculator = calculatorFor("TENANT1", 4);
        var secondCalculator = calculatorFor("TENANT1", 2);

        firstRegistry.register(Map.of("TENANT1", firstCalculator));
        secondRegistry.register(Map.of("TENANT1", secondCalculator));

        assertSame(firstCalculator, firstRegistry.get("TENANT1"));
        assertSame(secondCalculator, secondRegistry.get("TENANT1"));
    }

    @Test
    void missingTenantThrowsExactError() {
        var registry = new ShardCalculatorRegistry();

        var exception = assertThrows(IllegalStateException.class, () -> registry.get("TENANT1"));

        assertEquals("ShardCalculator has not been registered for tenant: TENANT1", exception.getMessage());
    }

    @Test
    void nullMapRejectsWithoutChangingRegistry() {
        var registry = new ShardCalculatorRegistry();
        var tenant1 = calculatorFor("TENANT1", 4);
        registry.register(Map.of("TENANT1", tenant1));

        assertThrows(NullPointerException.class, () -> registry.register(null));

        assertSame(tenant1, registry.get("TENANT1"));
    }

    @Test
    void nullTenantIdRejectsBeforePublishingAnyEntry() {
        var registry = new ShardCalculatorRegistry();
        var valid = calculatorFor("TENANT2", 2);
        var calculators = new LinkedHashMap<String, ShardCalculator<String>>();
        calculators.put("TENANT1", calculatorFor("TENANT1", 4));
        calculators.put(null, valid);

        assertThrows(NullPointerException.class, () -> registry.register(calculators));

        assertThrows(IllegalStateException.class, () -> registry.get("TENANT1"));
        assertThrows(IllegalStateException.class, () -> registry.get("TENANT2"));
    }

    @Test
    void nullCalculatorRejectsBeforePublishingAnyEntry() {
        var registry = new ShardCalculatorRegistry();
        var calculators = new LinkedHashMap<String, ShardCalculator<String>>();
        calculators.put("TENANT1", calculatorFor("TENANT1", 4));
        calculators.put("TENANT2", null);

        assertThrows(NullPointerException.class, () -> registry.register(calculators));

        assertThrows(IllegalStateException.class, () -> registry.get("TENANT1"));
        assertThrows(IllegalStateException.class, () -> registry.get("TENANT2"));
    }

    @Test
    void nullTenantLookupRejectsClearly() {
        var registry = new ShardCalculatorRegistry();

        var exception = assertThrows(NullPointerException.class, () -> registry.get(null));

        assertEquals("tenantId", exception.getMessage());
    }

    @Test
    void duplicateBatchDoesNotPublishAnyNewTenant() {
        var registry = new ShardCalculatorRegistry();
        var existing = calculatorFor("TENANT1", 4);
        registry.register(Map.of("TENANT1", existing));

        var duplicateBatch = new LinkedHashMap<String, ShardCalculator<String>>();
        duplicateBatch.put("TENANT2", calculatorFor("TENANT2", 8));
        duplicateBatch.put("TENANT1", calculatorFor("TENANT1", 2));

        var exception = assertThrows(IllegalStateException.class, () -> registry.register(duplicateBatch));

        assertEquals("ShardCalculator already registered for tenant: TENANT1", exception.getMessage());
        assertSame(existing, registry.get("TENANT1"));
        assertThrows(IllegalStateException.class, () -> registry.get("TENANT2"));
    }

    @Test
    void registerUsesOneStableBatchWhenCallerMapChangesBetweenTraversals() {
        var registry = new ShardCalculatorRegistry();
        var existing = calculatorFor("TENANT1", 4);
        var replacement = calculatorFor("TENANT1", 2);
        var newCalculator = calculatorFor("TENANT2", 8);
        registry.register(Map.of("TENANT1", existing));

        var batch = new LinkedHashMap<String, ShardCalculator<String>>();
        batch.put("TENANT2", newCalculator);
        var changingBatch = new TraversalMutatingMap(
                batch,
                3,
                () -> batch.put("TENANT1", replacement));

        registry.register(changingBatch);

        assertEquals(1, changingBatch.traversalCount());
        assertSame(existing, registry.get("TENANT1"));
        assertSame(newCalculator, registry.get("TENANT2"));
    }

    @Test
    void callerMutationAfterRegisterDoesNotAffectRegistryOrDuplicateProtection() {
        var registry = new ShardCalculatorRegistry();
        var registered = calculatorFor("TENANT1", 4);
        var replacement = calculatorFor("TENANT1", 2);
        var later = calculatorFor("TENANT2", 8);
        var batch = new LinkedHashMap<String, ShardCalculator<String>>();
        batch.put("TENANT1", registered);

        registry.register(batch);
        batch.put("TENANT1", replacement);
        batch.put("TENANT2", later);

        assertSame(registered, registry.get("TENANT1"));
        assertThrows(IllegalStateException.class, () -> registry.get("TENANT2"));
        var exception = assertThrows(
                IllegalStateException.class,
                () -> registry.register(Map.of("TENANT1", replacement)));
        assertEquals("ShardCalculator already registered for tenant: TENANT1", exception.getMessage());
        assertSame(registered, registry.get("TENANT1"));
    }

    @Test
    void clearAffectsOnlyReceiver() {
        var firstRegistry = new ShardCalculatorRegistry();
        var secondRegistry = new ShardCalculatorRegistry();
        var secondCalculator = calculatorFor("TENANT1", 8);
        firstRegistry.register(Map.of(
                "TENANT1", calculatorFor("TENANT1", 4),
                "TENANT2", calculatorFor("TENANT2", 2)));
        secondRegistry.register(Map.of("TENANT1", secondCalculator));

        firstRegistry.clear();

        assertThrows(IllegalStateException.class, () -> firstRegistry.get("TENANT1"));
        assertThrows(IllegalStateException.class, () -> firstRegistry.get("TENANT2"));
        assertSame(secondCalculator, secondRegistry.get("TENANT1"));
    }

    @Test
    void concurrentReadsAfterPublicationReturnSameCalculator() throws Exception {
        var registry = new ShardCalculatorRegistry();
        var calculator = calculatorFor("TENANT1", 4);
        registry.register(Map.of("TENANT1", calculator));

        ExecutorService executor = Executors.newFixedThreadPool(4);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Callable<ShardCalculator<String>>> tasks = List.of(
                    readerTask(registry, start),
                    readerTask(registry, start),
                    readerTask(registry, start),
                    readerTask(registry, start));
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
        var registry = new ShardCalculatorRegistry();
        var oldCalculator = calculatorFor("OLD", 4);
        registry.register(Map.of("OLD", oldCalculator));

        var batch = new LinkedHashMap<String, ShardCalculator<String>>();
        batch.put("NEW-FIRST", calculatorFor("NEW-FIRST", 2));
        batch.put("NEW-MIDDLE", calculatorFor("NEW-MIDDLE", 4));
        batch.put("NEW-LAST", calculatorFor("NEW-LAST", 8));
        var firstEntryTransferred = new CountDownLatch(1);
        var continuePublication = new CountDownLatch(1);
        var pausingBatch = new PublicationPausingMap(batch, firstEntryTransferred, continuePublication);

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> registerFuture = executor.submit(() -> registry.register(pausingBatch));

            assertTrue(firstEntryTransferred.await(5, TimeUnit.SECONDS));
            boolean firstVisible = isRegistered(registry, "NEW-FIRST");
            boolean lastVisible = isRegistered(registry, "NEW-LAST");
            assertEquals(
                    firstVisible,
                    lastVisible,
                    "A reader must not observe one tenant from a batch while another remains missing");

            continuePublication.countDown();
            registerFuture.get(5, TimeUnit.SECONDS);

            batch.forEach((tenantId, calculator) -> assertSame(calculator, registry.get(tenantId)));
            assertSame(oldCalculator, registry.get("OLD"));
        } finally {
            continuePublication.countDown();
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

    private Callable<ShardCalculator<String>> readerTask(
            ShardCalculatorRegistry registry,
            CountDownLatch start) {
        return () -> {
            start.await(5, TimeUnit.SECONDS);
            return registry.get("TENANT1");
        };
    }

    private boolean isRegistered(ShardCalculatorRegistry registry, String tenantId) {
        try {
            registry.get(tenantId);
            return true;
        } catch (IllegalStateException ignored) {
            return false;
        }
    }

    private static final class PublicationPausingMap extends AbstractMap<String, ShardCalculator<String>> {
        private static final int BATCH_SNAPSHOT_TRAVERSAL = 1;

        private final LinkedHashMap<String, ShardCalculator<String>> delegate;
        private final CountDownLatch firstEntryTransferred;
        private final CountDownLatch continuePublication;
        private final AtomicInteger entrySetCalls = new AtomicInteger();

        private PublicationPausingMap(
                LinkedHashMap<String, ShardCalculator<String>> delegate,
                CountDownLatch firstEntryTransferred,
                CountDownLatch continuePublication) {
            this.delegate = delegate;
            this.firstEntryTransferred = firstEntryTransferred;
            this.continuePublication = continuePublication;
        }

        @Override
        public Set<Entry<String, ShardCalculator<String>>> entrySet() {
            if (entrySetCalls.incrementAndGet() != BATCH_SNAPSHOT_TRAVERSAL) {
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
                                firstEntryTransferred.countDown();
                                try {
                                    assertTrue(continuePublication.await(5, TimeUnit.SECONDS));
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

    private static final class TraversalMutatingMap extends AbstractMap<String, ShardCalculator<String>> {
        private final LinkedHashMap<String, ShardCalculator<String>> delegate;
        private final int mutationTraversal;
        private final Runnable mutation;
        private final AtomicInteger entrySetCalls = new AtomicInteger();

        private TraversalMutatingMap(
                LinkedHashMap<String, ShardCalculator<String>> delegate,
                int mutationTraversal,
                Runnable mutation) {
            this.delegate = delegate;
            this.mutationTraversal = mutationTraversal;
            this.mutation = mutation;
        }

        @Override
        public Set<Entry<String, ShardCalculator<String>>> entrySet() {
            if (entrySetCalls.incrementAndGet() == mutationTraversal) {
                mutation.run();
            }
            return delegate.entrySet();
        }

        private int traversalCount() {
            return entrySetCalls.get();
        }
    }
}

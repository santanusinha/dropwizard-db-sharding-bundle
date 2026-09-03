package io.appform.dropwizard.sharding.utils;

import io.appform.dropwizard.sharding.sharding.BalancedShardManager;
import io.appform.dropwizard.sharding.sharding.ShardManager;
import io.appform.dropwizard.sharding.sharding.impl.ConsistentHashBucketIdExtractor;
import org.junit.jupiter.api.Test;

import java.util.AbstractMap;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ShardCalculatorRegistryTest {

    private static final int PUBLICATION_RACE_ITERATIONS = 5;
    private static final int PUBLICATION_BATCH_SIZE = 50_000;
    private static final int PUBLICATION_READER_COUNT = 4;

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
        ExecutorService executor = Executors.newFixedThreadPool(PUBLICATION_READER_COUNT);
        try {
            for (int iteration = 0; iteration < PUBLICATION_RACE_ITERATIONS; iteration++) {
                assertBatchPublicationIsAtomic(executor, iteration);
            }
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    private void assertBatchPublicationIsAtomic(
            ExecutorService executor,
            int iteration) throws Exception {
        var registry = new ShardCalculatorRegistry();
        var oldCalculator = calculatorFor("OLD", 4);
        var batchCalculator = calculatorFor("BATCH", 8);
        registry.register(Map.of("OLD", oldCalculator));

        var batch = new LinkedHashMap<String, ShardCalculator<String>>();
        for (int index = 0; index < PUBLICATION_BATCH_SIZE; index++) {
            batch.put(batchTenantId(iteration, index), batchCalculator);
        }
        List<String> observedTenants = List.of(
                batchTenantId(iteration, 0),
                batchTenantId(iteration, PUBLICATION_BATCH_SIZE / 4),
                batchTenantId(iteration, PUBLICATION_BATCH_SIZE / 2),
                batchTenantId(iteration, PUBLICATION_BATCH_SIZE * 3 / 4),
                batchTenantId(iteration, PUBLICATION_BATCH_SIZE - 1));
        var readersReady = new CountDownLatch(PUBLICATION_READER_COUNT);
        var startReaders = new CountDownLatch(1);
        var readersObserving = new CountDownLatch(PUBLICATION_READER_COUNT);
        var registrationComplete = new AtomicBoolean();
        var partialPublication = new AtomicReference<String>();
        List<Future<Void>> readers = IntStream.range(0, PUBLICATION_READER_COUNT)
                .mapToObj(ignored -> executor.submit(publicationObserver(
                        registry,
                        observedTenants,
                        readersReady,
                        startReaders,
                        readersObserving,
                        registrationComplete,
                        partialPublication)))
                .collect(Collectors.toList());

        assertTrue(readersReady.await(5, TimeUnit.SECONDS));
        startReaders.countDown();
        assertTrue(readersObserving.await(5, TimeUnit.SECONDS));
        try {
            registry.register(batch);
        } finally {
            registrationComplete.set(true);
        }
        for (Future<Void> reader : readers) {
            reader.get(5, TimeUnit.SECONDS);
        }

        assertNull(
                partialPublication.get(),
                "A reader must not observe one tenant from a batch while another remains missing");
        for (String tenantId : observedTenants) {
            assertSame(batchCalculator, registry.get(tenantId));
        }
        assertSame(oldCalculator, registry.get("OLD"));
    }

    private Callable<Void> publicationObserver(
            ShardCalculatorRegistry registry,
            List<String> observedTenants,
            CountDownLatch readersReady,
            CountDownLatch startReaders,
            CountDownLatch readersObserving,
            AtomicBoolean registrationComplete,
            AtomicReference<String> partialPublication) {
        return () -> {
            readersReady.countDown();
            assertTrue(startReaders.await(5, TimeUnit.SECONDS));
            readersObserving.countDown();
            do {
                String visibleTenant = null;
                for (String tenantId : observedTenants) {
                    if (isRegistered(registry, tenantId)) {
                        visibleTenant = tenantId;
                    } else if (visibleTenant != null) {
                        partialPublication.compareAndSet(
                                null,
                                visibleTenant + " was visible while " + tenantId + " was absent");
                        return null;
                    }
                }
            } while (!registrationComplete.get() && partialPublication.get() == null);
            return null;
        };
    }

    private String batchTenantId(int iteration, int index) {
        return "NEW-" + iteration + "-" + index;
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

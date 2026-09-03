# Per-Tenant ShardCalculator Registry Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Create one tenant-bound shard calculator per configured tenant, publish calculators through a bundle-owned registry, and remove shard-manager and calculator dependencies from DAO APIs.

**Architecture:** Each `MultiTenantDBShardingBundleBase` owns one final `ShardCalculatorRegistry`. The bundle builds tenant calculators locally and publishes them to its registry in one immutable snapshot after initialization succeeds. DAOs receive that registry, query it for every routing operation, and never cache calculators.

**Tech Stack:** Java 11+, Maven, JUnit 5, Guava, Dropwizard, Hibernate, Mockito

## Global Constraints

- Each bundle owns its registry; registrations never cross bundle boundaries.
- Registration publishes one volatile immutable snapshot after the complete batch validates.
- Duplicate tenant IDs fail only within one registry.
- DAO constructors accept `ShardCalculatorRegistry` as their only routing dependency.
- DAOs never receive or cache shard managers, calculators, calculator maps, resolvers, or scope tokens.
- Direct DAO tests create isolated registries with `ShardCalculatorTestUtils.registryFor(...)`.
- Preserve TDD, focused test commands, compile-safe task ordering, and one commit per task.

---

## File Map

| Action | File | Responsibility |
| --- | --- | --- |
| Create | `src/main/java/io/appform/dropwizard/sharding/utils/ShardCalculatorRegistry.java` | Store one bundle's tenant calculators and publish registration batches atomically |
| Create | `src/test/java/io/appform/dropwizard/sharding/utils/ShardCalculatorRegistryTest.java` | Verify instance isolation, errors, concurrency, and atomic publication |
| Create | `src/test/java/io/appform/dropwizard/sharding/utils/ShardCalculatorTest.java` | Verify tenant-bound routing |
| Create | `src/test/java/io/appform/dropwizard/sharding/utils/ShardCalculatorTestUtils.java` | Build isolated registries for direct DAO tests |
| Modify | `src/main/java/io/appform/dropwizard/sharding/utils/ShardCalculator.java` | Convert calculator ownership from a tenant map to one tenant |
| Modify | `src/main/java/io/appform/dropwizard/sharding/MultiTenantDBShardingBundleBase.java` | Own, populate, expose, and pass one registry |
| Modify | `src/main/java/io/appform/dropwizard/sharding/DBShardingBundleBase.java` | Expose the delegate bundle's default-namespace calculator |
| Modify | `src/main/java/io/appform/dropwizard/sharding/dao/MultiTenantLookupDao.java` | Resolve tenant calculators through the passed registry |
| Modify | `src/main/java/io/appform/dropwizard/sharding/dao/MultiTenantCacheableLookupDao.java` | Pass and validate through the registry |
| Modify | `src/main/java/io/appform/dropwizard/sharding/dao/LookupDao.java` | Remove DAO-level calculator access |
| Modify | `src/main/java/io/appform/dropwizard/sharding/dao/MultiTenantRelationalDao.java` | Resolve tenant calculators through the passed registry |
| Modify | `src/main/java/io/appform/dropwizard/sharding/dao/MultiTenantCacheableRelationalDao.java` | Pass and validate through the registry |
| Modify | `src/main/java/io/appform/dropwizard/sharding/dao/RelationalDao.java` | Remove DAO-level calculator access |
| Modify | `src/main/java/io/appform/dropwizard/sharding/dao/WrapperDao.java` | Resolve its namespace calculator for every `forParent` call |
| Delete | `src/main/java/io/appform/dropwizard/sharding/dao/ShardedDao.java` | Remove the obsolete DAO calculator contract |
| Modify | `src/test/java/io/appform/dropwizard/sharding/BundleBasedTestBase.java` | Remove shared registry cleanup |
| Modify | `src/test/java/io/appform/dropwizard/sharding/MultiTenantBundleBasedTestBase.java` | Remove shared registry cleanup |
| Modify | `src/test/java/io/appform/dropwizard/sharding/BundleMvccSnapshotTest.java` | Cover two live default-namespace bundles with independent calculators |
| Modify | DAO tests listed in Tasks 4-6 | Create and pass isolated registry instances |

## Task 1: Add tenant-bound calculator behavior

**Files:**
- Create: `src/test/java/io/appform/dropwizard/sharding/utils/ShardCalculatorTest.java`
- Modify: `src/main/java/io/appform/dropwizard/sharding/utils/ShardCalculator.java`

- [ ] **Step 1: Write the failing tenant-bound calculator tests**

Create `ShardCalculatorTest` with a recording extractor and managers that have different shard counts:

```java
package io.appform.dropwizard.sharding.utils;

import io.appform.dropwizard.sharding.sharding.BalancedShardManager;
import io.appform.dropwizard.sharding.sharding.BucketIdExtractor;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShardCalculatorTest {

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

        assertEquals(3, calculator.shardId("key"));
        assertTrue(calculator.isOnValidShard("key"));
    }

    @Test
    void calculatorsUseIndependentManagers() {
        BucketIdExtractor<String> extractor = (tenantId, key) -> 750;
        var twoShardCalculator = new ShardCalculator<>(
                "TENANT1",
                new BalancedShardManager(2),
                extractor);
        var fourShardCalculator = new ShardCalculator<>(
                "TENANT2",
                new BalancedShardManager(4),
                extractor);

        assertEquals(1, twoShardCalculator.shardId("key"));
        assertEquals(3, fourShardCalculator.shardId("key"));
        assertFalse(twoShardCalculator == fourShardCalculator);
    }
}
```

- [ ] **Step 2: Run the test and verify the new constructor is missing**

Run:

```bash
mvn -q -o -Dtest=ShardCalculatorTest test
```

Expected: test compilation fails because `ShardCalculator(String, ShardManager, BucketIdExtractor)` does not exist.

- [ ] **Step 3: Add tenant-bound state without breaking existing callers yet**

Temporarily retain the old map constructor and tenant-parameter methods so the repository compiles while DAO families migrate. Add the new constructor and make key-only methods use the bound tenant when present:

```java
private final String tenantId;
private final Map<String, ShardManager> shardManagers;
private final BucketIdExtractor<T> extractor;

public ShardCalculator(
        String tenantId,
        ShardManager shardManager,
        BucketIdExtractor<T> extractor) {
    this.tenantId = Objects.requireNonNull(tenantId, "tenantId");
    this.shardManagers = Map.of(
            tenantId,
            Objects.requireNonNull(shardManager, "shardManager"));
    this.extractor = Objects.requireNonNull(extractor, "extractor");
}

public ShardCalculator(
        Map<String, ShardManager> shardManagers,
        BucketIdExtractor<T> extractor) {
    this.tenantId = null;
    this.shardManagers = Objects.requireNonNull(shardManagers, "shardManagers");
    this.extractor = Objects.requireNonNull(extractor, "extractor");
}

public int shardId(T key) {
    return shardId(tenantId == null
            ? DBShardingBundleBase.DEFAULT_NAMESPACE
            : tenantId, key);
}

public boolean isOnValidShard(T key) {
    return isOnValidShard(tenantId == null
            ? DBShardingBundleBase.DEFAULT_NAMESPACE
            : tenantId, key);
}
```

Add `java.util.Objects`. Do not deprecate the transitional APIs; Task 7 deletes them before completion.

- [ ] **Step 4: Run focused tests**

Run:

```bash
mvn -q -o -Dtest=ShardCalculatorTest test
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/appform/dropwizard/sharding/utils/ShardCalculator.java \
        src/test/java/io/appform/dropwizard/sharding/utils/ShardCalculatorTest.java
git commit -m "refactor: add tenant-bound shard calculators" \
  -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

## Task 2: Add an atomic instance registry

**Files:**
- Create: `src/main/java/io/appform/dropwizard/sharding/utils/ShardCalculatorRegistry.java`
- Create: `src/test/java/io/appform/dropwizard/sharding/utils/ShardCalculatorRegistryTest.java`
- Create: `src/test/java/io/appform/dropwizard/sharding/utils/ShardCalculatorTestUtils.java`

**Interfaces:**
- Consumes: `ShardCalculator(String, ShardManager, BucketIdExtractor)` from Task 1.
- Produces: `new ShardCalculatorRegistry()`, `registry.register(Map<String, ShardCalculator<String>>)`, `registry.get(String)`, test-only `registry.clear()`, and `ShardCalculatorTestUtils.registryFor(Map<String, ShardManager>)`.

- [ ] **Step 1: Write failing instance-registry tests**

Create a fresh registry in each test. Cover retrieval, missing tenants, invalid
input, duplicate rejection, clearing, concurrent reads, atomic batch
visibility, and independent use of the same tenant ID:

```java
class ShardCalculatorRegistryTest {

    @Test
    void twoRegistriesCanRegisterTheSameTenantIndependently() {
        var firstRegistry = new ShardCalculatorRegistry();
        var secondRegistry = new ShardCalculatorRegistry();
        var firstCalculator = calculatorFor("TENANT1", 2);
        var secondCalculator = calculatorFor("TENANT1", 4);

        firstRegistry.register(Map.of("TENANT1", firstCalculator));
        secondRegistry.register(Map.of("TENANT1", secondCalculator));

        assertSame(firstCalculator, firstRegistry.get("TENANT1"));
        assertSame(secondCalculator, secondRegistry.get("TENANT1"));
        assertNotSame(
                firstRegistry.get("TENANT1"),
                secondRegistry.get("TENANT1"));
    }

    @Test
    void duplicateBatchPublishesNothingInThatRegistry() {
        var registry = new ShardCalculatorRegistry();
        var existing = calculatorFor("TENANT1", 2);
        registry.register(Map.of("TENANT1", existing));

        var error = assertThrows(
                IllegalStateException.class,
                () -> registry.register(Map.of(
                        "TENANT1", calculatorFor("TENANT1", 4),
                        "TENANT2", calculatorFor("TENANT2", 4))));

        assertEquals(
                "ShardCalculator already registered for tenant: TENANT1",
                error.getMessage());
        assertSame(existing, registry.get("TENANT1"));
        assertThrows(
                IllegalStateException.class,
                () -> registry.get("TENANT2"));
    }

    @Test
    void missingTenantIncludesTenantId() {
        var registry = new ShardCalculatorRegistry();

        var error = assertThrows(
                IllegalStateException.class,
                () -> registry.get("UNKNOWN"));

        assertEquals(
                "ShardCalculator has not been registered for tenant: UNKNOWN",
                error.getMessage());
    }

    @Test
    void rejectsNullRegistrationInputsBeforePublishing() {
        var registry = new ShardCalculatorRegistry();
        assertThrows(NullPointerException.class, () -> registry.register(null));

        var calculators = new HashMap<String, ShardCalculator<String>>();
        calculators.put(null, calculatorFor("TENANT1", 2));
        assertThrows(
                NullPointerException.class,
                () -> registry.register(calculators));

        calculators.clear();
        calculators.put("TENANT1", null);
        assertThrows(
                NullPointerException.class,
                () -> registry.register(calculators));

        assertThrows(
                IllegalStateException.class,
                () -> registry.get("TENANT1"));
    }

    @Test
    void clearRemovesOnlyThatRegistryEntries() {
        var firstRegistry = new ShardCalculatorRegistry();
        var secondRegistry = new ShardCalculatorRegistry();
        var secondCalculator = calculatorFor("TENANT1", 4);
        firstRegistry.register(Map.of(
                "TENANT1",
                calculatorFor("TENANT1", 2)));
        secondRegistry.register(Map.of("TENANT1", secondCalculator));

        firstRegistry.clear();

        assertThrows(
                IllegalStateException.class,
                () -> firstRegistry.get("TENANT1"));
        assertSame(secondCalculator, secondRegistry.get("TENANT1"));
    }

    @Test
    void concurrentReadsReturnPublishedCalculator() throws Exception {
        var registry = new ShardCalculatorRegistry();
        var calculator = calculatorFor("TENANT1", 2);
        registry.register(Map.of("TENANT1", calculator));
        var executor = Executors.newFixedThreadPool(4);
        try {
            var reads = IntStream.range(0, 20)
                    .mapToObj(ignored -> executor.submit(
                            () -> registry.get("TENANT1")))
                    .collect(Collectors.toList());
            for (var read : reads) {
                assertSame(calculator, read.get(5, TimeUnit.SECONDS));
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
        var pausingBatch = new PublicationPausingMap(
                batch,
                firstEntryTransferred,
                continuePublication);
        var executor = Executors.newSingleThreadExecutor();
        try {
            var registration = executor.submit(
                    () -> registry.register(pausingBatch));

            assertTrue(firstEntryTransferred.await(5, TimeUnit.SECONDS));
            boolean firstVisible = isRegistered(
                    registry,
                    "NEW-FIRST");
            boolean lastVisible = isRegistered(registry, "NEW-LAST");
            assertEquals(
                    firstVisible,
                    lastVisible,
                    "A reader must not observe one tenant from a batch "
                            + "while another remains missing");

            continuePublication.countDown();
            registration.get(5, TimeUnit.SECONDS);
            batch.forEach((tenantId, calculator) -> assertSame(
                    calculator,
                    registry.get(tenantId)));
            assertSame(oldCalculator, registry.get("OLD"));
        } finally {
            continuePublication.countDown();
            executor.shutdownNow();
        }
    }
}
```

Retain the existing `PublicationPausingMap`, but pass the registry into
`isRegistered`:

```java
private boolean isRegistered(
        ShardCalculatorRegistry registry,
        String tenantId) {
    try {
        registry.get(tenantId);
        return true;
    } catch (IllegalStateException ignored) {
        return false;
    }
}
```

The pausing iterator must still stop during the batch-copy traversal. Against
entry-by-entry publication, the first tenant becomes visible while the last is
absent. Against one immutable-snapshot assignment, both remain absent until
the copy completes. Add the imports required by these tests.

- [ ] **Step 2: Run the registry tests and verify the instance API is missing**

Run:

```bash
mvn -q -o -Dtest=ShardCalculatorRegistryTest test
```

Expected: test compilation fails because the constructible instance registry
and its instance methods do not exist.

- [ ] **Step 3: Implement the bundle-local registry type**

Create a normal final class with instance state:

```java
public final class ShardCalculatorRegistry {

    private volatile Map<String, ShardCalculator<String>> calculators =
            Map.of();

    public synchronized void register(
            Map<String, ShardCalculator<String>> calculators) {
        Objects.requireNonNull(calculators, "calculators");
        calculators.forEach((tenantId, calculator) -> {
            Objects.requireNonNull(tenantId, "tenantId");
            Objects.requireNonNull(calculator, "calculator");
        });
        Map<String, ShardCalculator<String>> current = this.calculators;
        calculators.keySet().forEach(tenantId -> {
            if (current.containsKey(tenantId)) {
                throw new IllegalStateException(
                        "ShardCalculator already registered for tenant: "
                                + tenantId);
            }
        });
        Map<String, ShardCalculator<String>> updated =
                new HashMap<>(current);
        updated.putAll(calculators);
        this.calculators = Map.copyOf(updated);
    }

    public ShardCalculator<String> get(String tenantId) {
        Map<String, ShardCalculator<String>> snapshot = calculators;
        var calculator = snapshot.get(tenantId);
        if (calculator == null) {
            throw new IllegalStateException(
                    "ShardCalculator has not been registered for tenant: "
                            + tenantId);
        }
        return calculator;
    }

    @VisibleForTesting
    public synchronized void clear() {
        calculators = Map.of();
    }
}
```

Registration validates one captured snapshot, builds an immutable merged copy,
and publishes it with one volatile assignment. `get` captures one snapshot.
`clear` affects only the receiver and exists for isolated tests that need to
change registry contents between two DAO operations.

- [ ] **Step 4: Add the isolated test-registry helper**

Create:

```java
public final class ShardCalculatorTestUtils {

    private ShardCalculatorTestUtils() {
    }

    public static ShardCalculatorRegistry registryFor(
            Map<String, ShardManager> shardManagers) {
        var registry = new ShardCalculatorRegistry();
        var calculators = shardManagers.entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        entry -> new ShardCalculator<>(
                                entry.getKey(),
                                entry.getValue(),
                                new ConsistentHashBucketIdExtractor<>(
                                        Map.of(
                                                entry.getKey(),
                                                entry.getValue())))));
        registry.register(calculators);
        return registry;
    }
}
```

Keep this helper under `src/test`; production code must not depend on it.

- [ ] **Step 5: Run focused tests**

Run:

```bash
mvn -q -o -Dtest=ShardCalculatorRegistryTest,ShardCalculatorTest test
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/io/appform/dropwizard/sharding/utils/ShardCalculatorRegistry.java \
        src/test/java/io/appform/dropwizard/sharding/utils/ShardCalculatorRegistryTest.java \
        src/test/java/io/appform/dropwizard/sharding/utils/ShardCalculatorTestUtils.java
git commit -m "feat: add bundle-local shard calculator registry" \
  -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

## Task 3: Make each bundle own its registry

**Files:**
- Modify: `src/main/java/io/appform/dropwizard/sharding/MultiTenantDBShardingBundleBase.java:85-290`
- Modify: `src/main/java/io/appform/dropwizard/sharding/DBShardingBundleBase.java:120-220`
- Modify: `src/test/java/io/appform/dropwizard/sharding/MultiTenantDBShardingBundleTestBase.java`
- Modify: `src/test/java/io/appform/dropwizard/sharding/DBShardingBundleTestBase.java`
- Modify: `src/test/java/io/appform/dropwizard/sharding/BundleBasedTestBase.java`
- Modify: `src/test/java/io/appform/dropwizard/sharding/MultiTenantBundleBasedTestBase.java`
- Modify: `src/test/java/io/appform/dropwizard/sharding/BundleMvccSnapshotTest.java`

**Interfaces:**
- Consumes: the registry instance API from Task 2.
- Produces: one final registry per `MultiTenantDBShardingBundleBase`,
  `getShardCalculator(String tenantId)`, and the single-tenant
  `getShardCalculator()` accessor.

- [ ] **Step 1: Rewrite bundle tests for instance ownership**

In `MultiTenantDBShardingBundleTestBase`, keep the distinct-calculator
assertion but remove class-qualified registry access:

```java
@Test
void registersOneCalculatorPerTenant() {
    var bundle = getBundle();
    bundle.initialize(bootstrap);
    bundle.run(testConfig, environment);

    var tenant1 = bundle.getShardCalculator("TENANT1");
    var tenant2 = bundle.getShardCalculator("TENANT2");

    assertNotSame(tenant1, tenant2);
    assertSame(tenant1, bundle.getShardCalculator("TENANT1"));
    assertSame(tenant2, bundle.getShardCalculator("TENANT2"));
}
```

In the existing failed-initialization test, query the failed bundle:

```java
assertThrows(
        IllegalStateException.class,
        () -> bundle.getShardCalculator("TENANT1"));
assertThrows(
        IllegalStateException.class,
        () -> bundle.getShardCalculator("TENANT2"));
```

In `DBShardingBundleTestBase`, rewrite the default accessor test:

```java
@Test
void exposesDefaultNamespaceCalculator() {
    var bundle = getBundle();
    bundle.initialize(bootstrap);
    bundle.run(testConfig, environment);

    assertSame(
            bundle.getShardCalculator(),
            bundle.getShardCalculator());
}
```

- [ ] **Step 2: Add two-live-bundle regression coverage**

In `BundleMvccSnapshotTest`, retain both initialized bundles as fields:

```java
private DBShardingBundleBase<TestConfig> writeBundle;
private DBShardingBundleBase<TestConfig> readBundle;
```

Assign these fields in `setUp()` instead of local variables, then add:

```java
@Test
void twoLiveDefaultNamespaceBundlesOwnIndependentCalculators() {
    assertEquals(
            DBShardingBundleBase.DEFAULT_NAMESPACE,
            writeBundle.getDbNamespace());
    assertEquals(
            DBShardingBundleBase.DEFAULT_NAMESPACE,
            readBundle.getDbNamespace());
    assertNotSame(
            writeBundle.getShardCalculator(),
            readBundle.getShardCalculator());
}
```

This test proves that two live bundles can expose different calculator
instances for the same namespace without registration collision. Keep both
existing MVCC tests unchanged.

- [ ] **Step 3: Run bundle tests and verify ownership is missing**

Run:

```bash
mvn -q -o \
  -Dtest=MultiTenantBalancedDBShardingBundleWithEntityTest,BalancedDBShardingBundleWithEntityTest,BundleMvccSnapshotTest \
  test
```

Expected: test compilation fails because the bundle-owned accessor and
registration behavior do not exist yet.

- [ ] **Step 4: Add the final registry field and delayed registration**

In `MultiTenantDBShardingBundleBase`, add:

```java
private final ShardCalculatorRegistry shardCalculatorRegistry =
        new ShardCalculatorRegistry();
```

Keep calculator construction local to `run()`:

```java
final Map<String, ShardCalculator<String>> shardCalculators =
        Maps.newHashMap();
```

After each tenant's `ShardManager` is created, build but do not publish:

```java
shardCalculators.put(
        tenantId,
        new ShardCalculator<>(
                tenantId,
                shardManager,
                new ConsistentHashBucketIdExtractor<>(
                        Map.of(tenantId, shardManager))));
```

After `tenantedConfig.getTenants().forEach(...)` completes, publish the batch
before `registerBucketIdExtractor(...)`:

```java
shardCalculatorRegistry.register(shardCalculators);
registerBucketIdExtractor(this.shardManagers);
```

Do not publish inside the tenant loop. If any tenant fails, the local map is
discarded and the bundle's registry remains unchanged.

- [ ] **Step 5: Delegate bundle accessors to the owned instance**

In `MultiTenantDBShardingBundleBase`:

```java
public ShardCalculator<String> getShardCalculator(String tenantId) {
    return shardCalculatorRegistry.get(tenantId);
}
```

Keep `DBShardingBundleBase`:

```java
public ShardCalculator<String> getShardCalculator() {
    return delegate.getShardCalculator(dbNamespace);
}
```

Tasks 4-6 will update each DAO factory to pass
`shardCalculatorRegistry`. Until then, retain the current constructor
arguments so this task compiles.

- [ ] **Step 6: Remove shared test cleanup**

Delete the registry imports and `@AfterEach` cleanup methods from
`BundleBasedTestBase` and `MultiTenantBundleBasedTestBase`. Bundle tests now
receive fresh registry state from each new bundle instance and need no shared
teardown.

- [ ] **Step 7: Run focused bundle coverage**

Run:

```bash
mvn -q -o \
  -Dtest=MultiTenantBalancedDBShardingBundleWithEntityTest,BalancedDBShardingBundleWithEntityTest,BundleMvccSnapshotTest \
  test
```

Expected: PASS, including both existing `BundleMvccSnapshotTest` MVCC cases
and `twoLiveDefaultNamespaceBundlesOwnIndependentCalculators`.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/io/appform/dropwizard/sharding/MultiTenantDBShardingBundleBase.java \
        src/main/java/io/appform/dropwizard/sharding/DBShardingBundleBase.java \
        src/test/java/io/appform/dropwizard/sharding/MultiTenantDBShardingBundleTestBase.java \
        src/test/java/io/appform/dropwizard/sharding/DBShardingBundleTestBase.java \
        src/test/java/io/appform/dropwizard/sharding/BundleBasedTestBase.java \
        src/test/java/io/appform/dropwizard/sharding/MultiTenantBundleBasedTestBase.java \
        src/test/java/io/appform/dropwizard/sharding/BundleMvccSnapshotTest.java
git commit -m "refactor: scope calculator registry to bundles" \
  -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

## Task 4: Migrate lookup DAO routing

**Files:**
- Modify: `src/main/java/io/appform/dropwizard/sharding/dao/MultiTenantLookupDao.java`
- Modify: `src/main/java/io/appform/dropwizard/sharding/dao/MultiTenantCacheableLookupDao.java`
- Modify: `src/main/java/io/appform/dropwizard/sharding/dao/LookupDao.java`
- Modify: `src/main/java/io/appform/dropwizard/sharding/MultiTenantDBShardingBundleBase.java:212-240`
- Modify tests:
  - `src/test/java/io/appform/dropwizard/sharding/dao/MultiTenantLookupDaoTest.java`
  - `src/test/java/io/appform/dropwizard/sharding/dao/MultiTenantCacheableLookupDaoTest.java`
  - `src/test/java/io/appform/dropwizard/sharding/dao/LookupDaoTest.java`
  - `src/test/java/io/appform/dropwizard/sharding/dao/CacheableLookupDaoTest.java`
  - `src/test/java/io/appform/dropwizard/sharding/ScrollTest.java`
  - `src/test/java/io/appform/dropwizard/sharding/dao/EncryptionAtRestTest.java`
  - `src/test/java/io/appform/dropwizard/sharding/dao/locktest/LockTest.java`

**Interfaces:**
- Consumes: the bundle-owned registry from Task 3.
- Produces: lookup constructors whose routing dependency is
  `ShardCalculatorRegistry registry`.

- [ ] **Step 1: Add failing per-operation and unknown-tenant tests**

In `MultiTenantLookupDaoTest`, retain the isolated registry as a field:

```java
private ShardCalculatorRegistry registry;
```

Build it in setup and pass it to the DAO constructor:

```java
registry = ShardCalculatorTestUtils.registryFor(shardManagers);
lookupDao = new MultiTenantLookupDao<>(
        sessionFactories,
        TestEntity.class,
        registry,
        shardingOptions,
        shardInfoProviders,
        observer);
```

Add:

```java
@Test
void resolvesCalculatorForEveryOperation() throws Exception {
    var entity = TestEntity.builder()
            .externalId("dynamic-key")
            .text("value")
            .build();
    lookupDao.save("TENANT1", entity);

    registry.clear();

    var error = assertThrows(
            IllegalStateException.class,
            () -> lookupDao.get("TENANT1", "dynamic-key"));
    assertEquals(
            "ShardCalculator has not been registered for tenant: TENANT1",
            error.getMessage());
}

@Test
void unknownTenantFailsThroughRegistry() {
    var error = assertThrows(
            IllegalStateException.class,
            () -> lookupDao.get("UNKNOWN", "key"));

    assertEquals(
            "ShardCalculator has not been registered for tenant: UNKNOWN",
            error.getMessage());
}
```

The first test clears only the registry created by that test fixture. It proves
that the DAO queries its registry again instead of retaining a calculator.

- [ ] **Step 2: Run the lookup test and verify the new constructor is missing**

Run:

```bash
mvn -q -o -Dtest=MultiTenantLookupDaoTest test
```

Expected: test compilation fails because `MultiTenantLookupDao` still accepts
the shard-manager map instead of `ShardCalculatorRegistry`.

- [ ] **Step 3: Replace lookup calculator state with the registry**

Change the constructor to:

```java
public MultiTenantLookupDao(
        Map<String, List<SessionFactory>> sessionFactories,
        Class<T> entityClass,
        ShardCalculatorRegistry registry,
        Map<String, ShardingBundleOptions> shardingOptions,
        Map<String, ShardInfoProvider> shardInfoProviders,
        TransactionObserver observer) {
    this.registry = Objects.requireNonNull(registry, "registry");
    this.sessionFactories = sessionFactories;
    sessionFactories.forEach((tenantId, factories) -> daos.put(
            tenantId,
            factories.stream()
                    .map(LookupDaoPriv::new)
                    .collect(Collectors.toList())));
    this.entityClass = entityClass;
    this.shardingOptions = shardingOptions;
    this.shardInfoProviders = shardInfoProviders;
    this.observer = observer;
    shardInfoProviders.forEach((tenantId, shardInfoProvider) ->
            this.transactionExecutor.put(
                    tenantId,
                    new TransactionExecutor(
                            shardInfoProvider,
                            DaoType.LOOKUP,
                            entityClass,
                            observer)));
    Field[] fields =
            FieldUtils.getFieldsWithAnnotation(entityClass, LookupKey.class);
    Preconditions.checkArgument(
            fields.length != 0,
            "At least one field needs to be sharding key");
    Preconditions.checkArgument(
            fields.length == 1,
            "Only one field can be sharding key");
    keyField = fields[0];
    if (!keyField.isAccessible()) {
        try {
            keyField.setAccessible(true);
        } catch (SecurityException e) {
            log.error(
                    "Error making key field accessible please use a public "
                            + "method and mark that as LookupKey",
                    e);
            throw new IllegalArgumentException(
                    "Invalid class, DAO cannot be created.",
                    e);
        }
    }
    Preconditions.checkArgument(
            ClassUtils.isAssignable(keyField.getType(), String.class),
            "Key field must be a string");
}
```

Add one final field:

```java
private final ShardCalculatorRegistry registry;
```

Remove `implements ShardedDao<T>`, the calculator field, its getter, calculator
construction, and shard-manager imports. Add:

```java
protected final int shardId(String tenantId, String key) {
    return registry.get(tenantId).shardId(key);
}

protected final void validateTenant(String tenantId) {
    registry.get(tenantId);
}
```

Replace every routing expression:

```java
shardCalculator.shardId(tenantId, key)
```

with:

```java
shardId(tenantId, key)
```

Apply the same replacement for variables named `id` and for batch grouping
lambdas. Do not store a returned calculator in a field or map.

- [ ] **Step 4: Pass the registry through cacheable lookup and bundle factories**

Change `MultiTenantCacheableLookupDao` to:

```java
public MultiTenantCacheableLookupDao(
        Map<String, List<SessionFactory>> sessionFactories,
        Class<T> entityClass,
        ShardCalculatorRegistry registry,
        Map<String, LookupCache<T>> cache,
        Map<String, ShardingBundleOptions> shardingOptions,
        Map<String, ShardInfoProvider> shardInfoProvider,
        TransactionObserver observer) {
    super(
            sessionFactories,
            entityClass,
            registry,
            shardingOptions,
            shardInfoProvider,
            observer);
    this.cache = cache;
}
```

Update both lookup factory methods in `MultiTenantDBShardingBundleBase` to pass
`this.shardCalculatorRegistry` in place of `this.shardManagers`.

Insert `validateTenant(tenantId);` as the first statement in each public
override that reads `cache.get(tenantId)` before a superclass routing method.
Leave the cache lookup and fallback logic after that validation unchanged. Add
an unknown-tenant test to `MultiTenantCacheableLookupDaoTest` that expects the
registry's exact `IllegalStateException`.

- [ ] **Step 5: Remove single-tenant lookup calculator access**

Change `LookupDao` to stop implementing `ShardedDao<T>`. Delete its
`getShardCalculator()` method and `ShardCalculator` import. Single-tenant
lookup creation continues through the delegate bundle's DAO factory, which now
passes that bundle's registry.

- [ ] **Step 6: Update lookup-related test fixtures**

In every test listed for this task:

1. Create an isolated registry after creating shard managers:

```java
ShardCalculatorRegistry registry =
        ShardCalculatorTestUtils.registryFor(shardManagers);
```

For one default-namespace manager use:

```java
ShardCalculatorRegistry registry =
        ShardCalculatorTestUtils.registryFor(Map.of(
                DBShardingBundleBase.DEFAULT_NAMESPACE,
                shardManager));
```

2. Replace the shard-manager constructor argument with `registry`.
3. Remove registry teardown; each fixture owns its registry.
4. Replace `LookupDao.getShardCalculator()` use with:

```java
registry.get(DBShardingBundleBase.DEFAULT_NAMESPACE).shardId(key);
```

- [ ] **Step 7: Run all lookup selectors**

Run:

```bash
mvn -q -o \
  -Dtest=MultiTenantLookupDaoTest,MultiTenantCacheableLookupDaoTest,LookupDaoTest,CacheableLookupDaoTest,ScrollTest,EncryptionAtRestTest,LockTest \
  test
```

Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/io/appform/dropwizard/sharding/MultiTenantDBShardingBundleBase.java \
        src/main/java/io/appform/dropwizard/sharding/dao/MultiTenantLookupDao.java \
        src/main/java/io/appform/dropwizard/sharding/dao/MultiTenantCacheableLookupDao.java \
        src/main/java/io/appform/dropwizard/sharding/dao/LookupDao.java \
        src/test/java/io/appform/dropwizard/sharding/ScrollTest.java \
        src/test/java/io/appform/dropwizard/sharding/dao/EncryptionAtRestTest.java \
        src/test/java/io/appform/dropwizard/sharding/dao/MultiTenantLookupDaoTest.java \
        src/test/java/io/appform/dropwizard/sharding/dao/MultiTenantCacheableLookupDaoTest.java \
        src/test/java/io/appform/dropwizard/sharding/dao/LookupDaoTest.java \
        src/test/java/io/appform/dropwizard/sharding/dao/CacheableLookupDaoTest.java \
        src/test/java/io/appform/dropwizard/sharding/dao/locktest/LockTest.java
git commit -m "refactor: resolve lookup calculators by bundle registry" \
  -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

## Task 5: Migrate relational DAO routing

**Files:**
- Modify: `src/main/java/io/appform/dropwizard/sharding/dao/MultiTenantRelationalDao.java`
- Modify: `src/main/java/io/appform/dropwizard/sharding/dao/MultiTenantCacheableRelationalDao.java`
- Modify: `src/main/java/io/appform/dropwizard/sharding/dao/RelationalDao.java`
- Modify: `src/main/java/io/appform/dropwizard/sharding/MultiTenantDBShardingBundleBase.java:232-260`
- Modify tests:
  - `src/test/java/io/appform/dropwizard/sharding/dao/MultiTenantRelationalDaoTest.java`
  - `src/test/java/io/appform/dropwizard/sharding/dao/MultiTenantRelationalReadOnlyLockedContextTest.java`
  - `src/test/java/io/appform/dropwizard/sharding/dao/RelationalDaoTest.java`
  - `src/test/java/io/appform/dropwizard/sharding/dao/RelationalReadOnlyLockedContextTest.java`
  - `src/test/java/io/appform/dropwizard/sharding/dao/CacheableLookupDaoTest.java`
  - `src/test/java/io/appform/dropwizard/sharding/dao/MultiTenantCacheableLookupDaoTest.java`
  - `src/test/java/io/appform/dropwizard/sharding/dao/locktest/ParentChildTest.java`

**Interfaces:**
- Consumes: the bundle-owned registry from Task 3.
- Produces: relational constructors whose routing dependency is
  `ShardCalculatorRegistry registry`.

- [ ] **Step 1: Add failing relational per-operation and unknown-tenant tests**

In `MultiTenantRelationalDaoTest`, retain and pass an isolated registry:

```java
private ShardCalculatorRegistry registry;

registry = ShardCalculatorTestUtils.registryFor(shardManagers);
relationalDao = new MultiTenantRelationalDao<>(
        sessionFactories,
        RelationalEntity.class,
        registry,
        shardingOptions,
        shardInfoProviders,
        observer);
```

Add:

```java
@Test
void resolvesCalculatorForEveryOperation() {
    var entity = RelationalEntity.builder()
            .key("entity")
            .value("value")
            .build();
    assertTrue(relationalDao.save(
            "TENANT1",
            "parent",
            entity).isPresent());

    registry.clear();

    var error = assertThrows(
            IllegalStateException.class,
            () -> relationalDao.get(
                    "TENANT1",
                    "parent",
                    entity.getKey()));
    assertEquals(
            "ShardCalculator has not been registered for tenant: TENANT1",
            error.getMessage());
}

@Test
void unknownTenantFailsThroughRegistry() {
    var error = assertThrows(
            IllegalStateException.class,
            () -> relationalDao.get(
                    "UNKNOWN",
                    "parent",
                    "key"));

    assertEquals(
            "ShardCalculator has not been registered for tenant: UNKNOWN",
            error.getMessage());
}
```

- [ ] **Step 2: Run the relational test and verify the new constructor is missing**

Run:

```bash
mvn -q -o -Dtest=MultiTenantRelationalDaoTest test
```

Expected: test compilation fails because `MultiTenantRelationalDao` still
accepts the shard-manager map instead of `ShardCalculatorRegistry`.

- [ ] **Step 3: Replace relational calculator state with the registry**

Change the constructor to:

```java
public MultiTenantRelationalDao(
        Map<String, List<SessionFactory>> sessionFactories,
        Class<T> entityClass,
        ShardCalculatorRegistry registry,
        Map<String, ShardingBundleOptions> shardingOptions,
        Map<String, ShardInfoProvider> shardInfoProviders,
        TransactionObserver observer) {
    this.registry = Objects.requireNonNull(registry, "registry");
    this.shardingOptions = shardingOptions;
    sessionFactories.forEach((tenantId, factories) -> daos.put(
            tenantId,
            factories.stream()
                    .map(RelationalDaoPriv::new)
                    .collect(Collectors.toList())));
    this.entityClass = entityClass;
    this.shardInfoProviders = shardInfoProviders;
    this.observer = observer;
    shardInfoProviders.forEach((tenantId, shardInfoProvider) ->
            this.transactionExecutor.put(
                    tenantId,
                    new TransactionExecutor(
                            shardInfoProvider,
                            DaoType.RELATIONAL,
                            entityClass,
                            observer)));
    Field[] fields = FieldUtils.getFieldsWithAnnotation(entityClass, Id.class);
    Preconditions.checkArgument(
            fields.length != 0,
            "A field needs to be designated as @Id");
    Preconditions.checkArgument(
            fields.length == 1,
            "Only one field can be designated as @Id");
    keyField = fields[0];
    if (!keyField.isAccessible()) {
        try {
            keyField.setAccessible(true);
        } catch (SecurityException e) {
            log.error(
                    "Error making key field accessible please use a public "
                            + "method and mark that as @Id",
                    e);
            throw new IllegalArgumentException(
                    "Invalid class, DAO cannot be created.",
                    e);
        }
    }
}
```

Add:

```java
private final ShardCalculatorRegistry registry;

protected final int shardId(String tenantId, String key) {
    return registry.get(tenantId).shardId(key);
}

protected final void validateTenant(String tenantId) {
    registry.get(tenantId);
}
```

Remove `implements ShardedDao<T>`, the calculator field, its getter,
calculator construction, and shard-manager imports. Replace every:

```java
shardCalculator.shardId(tenantId, parentKey)
```

with:

```java
shardId(tenantId, parentKey)
```

Apply the same replacement where the routing key variable is `id`. Contexts
that already contain a shard ID remain unchanged. Do not cache a calculator.

- [ ] **Step 4: Pass the registry through cacheable relational and bundle factories**

Change `MultiTenantCacheableRelationalDao` to:

```java
public MultiTenantCacheableRelationalDao(
        Map<String, List<SessionFactory>> sessionFactories,
        Class<T> entityClass,
        ShardCalculatorRegistry registry,
        Map<String, RelationalCache<T>> cache,
        Map<String, ShardingBundleOptions> shardingOptions,
        Map<String, ShardInfoProvider> shardInfoProvider,
        TransactionObserver observer) {
    super(
            sessionFactories,
            entityClass,
            registry,
            shardingOptions,
            shardInfoProvider,
            observer);
    this.cache = cache;
}
```

Update both relational factory methods in
`MultiTenantDBShardingBundleBase` to pass `this.shardCalculatorRegistry` in
place of `this.shardManagers`.

Before every cache access in `MultiTenantCacheableRelationalDao`, call:

```java
validateTenant(tenantId);
```

Apply this at the start of each public override that reads
`cache.get(tenantId)` before superclass routing. Add an unknown-tenant test to
the cacheable relational coverage in `MultiTenantCacheableLookupDaoTest` that
expects the exact registry error.

- [ ] **Step 5: Remove single-tenant relational calculator access**

Change `RelationalDao` to stop implementing `ShardedDao<T>`. Delete its
calculator accessor and `ShardCalculator` import. Single-tenant relational
creation continues through the delegate bundle's DAO factory.

- [ ] **Step 6: Update relational test fixtures**

For every test listed in this task:

1. Create a registry with
   `ShardCalculatorTestUtils.registryFor(...)`.
2. Replace the shard-manager constructor argument with that registry.
3. Remove registry teardown.
4. Replace DAO calculator access with:

```java
registry.get(tenantId).shardId(parentKey);
```

- [ ] **Step 7: Run all relational selectors**

Run:

```bash
mvn -q -o \
  -Dtest=MultiTenantRelationalDaoTest,MultiTenantRelationalReadOnlyLockedContextTest,RelationalDaoTest,RelationalReadOnlyLockedContextTest,CacheableLookupDaoTest,MultiTenantCacheableLookupDaoTest,ParentChildTest \
  test
```

Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/io/appform/dropwizard/sharding/MultiTenantDBShardingBundleBase.java \
        src/main/java/io/appform/dropwizard/sharding/dao/MultiTenantRelationalDao.java \
        src/main/java/io/appform/dropwizard/sharding/dao/MultiTenantCacheableRelationalDao.java \
        src/main/java/io/appform/dropwizard/sharding/dao/RelationalDao.java \
        src/test/java/io/appform/dropwizard/sharding/dao/MultiTenantRelationalDaoTest.java \
        src/test/java/io/appform/dropwizard/sharding/dao/MultiTenantRelationalReadOnlyLockedContextTest.java \
        src/test/java/io/appform/dropwizard/sharding/dao/RelationalDaoTest.java \
        src/test/java/io/appform/dropwizard/sharding/dao/RelationalReadOnlyLockedContextTest.java \
        src/test/java/io/appform/dropwizard/sharding/dao/CacheableLookupDaoTest.java \
        src/test/java/io/appform/dropwizard/sharding/dao/MultiTenantCacheableLookupDaoTest.java \
        src/test/java/io/appform/dropwizard/sharding/dao/locktest/ParentChildTest.java
git commit -m "refactor: resolve relational calculators by bundle registry" \
  -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

## Task 6: Migrate `WrapperDao` and remove the DAO calculator contract

**Files:**
- Modify: `src/main/java/io/appform/dropwizard/sharding/dao/WrapperDao.java`
- Modify: `src/main/java/io/appform/dropwizard/sharding/MultiTenantDBShardingBundleBase.java:254-285`
- Delete: `src/main/java/io/appform/dropwizard/sharding/dao/ShardedDao.java`
- Modify tests:
  - `src/test/java/io/appform/dropwizard/sharding/dao/WrapperDaoTest.java`
  - `src/test/java/io/appform/dropwizard/sharding/dao/WrapperDaoTransactionReuseTest.java`

**Interfaces:**
- Consumes: the bundle-owned registry from Task 3.
- Produces: `WrapperDao` constructors that receive `dbNamespace` and
  `ShardCalculatorRegistry registry`.

- [ ] **Step 1: Add failing per-call wrapper resolution**

In `WrapperDaoTest`, retain the fixture's registry:

```java
private ShardCalculatorRegistry registry;

registry = ShardCalculatorTestUtils.registryFor(Map.of(
        DBShardingBundleBase.DEFAULT_NAMESPACE,
        shardManager));
dao = new WrapperDao<>(
        DBShardingBundleBase.DEFAULT_NAMESPACE,
        sessionFactories,
        OrderDao.class,
        registry);
```

Add:

```java
@Test
void resolvesCalculatorForEveryForParentCall() {
    dao.forParent("customer1");

    registry.clear();

    var error = assertThrows(
            IllegalStateException.class,
            () -> dao.forParent("customer1"));
    assertEquals(
            "ShardCalculator has not been registered for tenant: default",
            error.getMessage());
}
```

- [ ] **Step 2: Run wrapper tests and verify the registry constructor is missing**

Run:

```bash
mvn -q -o -Dtest=WrapperDaoTest,WrapperDaoTransactionReuseTest test
```

Expected: test compilation fails because `WrapperDao` still accepts a
`ShardManager`.

- [ ] **Step 3: Replace wrapper calculator state with the registry**

Change the constructors to:

```java
public WrapperDao(
        String dbNamespace,
        List<SessionFactory> sessionFactories,
        Class<DaoType> daoClass,
        ShardCalculatorRegistry registry) {
    this(
            dbNamespace,
            sessionFactories,
            daoClass,
            null,
            null,
            registry);
}

public WrapperDao(
        String dbNamespace,
        List<SessionFactory> sessionFactories,
        Class<DaoType> daoClass,
        Class[] extraConstructorParamClasses,
        Class[] extraConstructorParamObjects,
        ShardCalculatorRegistry registry) {
    this.dbNamespace = dbNamespace;
    this.registry = Objects.requireNonNull(registry, "registry");
    this.daos = sessionFactories.stream().map(sessionFactory -> {
        Enhancer enhancer = new Enhancer();
        enhancer.setUseFactory(false);
        enhancer.setSuperclass(daoClass);
        enhancer.setCallback((MethodInterceptor) (
                obj,
                method,
                args,
                proxy) -> {
            ShardedTransaction transaction =
                    method.getAnnotation(ShardedTransaction.class);
            if (transaction == null) {
                return proxy.invokeSuper(obj, args);
            }
            TransactionHandler transactionHandler =
                    new TransactionHandler(
                            sessionFactory,
                            transaction.readOnly());
            try {
                transactionHandler.beforeStart();
                Object result = proxy.invokeSuper(obj, args);
                transactionHandler.afterEnd();
                return result;
            } catch (InvocationTargetException e) {
                transactionHandler.onError();
                throw e.getCause();
            } catch (Exception e) {
                transactionHandler.onError();
                throw e;
            }
        });
        return createDAOProxy(
                sessionFactory,
                enhancer,
                extraConstructorParamClasses,
                extraConstructorParamObjects);
    }).collect(Collectors.toList());
}
```

Add:

```java
private final ShardCalculatorRegistry registry;
```

Remove the calculator field, shard-manager parameter, calculator construction,
calculator getter, and related imports. Resolve for every call:

```java
public DaoType forParent(final String parentKey) {
    int shardId = registry.get(dbNamespace).shardId(parentKey);
    return daos.get(shardId);
}
```

- [ ] **Step 4: Update wrapper factories**

In both `MultiTenantDBShardingBundleBase.createWrapperDao` overloads, retain
the unknown-tenant precondition and pass `this.shardCalculatorRegistry` instead
of `this.shardManagers.get(tenantId)`.

`DBShardingBundleBase.createWrapperDao` already delegates to these factories,
so its wrappers use the delegate bundle's registry.

- [ ] **Step 5: Delete `ShardedDao`**

After Tasks 4 and 5 remove all implementations, delete:

```text
src/main/java/io/appform/dropwizard/sharding/dao/ShardedDao.java
```

Search for remaining references:

```bash
grep -R "ShardedDao\\|getShardCalculator()" -n src/main/java
```

Expected: only bundle-level `getShardCalculator()` methods remain; no
`ShardedDao` references remain.

- [ ] **Step 6: Update wrapper tests**

In each wrapper test, create an isolated registry:

```java
ShardCalculatorRegistry registry =
        ShardCalculatorTestUtils.registryFor(Map.of(
                DBShardingBundleBase.DEFAULT_NAMESPACE,
                shardManager));
```

Pass it in place of the manager. Replace shard-ID calculations in
`WrapperDaoTransactionReuseTest` with:

```java
registry.get(DBShardingBundleBase.DEFAULT_NAMESPACE)
        .shardId(parentKey);
```

Do not add teardown; each test fixture owns its registry.

- [ ] **Step 7: Run wrapper and bundle tests**

Run:

```bash
mvn -q -o \
  -Dtest=WrapperDaoTest,WrapperDaoTransactionReuseTest,MultiTenantBalancedDBShardingBundleWithEntityTest,BalancedDBShardingBundleWithEntityTest,BundleMvccSnapshotTest \
  test
```

Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/io/appform/dropwizard/sharding/MultiTenantDBShardingBundleBase.java \
        src/main/java/io/appform/dropwizard/sharding/dao/WrapperDao.java \
        src/test/java/io/appform/dropwizard/sharding/dao/WrapperDaoTest.java \
        src/test/java/io/appform/dropwizard/sharding/dao/WrapperDaoTransactionReuseTest.java
git rm src/main/java/io/appform/dropwizard/sharding/dao/ShardedDao.java
git commit -m "refactor: move calculator access out of daos" \
  -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

## Task 7: Remove every legacy `ShardCalculator` API

**Files:**
- Modify: `src/main/java/io/appform/dropwizard/sharding/utils/ShardCalculator.java`
- Modify: `src/test/java/io/appform/dropwizard/sharding/utils/ShardCalculatorTest.java`
- Modify any remaining tests found by the searches below

- [ ] **Step 1: Add reflection tests for the final API**

Add:

```java
@Test
void exposesOnlyTenantBoundRoutingApi() {
    var constructors = Arrays.stream(
            ShardCalculator.class.getConstructors())
            .map(Constructor::getParameterTypes)
            .collect(Collectors.toList());

    assertEquals(1, constructors.size());
    assertArrayEquals(
            new Class<?>[]{
                    String.class,
                    ShardManager.class,
                    BucketIdExtractor.class
            },
            constructors.get(0));
    assertFalse(Arrays.stream(ShardCalculator.class.getMethods())
            .anyMatch(method -> method.getName().equals("shardId")
                    && method.getParameterCount() == 2));
    assertFalse(Arrays.stream(ShardCalculator.class.getMethods())
            .anyMatch(method -> method.getName().equals("isOnValidShard")
                    && method.getParameterCount() == 2));
}
```

Add imports for `Constructor`, `Arrays`, and `Collectors`.

- [ ] **Step 2: Run the calculator test and verify legacy APIs remain**

Run:

```bash
mvn -q -o -Dtest=ShardCalculatorTest test
```

Expected: FAIL because the transitional map constructor and tenant-parameter
methods still exist.

- [ ] **Step 3: Simplify `ShardCalculator` to its final form**

Replace its state and methods with:

```java
private final String tenantId;
private final ShardManager shardManager;
private final BucketIdExtractor<T> extractor;

public ShardCalculator(
        String tenantId,
        ShardManager shardManager,
        BucketIdExtractor<T> extractor) {
    this.tenantId = Objects.requireNonNull(tenantId, "tenantId");
    this.shardManager = Objects.requireNonNull(
            shardManager,
            "shardManager");
    this.extractor = Objects.requireNonNull(extractor, "extractor");
}

public int shardId(T key) {
    int bucketId = extractor.bucketId(tenantId, key);
    return shardManager.shardForBucket(bucketId);
}

public boolean isOnValidShard(T key) {
    int bucketId = extractor.bucketId(tenantId, key);
    return shardManager.isMappedToValidShard(bucketId);
}
```

Remove `DBShardingBundleBase` and `Map` imports. No compatibility constructor,
default namespace fallback, or tenant-parameter routing method remains.

- [ ] **Step 4: Search for obsolete construction and routing**

Run:

```bash
grep -R "new ShardCalculator<>(.*Map\\|shardId(.*tenantId\\|isOnValidShard(.*tenantId" \
  -n src/main/java src/test/java || true
```

Update every match to the tenant-bound constructor or key-only routing API.
Expected after edits: no matches.

- [ ] **Step 5: Run calculator, registry, DAO, and bundle tests**

Run:

```bash
mvn -q -o \
  -Dtest=ShardCalculatorTest,ShardCalculatorRegistryTest,MultiTenantLookupDaoTest,MultiTenantRelationalDaoTest,WrapperDaoTest,MultiTenantBalancedDBShardingBundleWithEntityTest,BalancedDBShardingBundleWithEntityTest,BundleMvccSnapshotTest \
  test
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/io/appform/dropwizard/sharding/utils/ShardCalculator.java \
        src/test/java/io/appform/dropwizard/sharding/utils/ShardCalculatorTest.java \
        src/main/java src/test/java
git commit -m "refactor: remove legacy shard calculator api" \
  -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

## Task 8: Complete fixture isolation and final verification

**Files:**
- Modify any remaining test fixture that creates a DAO directly
- No production behavior changes

- [ ] **Step 1: Find direct DAO construction without an isolated registry**

Run:

```bash
grep -R "new MultiTenantLookupDao\\|new MultiTenantCacheableLookupDao\\|new MultiTenantRelationalDao\\|new MultiTenantCacheableRelationalDao\\|new WrapperDao" \
  -n src/test/java
```

For each result, verify its setup either runs a bundle or creates a registry
with `ShardCalculatorTestUtils.registryFor(...)` and passes that instance to
the constructor. Add the helper call when neither is present.

- [ ] **Step 2: Verify the old helper and shared cleanup are gone**

Run:

```bash
grep -R "ShardCalculatorTestUtils.register\\|ShardCalculatorRegistry\\.clear()\\|ResourceLock" \
  -n src/test/java || true
```

Expected: no output. Calls such as `registry.clear()` are allowed only inside
an isolated test that proves per-operation resolution.

- [ ] **Step 3: Verify registry state and methods belong to instances**

Run:

```bash
grep -n "static.*calculators\\|static.*register\\|static.*get\\|static.*clear" \
  src/main/java/io/appform/dropwizard/sharding/utils/ShardCalculatorRegistry.java || true
grep -R "ShardCalculatorRegistry\\.register\\|ShardCalculatorRegistry\\.get\\|ShardCalculatorRegistry\\.clear" \
  -n src/main/java src/test/java || true
```

Expected: no output. Construction with `new ShardCalculatorRegistry()` and
calls through variables or bundle fields are expected.

- [ ] **Step 4: Verify DAO constructor dependencies**

Run:

```bash
grep -R -n -A12 \
  "public MultiTenantLookupDao\\|public MultiTenantCacheableLookupDao\\|public MultiTenantRelationalDao\\|public MultiTenantCacheableRelationalDao\\|public WrapperDao" \
  src/main/java/io/appform/dropwizard/sharding/dao
grep -R "Map<String, ShardManager> shardManagers\\|ShardManager shardManager\\|ShardCalculator<String> shardCalculator\\|Map<String, ShardCalculator" \
  -n src/main/java/io/appform/dropwizard/sharding/dao || true
```

Expected: constructor output shows `ShardCalculatorRegistry registry` for each
DAO. The forbidden-type search produces no output. Other constructor
dependencies remain unchanged.

- [ ] **Step 5: Verify bundle ownership and routing lookups**

Run:

```bash
grep -n "final ShardCalculatorRegistry\\|shardCalculatorRegistry.register" \
  src/main/java/io/appform/dropwizard/sharding/MultiTenantDBShardingBundleBase.java
grep -R "registry.get(tenantId)\\|registry.get(dbNamespace)" \
  -n src/main/java/io/appform/dropwizard/sharding/dao
```

Expected: the bundle has one final registry field and one post-initialization
batch registration. Lookup and relational DAOs query `registry.get(tenantId)`;
`WrapperDao` queries `registry.get(dbNamespace)`.

- [ ] **Step 6: Run the complete test suite**

Run:

```bash
mvn -q -o test
```

Expected: BUILD SUCCESS. This includes `BundleMvccSnapshotTest` with two live
default-namespace bundles.

- [ ] **Step 7: Commit fixture-only corrections if Step 1 changed files**

```bash
git add src/test/java
git commit -m "test: isolate tenant calculator registries" \
  -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

Skip this commit only when Tasks 1-7 already left every direct-construction
fixture isolated.

- [ ] **Step 8: Run final repository checks**

Run:

```bash
git diff --check origin/master...HEAD
git status --short
```

Expected: no whitespace errors and no uncommitted files.

# Per-Tenant ShardCalculator Registry Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Create one tenant-bound shard calculator per configured tenant, publish calculators through an atomic JVM-wide registry, and remove shard-manager and calculator dependencies from DAO APIs.

**Architecture:** `MultiTenantDBShardingBundleBase` builds all tenant calculators after tenant initialization and registers them as one batch. DAOs resolve `ShardCalculatorRegistry.get(tenantId)` at each routing operation, while bundles own the public calculator accessors. The work deliberately removes the old multi-tenant calculator API and DAO-level calculator access.

**Tech Stack:** Java 11+, Maven, JUnit 5, Guava, Dropwizard, Hibernate, Mockito

---

## File Map

| Action | File | Responsibility |
| --- | --- | --- |
| Create | `src/main/java/io/appform/dropwizard/sharding/utils/ShardCalculatorRegistry.java` | Store tenant calculators and publish registration batches atomically |
| Create | `src/test/java/io/appform/dropwizard/sharding/utils/ShardCalculatorRegistryTest.java` | Verify registry lifecycle, errors, and atomicity |
| Create | `src/test/java/io/appform/dropwizard/sharding/utils/ShardCalculatorTest.java` | Verify tenant-bound routing |
| Create | `src/test/java/io/appform/dropwizard/sharding/utils/ShardCalculatorTestUtils.java` | Register calculators consistently in DAO fixtures |
| Modify | `src/main/java/io/appform/dropwizard/sharding/utils/ShardCalculator.java` | Convert calculator ownership from a tenant map to one tenant |
| Modify | `src/main/java/io/appform/dropwizard/sharding/MultiTenantDBShardingBundleBase.java` | Build, register, expose, and use tenant calculators |
| Modify | `src/main/java/io/appform/dropwizard/sharding/DBShardingBundleBase.java` | Expose the default namespace calculator |
| Modify | `src/main/java/io/appform/dropwizard/sharding/dao/MultiTenantLookupDao.java` | Resolve tenant calculators for lookup routing |
| Modify | `src/main/java/io/appform/dropwizard/sharding/dao/MultiTenantCacheableLookupDao.java` | Remove the shard-manager constructor parameter |
| Modify | `src/main/java/io/appform/dropwizard/sharding/dao/LookupDao.java` | Remove DAO-level calculator access |
| Modify | `src/main/java/io/appform/dropwizard/sharding/dao/MultiTenantRelationalDao.java` | Resolve tenant calculators for relational routing |
| Modify | `src/main/java/io/appform/dropwizard/sharding/dao/MultiTenantCacheableRelationalDao.java` | Remove the shard-manager constructor parameter |
| Modify | `src/main/java/io/appform/dropwizard/sharding/dao/RelationalDao.java` | Remove DAO-level calculator access |
| Modify | `src/main/java/io/appform/dropwizard/sharding/dao/WrapperDao.java` | Resolve its namespace calculator per `forParent` call |
| Delete | `src/main/java/io/appform/dropwizard/sharding/dao/ShardedDao.java` | Remove the obsolete DAO calculator contract |
| Modify | `src/test/java/io/appform/dropwizard/sharding/MultiTenantBundleBasedTestBase.java` | Clear registry state between bundle tests |
| Modify | `src/test/java/io/appform/dropwizard/sharding/BundleBasedTestBase.java` | Clear registry state between single-tenant bundle tests |
| Modify | DAO tests listed in Tasks 4-7 | Register calculators, use reduced constructors, and remove DAO accessor usage |

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

## Task 2: Add atomic tenant calculator registry

**Files:**
- Create: `src/main/java/io/appform/dropwizard/sharding/utils/ShardCalculatorRegistry.java`
- Create: `src/test/java/io/appform/dropwizard/sharding/utils/ShardCalculatorRegistryTest.java`
- Create: `src/test/java/io/appform/dropwizard/sharding/utils/ShardCalculatorTestUtils.java`

- [ ] **Step 1: Write failing registry tests**

Cover retrieval, missing tenants, duplicate rejection, atomic batch behavior, clearing, and concurrent reads:

```java
class ShardCalculatorRegistryTest {

    @AfterEach
    void tearDown() {
        ShardCalculatorRegistry.clear();
    }

    @Test
    void registersAndReturnsEachTenantCalculator() {
        var tenant1 = calculatorFor("TENANT1", 2);
        var tenant2 = calculatorFor("TENANT2", 4);

        ShardCalculatorRegistry.register(Map.of(
                "TENANT1", tenant1,
                "TENANT2", tenant2));

        assertSame(tenant1, ShardCalculatorRegistry.get("TENANT1"));
        assertSame(tenant2, ShardCalculatorRegistry.get("TENANT2"));
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
    void rejectsNullRegistrationInputsBeforePublishing() {
        assertThrows(
                NullPointerException.class,
                () -> ShardCalculatorRegistry.register(null));

        var calculators = new HashMap<String, ShardCalculator<String>>();
        calculators.put(null, calculatorFor("TENANT1", 2));
        assertThrows(
                NullPointerException.class,
                () -> ShardCalculatorRegistry.register(calculators));

        calculators.clear();
        calculators.put("TENANT1", null);
        assertThrows(
                NullPointerException.class,
                () -> ShardCalculatorRegistry.register(calculators));

        assertThrows(
                IllegalStateException.class,
                () -> ShardCalculatorRegistry.get("TENANT1"));
    }

    @Test
    void duplicateBatchPublishesNothing() {
        var existing = calculatorFor("TENANT1", 2);
        ShardCalculatorRegistry.register(Map.of("TENANT1", existing));

        var error = assertThrows(
                IllegalStateException.class,
                () -> ShardCalculatorRegistry.register(Map.of(
                        "TENANT1", calculatorFor("TENANT1", 4),
                        "TENANT2", calculatorFor("TENANT2", 4))));

        assertEquals(
                "ShardCalculator already registered for tenant: TENANT1",
                error.getMessage());
        assertSame(existing, ShardCalculatorRegistry.get("TENANT1"));
        assertThrows(
                IllegalStateException.class,
                () -> ShardCalculatorRegistry.get("TENANT2"));
    }

    @Test
    void clearRemovesEveryTenant() {
        ShardCalculatorRegistry.register(Map.of(
                "TENANT1", calculatorFor("TENANT1", 2),
                "TENANT2", calculatorFor("TENANT2", 4)));

        ShardCalculatorRegistry.clear();

        assertThrows(
                IllegalStateException.class,
                () -> ShardCalculatorRegistry.get("TENANT1"));
        assertThrows(
                IllegalStateException.class,
                () -> ShardCalculatorRegistry.get("TENANT2"));
    }

    @Test
    void concurrentReadsReturnPublishedCalculator() throws Exception {
        var calculator = calculatorFor("TENANT1", 2);
        ShardCalculatorRegistry.register(Map.of("TENANT1", calculator));
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
        var manager = new BalancedShardManager(shardCount);
        return new ShardCalculator<>(
                tenantId,
                manager,
                new ConsistentHashBucketIdExtractor<>(
                        Map.of(tenantId, manager)));
    }
}
```

Add the imports required by the code above.

- [ ] **Step 2: Run the registry tests and verify the class is missing**

Run:

```bash
mvn -q -o -Dtest=ShardCalculatorRegistryTest test
```

Expected: test compilation fails because `ShardCalculatorRegistry` does not exist.

- [ ] **Step 3: Implement atomic registration**

Create a final utility class:

```java
public final class ShardCalculatorRegistry {

    private static final ConcurrentMap<String, ShardCalculator<String>>
            CALCULATORS = new ConcurrentHashMap<>();

    private ShardCalculatorRegistry() {
    }

    public static synchronized void register(
            Map<String, ShardCalculator<String>> calculators) {
        Objects.requireNonNull(calculators, "calculators");
        calculators.forEach((tenantId, calculator) -> {
            Objects.requireNonNull(tenantId, "tenantId");
            Objects.requireNonNull(calculator, "calculator");
            if (CALCULATORS.containsKey(tenantId)) {
                throw new IllegalStateException(
                        "ShardCalculator already registered for tenant: "
                                + tenantId);
            }
        });
        CALCULATORS.putAll(calculators);
    }

    public static ShardCalculator<String> get(String tenantId) {
        Objects.requireNonNull(tenantId, "tenantId");
        var calculator = CALCULATORS.get(tenantId);
        if (calculator == null) {
            throw new IllegalStateException(
                    "ShardCalculator has not been registered for tenant: "
                            + tenantId);
        }
        return calculator;
    }

    @VisibleForTesting
    public static synchronized void clear() {
        CALCULATORS.clear();
    }
}
```

Use Guava's `VisibleForTesting` and JDK concurrent collections. Validation must
complete before `putAll`.

- [ ] **Step 4: Add the shared test registration helper**

Create:

```java
public final class ShardCalculatorTestUtils {

    private ShardCalculatorTestUtils() {
    }

    public static void register(
            Map<String, ShardManager> shardManagers) {
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
        ShardCalculatorRegistry.register(calculators);
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
git commit -m "feat: add tenant shard calculator registry" \
  -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

## Task 3: Register calculators from the bundle

**Files:**
- Modify: `src/main/java/io/appform/dropwizard/sharding/MultiTenantDBShardingBundleBase.java:85-190,212-273`
- Modify: `src/main/java/io/appform/dropwizard/sharding/DBShardingBundleBase.java:120-220`
- Modify: `src/test/java/io/appform/dropwizard/sharding/MultiTenantBundleBasedTestBase.java`
- Modify: `src/test/java/io/appform/dropwizard/sharding/BundleBasedTestBase.java`
- Modify: `src/test/java/io/appform/dropwizard/sharding/MultiTenantDBShardingBundleTestBase.java`
- Modify: `src/test/java/io/appform/dropwizard/sharding/DBShardingBundleTestBase.java`

- [ ] **Step 1: Add failing bundle accessor and per-tenant tests**

In `MultiTenantDBShardingBundleTestBase`, add:

```java
@Test
void registersOneCalculatorPerTenant() {
    var bundle = getBundle();
    bundle.initialize(bootstrap);
    bundle.run(testConfig, environment);

    var tenant1 = bundle.getShardCalculator("TENANT1");
    var tenant2 = bundle.getShardCalculator("TENANT2");

    assertNotSame(tenant1, tenant2);
    assertSame(tenant1, ShardCalculatorRegistry.get("TENANT1"));
    assertSame(tenant2, ShardCalculatorRegistry.get("TENANT2"));
}
```

Add a test-only bundle whose second shard-manager creation fails. Use an
insertion-ordered tenant map so one tenant completes before the failure:

```java
@Test
void failedInitializationDoesNotPublishCalculators() {
    var tenants = new LinkedHashMap<String, ShardedHibernateFactory>();
    tenants.put("TENANT1", ShardedHibernateFactory.builder()
            .shards(List.of(createConfig("publish_guard_1")))
            .shardingOptions(ShardingBundleOptions.builder().build())
            .build());
    tenants.put("TENANT2", ShardedHibernateFactory.builder()
            .shards(List.of(createConfig("publish_guard_2")))
            .shardingOptions(ShardingBundleOptions.builder().build())
            .build());
    var failingConfig = new TestConfig(
            new MultiTenantShardedHibernateFactory(tenants));
    var managerCreations = new AtomicInteger();
    var bundle = new MultiTenantDBShardingBundleBase<TestConfig>(
            Order.class) {
        @Override
        protected ShardManager createShardManager(
                int numShards,
                ShardBlacklistingStore blacklistingStore) {
            if (managerCreations.incrementAndGet() == 2) {
                throw new IllegalStateException("second tenant failed");
            }
            return new BalancedShardManager(
                    numShards,
                    blacklistingStore);
        }

        @Override
        protected MultiTenantShardedHibernateFactory getConfig(
                TestConfig config) {
            return config.getShards();
        }
    };

    bundle.initialize(bootstrap);
    assertThrows(
            IllegalStateException.class,
            () -> bundle.run(failingConfig, environment));
    assertThrows(
            IllegalStateException.class,
            () -> ShardCalculatorRegistry.get("TENANT1"));
    assertThrows(
            IllegalStateException.class,
            () -> ShardCalculatorRegistry.get("TENANT2"));
}
```

Change `MultiTenantBundleBasedTestBase.TestConfig` to retain its current
default while accepting an explicit factory:

```java
@Getter
private final MultiTenantShardedHibernateFactory shards;

TestConfig() {
    this(new MultiTenantShardedHibernateFactory(Map.of(
            "TENANT1", ShardedHibernateFactory.builder()
                    .shardingOptions(
                            ShardingBundleOptions.builder().build())
                    .build(),
            "TENANT2", ShardedHibernateFactory.builder()
                    .shardingOptions(
                            ShardingBundleOptions.builder().build())
                    .build())));
}

TestConfig(MultiTenantShardedHibernateFactory shards) {
    this.shards = shards;
}
```

The anonymous bundle uses `BundleCommonBase`'s existing no-op blacklisting
store; do not add another override.

In `DBShardingBundleTestBase`, add:

```java
@Test
void exposesDefaultNamespaceCalculator() {
    var bundle = getBundle();
    bundle.initialize(bootstrap);
    bundle.run(testConfig, environment);

    assertSame(
            ShardCalculatorRegistry.get(bundle.getDbNamespace()),
            bundle.getShardCalculator());
}
```

Import `ShardCalculatorRegistry`, `assertNotSame`, and `assertSame`.

- [ ] **Step 2: Run bundle tests and verify accessors are missing**

Run:

```bash
mvn -q -o \
  -Dtest=MultiTenantBalancedDBShardingBundleWithEntityTest,BalancedDBShardingBundleWithEntityTest \
  test
```

Expected: test compilation fails because bundle calculator accessors do not exist.

- [ ] **Step 3: Build calculators privately and register after the tenant loop**

Add a local map at the start of `run()`:

```java
final Map<String, ShardCalculator<String>> shardCalculators =
        Maps.newHashMap();
```

After each tenant's `ShardManager` is created, build but do not register:

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
ShardCalculatorRegistry.register(shardCalculators);
registerBucketIdExtractor(this.shardManagers);
```

This placement is required. Do not register inside the tenant loop.

- [ ] **Step 4: Add bundle-level accessors**

In `MultiTenantDBShardingBundleBase`:

```java
public ShardCalculator<String> getShardCalculator(String tenantId) {
    return ShardCalculatorRegistry.get(tenantId);
}
```

In `DBShardingBundleBase`:

```java
public ShardCalculator<String> getShardCalculator() {
    return delegate.getShardCalculator(dbNamespace);
}
```

- [ ] **Step 5: Isolate bundle tests**

Add `@AfterEach` methods to both shared bundle test bases:

```java
@AfterEach
void clearShardCalculatorRegistry() {
    ShardCalculatorRegistry.clear();
}
```

Keep any existing setup or teardown behavior unchanged.

- [ ] **Step 6: Run focused bundle tests**

Run:

```bash
mvn -q -o \
  -Dtest=MultiTenantBalancedDBShardingBundleWithEntityTest,BalancedDBShardingBundleWithEntityTest \
  test
```

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/io/appform/dropwizard/sharding/MultiTenantDBShardingBundleBase.java \
        src/main/java/io/appform/dropwizard/sharding/DBShardingBundleBase.java \
        src/test/java/io/appform/dropwizard/sharding/MultiTenantBundleBasedTestBase.java \
        src/test/java/io/appform/dropwizard/sharding/BundleBasedTestBase.java \
        src/test/java/io/appform/dropwizard/sharding/MultiTenantDBShardingBundleTestBase.java \
        src/test/java/io/appform/dropwizard/sharding/DBShardingBundleTestBase.java
git commit -m "feat: register tenant calculators from bundles" \
  -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

## Task 4: Migrate lookup DAO routing

**Files:**
- Modify: `src/main/java/io/appform/dropwizard/sharding/dao/MultiTenantLookupDao.java`
- Modify: `src/main/java/io/appform/dropwizard/sharding/dao/MultiTenantCacheableLookupDao.java`
- Modify: `src/main/java/io/appform/dropwizard/sharding/dao/LookupDao.java`
- Modify: `src/main/java/io/appform/dropwizard/sharding/MultiTenantDBShardingBundleBase.java:212-230`
- Modify tests:
  - `src/test/java/io/appform/dropwizard/sharding/dao/MultiTenantLookupDaoTest.java`
  - `src/test/java/io/appform/dropwizard/sharding/dao/MultiTenantCacheableLookupDaoTest.java`
  - `src/test/java/io/appform/dropwizard/sharding/dao/LookupDaoTest.java`
  - `src/test/java/io/appform/dropwizard/sharding/dao/CacheableLookupDaoTest.java`
  - `src/test/java/io/appform/dropwizard/sharding/ScrollTest.java`
  - `src/test/java/io/appform/dropwizard/sharding/dao/EncryptionAtRestTest.java`
  - `src/test/java/io/appform/dropwizard/sharding/dao/locktest/LockTest.java`

- [ ] **Step 1: Add failing per-operation and unknown-tenant tests**

In `MultiTenantLookupDaoTest`, register fixture calculators with
`ShardCalculatorTestUtils.register(shardManager)`, then add:

```java
@Test
void resolvesCalculatorForEveryOperation() throws Exception {
    var entity = TestEntity.builder()
            .externalId("dynamic-key")
            .text("value")
            .build();
    lookupDao.save("TENANT1", entity);

    ShardCalculatorRegistry.clear();

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

- [ ] **Step 2: Run the lookup test and verify DAO still owns a calculator**

Run:

```bash
mvn -q -o -Dtest=MultiTenantLookupDaoTest test
```

Expected: the new per-operation test fails because the DAO still uses its
constructor-created calculator after the registry is cleared, and the
unknown-tenant test does not report the registry error.

- [ ] **Step 3: Remove calculator state and resolve through a helper**

In `MultiTenantLookupDao`:

1. Remove `implements ShardedDao<T>`.
2. Remove the `shardCalculator` field and its Lombok getter.
3. Remove `Map<String, ShardManager> shardManagers` from the constructor.
4. Remove `ConsistentHashBucketIdExtractor` and `ShardManager` imports.
5. Add:

```java
protected final ShardCalculator<String> shardCalculator(String tenantId) {
    return ShardCalculatorRegistry.get(tenantId);
}
```

6. Replace every routing expression:

```java
shardCalculator.shardId(tenantId, key)
```

with:

```java
shardCalculator(tenantId).shardId(key)
```

Apply the same replacement for variables named `id` and for batch grouping
lambdas. Do not cache the returned calculator in a field or map.

- [ ] **Step 4: Reduce cacheable and bundle constructor calls**

Change `MultiTenantCacheableLookupDao` to:

```java
public MultiTenantCacheableLookupDao(
        Map<String, List<SessionFactory>> sessionFactories,
        Class<T> entityClass,
        Map<String, LookupCache<T>> cache,
        Map<String, ShardingBundleOptions> shardingOptions,
        Map<String, ShardInfoProvider> shardInfoProvider,
        TransactionObserver observer) {
    super(
            sessionFactories,
            entityClass,
            shardingOptions,
            shardInfoProvider,
            observer);
    this.cache = cache;
}
```

Update both lookup factory methods in `MultiTenantDBShardingBundleBase` to stop
passing `this.shardManagers`.

Before every cache access in `MultiTenantCacheableLookupDao`, validate the
tenant through the inherited registry helper:

```java
shardCalculator(tenantId);
if (cache.get(tenantId).exists(key)) {
    // retain the existing cache behavior
}
```

Apply this at the start of each public override that reads
`cache.get(tenantId)` before it calls a superclass routing method. Add an
unknown-tenant test to `MultiTenantCacheableLookupDaoTest` that expects the
registry's exact `IllegalStateException` instead of a cache-map null
dereference.

- [ ] **Step 5: Remove single-tenant lookup calculator access**

Change `LookupDao` to no longer implement `ShardedDao<T>` and delete its
`getShardCalculator()` method and `ShardCalculator` import.

- [ ] **Step 6: Update lookup-related test fixtures**

In every test listed for this task:

1. Register calculators after creating shard managers:

```java
ShardCalculatorTestUtils.register(shardManagers);
```

For single-tenant maps use:

```java
ShardCalculatorTestUtils.register(
        Map.of(DBShardingBundleBase.DEFAULT_NAMESPACE, shardManager));
```

2. Remove the shard-manager argument from lookup and cacheable-lookup
constructors.
3. Add teardown:

```java
ShardCalculatorRegistry.clear();
```

4. Remove any use of `LookupDao.getShardCalculator()`. Tests that need a shard
ID should call:

```java
ShardCalculatorRegistry.get(DBShardingBundleBase.DEFAULT_NAMESPACE)
        .shardId(key);
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
git commit -m "refactor: resolve lookup calculators by tenant" \
  -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

## Task 5: Migrate relational DAO routing

**Files:**
- Modify: `src/main/java/io/appform/dropwizard/sharding/dao/MultiTenantRelationalDao.java`
- Modify: `src/main/java/io/appform/dropwizard/sharding/dao/MultiTenantCacheableRelationalDao.java`
- Modify: `src/main/java/io/appform/dropwizard/sharding/dao/RelationalDao.java`
- Modify: `src/main/java/io/appform/dropwizard/sharding/MultiTenantDBShardingBundleBase.java:232-252`
- Modify tests:
  - `src/test/java/io/appform/dropwizard/sharding/dao/MultiTenantRelationalDaoTest.java`
  - `src/test/java/io/appform/dropwizard/sharding/dao/MultiTenantRelationalReadOnlyLockedContextTest.java`
  - `src/test/java/io/appform/dropwizard/sharding/dao/RelationalDaoTest.java`
  - `src/test/java/io/appform/dropwizard/sharding/dao/RelationalReadOnlyLockedContextTest.java`
  - `src/test/java/io/appform/dropwizard/sharding/dao/CacheableLookupDaoTest.java`
  - `src/test/java/io/appform/dropwizard/sharding/dao/MultiTenantCacheableLookupDaoTest.java`
  - `src/test/java/io/appform/dropwizard/sharding/dao/locktest/ParentChildTest.java`

- [ ] **Step 1: Add failing relational per-operation and unknown-tenant tests**

In `MultiTenantRelationalDaoTest`, register fixture calculators and add:

```java
@Test
void resolvesCalculatorForEveryOperation() {
    var entity = RelationalEntity.builder()
            .key("entity")
            .value("value")
            .build();
    assertTrue(relationalDao.save("TENANT1", "parent", entity).isPresent());

    ShardCalculatorRegistry.clear();

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
            () -> relationalDao.get("UNKNOWN", "parent", "key"));

    assertEquals(
            "ShardCalculator has not been registered for tenant: UNKNOWN",
            error.getMessage());
}
```

- [ ] **Step 2: Run the relational test and verify current behavior fails**

Run:

```bash
mvn -q -o -Dtest=MultiTenantRelationalDaoTest test
```

Expected: the per-operation and registry-error assertions fail while the DAO
uses its constructor-created calculator.

- [ ] **Step 3: Remove relational calculator state**

In `MultiTenantRelationalDao`:

1. Remove `implements ShardedDao<T>`.
2. Remove the `shardCalculator` field and getter.
3. Remove the shard-manager constructor parameter and calculator construction.
4. Add:

```java
protected final ShardCalculator<String> shardCalculator(String tenantId) {
    return ShardCalculatorRegistry.get(tenantId);
}
```

5. Replace every:

```java
shardCalculator.shardId(tenantId, parentKey)
```

with:

```java
shardCalculator(tenantId).shardId(parentKey)
```

Apply the same replacement at routing points whose key variable is `id`.
Contexts that already contain a shard ID must remain unchanged.

- [ ] **Step 4: Reduce cacheable and bundle constructor calls**

Change `MultiTenantCacheableRelationalDao` to remove the shard-manager map from
its signature and `super(...)` call. Update both relational factory methods in
`MultiTenantDBShardingBundleBase` to stop passing `this.shardManagers`.

Before every cache access in `MultiTenantCacheableRelationalDao`, call:

```java
shardCalculator(tenantId);
```

Apply this at the start of each public override that reads
`cache.get(tenantId)` before superclass routing. Add an unknown-tenant test to
the cacheable relational coverage in `MultiTenantCacheableLookupDaoTest` that
expects the registry's exact error message.

- [ ] **Step 5: Remove single-tenant relational calculator access**

Change `RelationalDao` to no longer implement `ShardedDao<T>`. Delete its
calculator accessor and `ShardCalculator` import.

- [ ] **Step 6: Update relational test fixtures**

For every test listed in this task:

1. Register the relevant tenant managers with
   `ShardCalculatorTestUtils.register(...)`.
2. Remove shard-manager arguments from relational and cacheable-relational
   constructors.
3. Clear `ShardCalculatorRegistry` during teardown.
4. Replace DAO calculator access with registry access:

```java
ShardCalculatorRegistry.get(tenantId).shardId(parentKey);
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
git commit -m "refactor: resolve relational calculators by tenant" \
  -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

## Task 6: Migrate `WrapperDao` and remove DAO calculator contract

**Files:**
- Modify: `src/main/java/io/appform/dropwizard/sharding/dao/WrapperDao.java`
- Modify: `src/main/java/io/appform/dropwizard/sharding/MultiTenantDBShardingBundleBase.java:254-273`
- Delete: `src/main/java/io/appform/dropwizard/sharding/dao/ShardedDao.java`
- Modify tests:
  - `src/test/java/io/appform/dropwizard/sharding/dao/WrapperDaoTest.java`
  - `src/test/java/io/appform/dropwizard/sharding/dao/WrapperDaoTransactionReuseTest.java`

- [ ] **Step 1: Add failing wrapper registry tests**

In `WrapperDaoTest`, register the default namespace calculator and add:

```java
@Test
void resolvesCalculatorForEveryForParentCall() {
    dao.forParent("customer1");

    ShardCalculatorRegistry.clear();

    var error = assertThrows(
            IllegalStateException.class,
            () -> dao.forParent("customer1"));
    assertEquals(
            "ShardCalculator has not been registered for tenant: default",
            error.getMessage());
}
```

- [ ] **Step 2: Run wrapper tests and verify the DAO retains its calculator**

Run:

```bash
mvn -q -o -Dtest=WrapperDaoTest,WrapperDaoTransactionReuseTest test
```

Expected: the new test fails because the wrapper continues using the calculator
created in its constructor.

- [ ] **Step 3: Remove wrapper calculator and manager dependencies**

Change the constructors to:

```java
public WrapperDao(
        String dbNamespace,
        List<SessionFactory> sessionFactories,
        Class<DaoType> daoClass) {
    this(dbNamespace, sessionFactories, daoClass, null, null);
}

public WrapperDao(
        String dbNamespace,
        List<SessionFactory> sessionFactories,
        Class<DaoType> daoClass,
        Class[] extraConstructorParamClasses,
        Class[] extraConstructorParamObjects) {
    this.dbNamespace = dbNamespace;
    this.daos = sessionFactories.stream()
            // retain the existing proxy construction body unchanged
            .collect(Collectors.toList());
}
```

Remove the `ShardCalculator` field, `ShardManager` parameter, calculator
construction, and calculator accessor. Change routing to:

```java
public DaoType forParent(final String parentKey) {
    int shardId = ShardCalculatorRegistry.get(dbNamespace)
            .shardId(parentKey);
    return daos.get(shardId);
}
```

- [ ] **Step 4: Update wrapper factories**

In both `MultiTenantDBShardingBundleBase.createWrapperDao` overloads, retain the
unknown-tenant precondition but stop passing `this.shardManagers.get(tenantId)`.

- [ ] **Step 5: Delete `ShardedDao`**

After Tasks 4 and 5 remove all implementations, delete:

```text
src/main/java/io/appform/dropwizard/sharding/dao/ShardedDao.java
```

Search for remaining references:

```bash
grep -R "ShardedDao\\|getShardCalculator()" -n src/main/java
```

Expected: only the bundle-level `getShardCalculator()` method remains; no
`ShardedDao` references remain.

- [ ] **Step 6: Update wrapper tests**

Register the default namespace manager in each setup:

```java
ShardCalculatorTestUtils.register(Map.of(
        DBShardingBundleBase.DEFAULT_NAMESPACE,
        shardManager));
```

Remove the manager argument from `new WrapperDao(...)`. Replace shard-ID
calculations in `WrapperDaoTransactionReuseTest` with:

```java
ShardCalculatorRegistry.get(DBShardingBundleBase.DEFAULT_NAMESPACE)
        .shardId(parentKey);
```

Clear the registry in teardown.

- [ ] **Step 7: Run wrapper and bundle tests**

Run:

```bash
mvn -q -o \
  -Dtest=WrapperDaoTest,WrapperDaoTransactionReuseTest,MultiTenantBalancedDBShardingBundleWithEntityTest,BalancedDBShardingBundleWithEntityTest \
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
  -Dtest=ShardCalculatorTest,ShardCalculatorRegistryTest,MultiTenantLookupDaoTest,MultiTenantRelationalDaoTest,WrapperDaoTest,MultiTenantBalancedDBShardingBundleWithEntityTest,BalancedDBShardingBundleWithEntityTest \
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

- [ ] **Step 1: Find direct DAO construction without registry setup**

Run:

```bash
grep -R "new MultiTenantLookupDao\\|new MultiTenantCacheableLookupDao\\|new MultiTenantRelationalDao\\|new MultiTenantCacheableRelationalDao\\|new WrapperDao" \
  -n src/test/java
```

For each result, verify its setup calls either bundle `run()` or
`ShardCalculatorTestUtils.register(...)`. Add registration when neither is
present.

- [ ] **Step 2: Find teardown gaps**

Run:

```bash
grep -R "ShardCalculatorTestUtils.register\\|ShardCalculatorRegistry.register" \
  -l src/test/java
```

For each direct-registration test class, add:

```java
@AfterEach
void clearShardCalculatorRegistry() {
    ShardCalculatorRegistry.clear();
}
```

Merge this call into an existing teardown method when one exists.

- [ ] **Step 3: Verify forbidden production dependencies are gone**

Run:

```bash
grep -R "Map<String, ShardManager> shardManagers\\|ShardCalculator<String> shardCalculator" \
  -n src/main/java/io/appform/dropwizard/sharding/dao || true
grep -R "getShardCalculator" \
  -n src/main/java/io/appform/dropwizard/sharding/dao || true
```

Expected: no output.

- [ ] **Step 4: Verify all registry call sites are tenant-aware**

Run:

```bash
grep -R "ShardCalculatorRegistry.get()" \
  -n src/main/java src/test/java || true
grep -R "ShardCalculatorRegistry.register(" \
  -n src/main/java
```

Expected: no no-argument `get()` calls. Production registration occurs only in
`MultiTenantDBShardingBundleBase`; production reads occur in bundle accessors
and DAO routing helpers.

- [ ] **Step 5: Run the complete test suite**

Run:

```bash
mvn -q -o test
```

Expected: BUILD SUCCESS.

- [ ] **Step 6: Run final repository checks**

Run:

```bash
git diff --check origin/master...HEAD
git status --short
```

Expected: no whitespace errors and no uncommitted files.

- [ ] **Step 7: Commit fixture-only corrections if Step 1 or Step 2 changed files**

```bash
git add src/test/java
git commit -m "test: isolate tenant calculator registry state" \
  -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

Skip this commit only when Tasks 1-7 already left every fixture isolated and
the worktree is clean.

# Package-Private Per-Tenant Shard Calculator Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement bundle-owned, per-tenant shard calculators with package-private DAO constructors and no global registry.

**Architecture:** Move the complete bundle hierarchy into the `dao` package so bundles can call package-private DAO constructors directly. `MultiTenantDBShardingBundleBase` creates one tenant-bound calculator per tenant, publishes one immutable map after successful initialization, and shares that same map with all multi-tenant DAOs created by the bundle.

**Tech Stack:** Java 11, Dropwizard, Hibernate, Guava, JUnit 5, Maven

---

## File Structure

### Bundle package move

Move these files from `src/main/java/io/appform/dropwizard/sharding/` to
`src/main/java/io/appform/dropwizard/sharding/dao/` and change their package declarations:

- `DBShardingBundleBase.java`
- `DBShardingBundle.java`
- `BalancedDBShardingBundle.java`
- `MultiTenantDBShardingBundleBase.java`
- `MultiTenantDBShardingBundle.java`
- `MultiTenantBalancedDBShardingBundle.java`

The moved classes continue to own configuration, lifecycle, shard managers, session factories,
observers, and DAO factory methods. They additionally own tenant-bound shard calculators.

### Calculator API

- Modify: `src/main/java/io/appform/dropwizard/sharding/utils/ShardCalculator.java`
- Create: `src/test/java/io/appform/dropwizard/sharding/utils/ShardCalculatorTest.java`

`ShardCalculator` becomes tenant-bound and no longer depends on the bundle's default namespace.

### DAO construction and routing

- Modify: `src/main/java/io/appform/dropwizard/sharding/dao/MultiTenantLookupDao.java`
- Modify: `src/main/java/io/appform/dropwizard/sharding/dao/MultiTenantCacheableLookupDao.java`
- Modify: `src/main/java/io/appform/dropwizard/sharding/dao/MultiTenantRelationalDao.java`
- Modify: `src/main/java/io/appform/dropwizard/sharding/dao/MultiTenantCacheableRelationalDao.java`
- Modify: `src/main/java/io/appform/dropwizard/sharding/dao/LookupDao.java`
- Modify: `src/main/java/io/appform/dropwizard/sharding/dao/CacheableLookupDao.java`
- Modify: `src/main/java/io/appform/dropwizard/sharding/dao/RelationalDao.java`
- Modify: `src/main/java/io/appform/dropwizard/sharding/dao/CacheableRelationalDao.java`
- Modify: `src/main/java/io/appform/dropwizard/sharding/dao/WrapperDao.java`
- Delete: `src/main/java/io/appform/dropwizard/sharding/dao/ShardedDao.java`
- Modify: `src/main/java/io/appform/dropwizard/sharding/utils/ShardCalculator.java`
- Create: `src/test/java/io/appform/dropwizard/sharding/api/DaoConstructorVisibilityTest.java`

Multi-tenant DAOs retain one shared immutable calculator map. `WrapperDao` retains one calculator.
All listed constructors become package-private.

### Tests and documentation

Update all tests currently under `src/test/java/io/appform/dropwizard/sharding/` that reference the
bundle hierarchy so their package is `io.appform.dropwizard.sharding.dao` or their imports point to
that package. Update direct DAO fixtures to construct tenant-bound calculators explicitly.

- Modify: `README.md`
- Modify: all affected files reported by:

```bash
git grep -l -E '\b(DBShardingBundleBase|DBShardingBundle|BalancedDBShardingBundle|MultiTenantDBShardingBundleBase|MultiTenantDBShardingBundle|MultiTenantBalancedDBShardingBundle)\b' -- README.md src
```

---

### Task 1: Introduce the tenant-bound `ShardCalculator`

**Files:**
- Create: `src/test/java/io/appform/dropwizard/sharding/utils/ShardCalculatorTest.java`
- Modify: `src/main/java/io/appform/dropwizard/sharding/utils/ShardCalculator.java`

- [ ] **Step 1: Write failing tenant-bound calculator tests**

Create tests that exercise the intended constructor and tenant-free methods:

```java
package io.appform.dropwizard.sharding.utils;

import io.appform.dropwizard.sharding.sharding.BalancedShardManager;
import io.appform.dropwizard.sharding.sharding.impl.ConsistentHashBucketIdExtractor;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShardCalculatorTest {

    @Test
    void routesUsingBoundTenant() {
        var manager = new BalancedShardManager(4);
        var calculator = new ShardCalculator<>(
                "TENANT1",
                manager,
                new ConsistentHashBucketIdExtractor<>(Map.of("TENANT1", manager)));

        int expectedBucket = new ConsistentHashBucketIdExtractor<String>(
                Map.of("TENANT1", manager)).bucketId("TENANT1", "customer-1");

        assertEquals(manager.shardForBucket(expectedBucket), calculator.shardId("customer-1"));
    }

    @Test
    void validatesUsingBoundTenant() {
        var manager = new BalancedShardManager(2);
        var calculator = new ShardCalculator<>(
                "TENANT1",
                manager,
                new ConsistentHashBucketIdExtractor<>(Map.of("TENANT1", manager)));

        assertTrue(calculator.isOnValidShard("customer-1"));
        manager.blacklistShard(calculator.shardId("customer-1"));
        assertFalse(calculator.isOnValidShard("customer-1"));
    }

}
```

- [ ] **Step 2: Run the test and verify RED**

Run:

```bash
mvn -q -o -Dtest=ShardCalculatorTest test
```

Expected: compilation fails because the tenant-bound constructor does not exist.

- [ ] **Step 3: Implement the tenant-bound calculator**

Add the tenant-bound constructor while retaining the old map-backed path temporarily:

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

public int shardId(T key) {
    return shardId(tenantId, key);
}

public boolean isOnValidShard(T key) {
    return isOnValidShard(tenantId, key);
}
```

Change the old constructor to set:

```java
this.tenantId = DBShardingBundleBase.DEFAULT_NAMESPACE;
this.shardManagers = shardManagers;
this.extractor = extractor;
```

Keep that map constructor and the tenant-parameter overloads temporarily so existing DAO callers
compile during the migration. Mark them `@Deprecated`; Task 8 removes them after every caller has
moved to the tenant-bound API. Task 8 also replaces the temporary map field with a final
`ShardManager shardManager`.

- [ ] **Step 4: Run the calculator tests and verify GREEN**

Run:

```bash
mvn -q -o -Dtest=ShardCalculatorTest test
```

Expected: all `ShardCalculatorTest` tests pass and the existing suite still compiles through the
temporary deprecated compatibility methods.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/appform/dropwizard/sharding/utils/ShardCalculator.java \
  src/test/java/io/appform/dropwizard/sharding/utils/ShardCalculatorTest.java
git commit -m "refactor: bind shard calculator to tenant"
```

### Task 2: Move the bundle hierarchy into the DAO package

**Files:**
- Move: `src/main/java/io/appform/dropwizard/sharding/DBShardingBundleBase.java`
- Move: `src/main/java/io/appform/dropwizard/sharding/DBShardingBundle.java`
- Move: `src/main/java/io/appform/dropwizard/sharding/BalancedDBShardingBundle.java`
- Move: `src/main/java/io/appform/dropwizard/sharding/MultiTenantDBShardingBundleBase.java`
- Move: `src/main/java/io/appform/dropwizard/sharding/MultiTenantDBShardingBundle.java`
- Move: `src/main/java/io/appform/dropwizard/sharding/MultiTenantBalancedDBShardingBundle.java`
- Modify: affected main and test Java imports

- [ ] **Step 1: Add a compile-time package assertion**

Create `src/test/java/io/appform/dropwizard/sharding/api/BundlePackageTest.java`:

```java
package io.appform.dropwizard.sharding.api;

import io.appform.dropwizard.sharding.dao.DBShardingBundleBase;
import io.appform.dropwizard.sharding.dao.MultiTenantDBShardingBundleBase;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BundlePackageTest {

    @Test
    void bundleBasesLiveInDaoPackage() {
        assertEquals("io.appform.dropwizard.sharding.dao",
                DBShardingBundleBase.class.getPackageName());
        assertEquals("io.appform.dropwizard.sharding.dao",
                MultiTenantDBShardingBundleBase.class.getPackageName());
    }
}
```

- [ ] **Step 2: Run the package test and verify RED**

Run:

```bash
mvn -q -o -Dtest=BundlePackageTest test
```

Expected: test compilation fails because the bundle classes do not exist in the `dao` package.

- [ ] **Step 3: Move all six bundle files**

Run:

```bash
git mv src/main/java/io/appform/dropwizard/sharding/DBShardingBundleBase.java \
  src/main/java/io/appform/dropwizard/sharding/dao/DBShardingBundleBase.java
git mv src/main/java/io/appform/dropwizard/sharding/DBShardingBundle.java \
  src/main/java/io/appform/dropwizard/sharding/dao/DBShardingBundle.java
git mv src/main/java/io/appform/dropwizard/sharding/BalancedDBShardingBundle.java \
  src/main/java/io/appform/dropwizard/sharding/dao/BalancedDBShardingBundle.java
git mv src/main/java/io/appform/dropwizard/sharding/MultiTenantDBShardingBundleBase.java \
  src/main/java/io/appform/dropwizard/sharding/dao/MultiTenantDBShardingBundleBase.java
git mv src/main/java/io/appform/dropwizard/sharding/MultiTenantDBShardingBundle.java \
  src/main/java/io/appform/dropwizard/sharding/dao/MultiTenantDBShardingBundle.java
git mv src/main/java/io/appform/dropwizard/sharding/MultiTenantBalancedDBShardingBundle.java \
  src/main/java/io/appform/dropwizard/sharding/dao/MultiTenantBalancedDBShardingBundle.java
```

Change each package declaration to:

```java
package io.appform.dropwizard.sharding.dao;
```

Add explicit imports for every type that remains in `io.appform.dropwizard.sharding`; compile after
the move will identify any formerly same-package type. In particular, import `BundleCommonBase`
and `ShardInfoProvider` in `MultiTenantDBShardingBundleBase`.

- [ ] **Step 4: Update all bundle references**

For each file returned by:

```bash
git grep -l -E '\b(DBShardingBundleBase|DBShardingBundle|BalancedDBShardingBundle|MultiTenantDBShardingBundleBase|MultiTenantDBShardingBundle|MultiTenantBalancedDBShardingBundle)\b' -- README.md src
```

replace imports from `io.appform.dropwizard.sharding` with
`io.appform.dropwizard.sharding.dao`.

Move all top-level bundle test package declarations from:

```java
package io.appform.dropwizard.sharding;
```

to:

```java
package io.appform.dropwizard.sharding.dao;
```

for the bundle-focused tests and bases under `src/test/java/io/appform/dropwizard/sharding/`.
Import non-DAO classes from their existing packages when compilation reports a formerly
same-package reference.

- [ ] **Step 5: Run the package and bundle tests**

Run:

```bash
mvn -q -o -Dtest=BundlePackageTest,BalancedDBShardingBundleWithEntityTest,MultiTenantBalancedDBShardingBundleWithEntityTest test
```

Expected: tests compile and pass.

- [ ] **Step 6: Commit**

```bash
git add src/main src/test README.md
git commit -m "refactor: move bundles into dao package"
```

### Task 3: Publish one immutable calculator map per bundle

**Files:**
- Modify: `src/main/java/io/appform/dropwizard/sharding/dao/MultiTenantDBShardingBundleBase.java`
- Modify: `src/main/java/io/appform/dropwizard/sharding/dao/DBShardingBundleBase.java`
- Modify: `src/test/java/io/appform/dropwizard/sharding/dao/MultiTenantDBShardingBundleTestBase.java`
- Modify: `src/test/java/io/appform/dropwizard/sharding/dao/BundleMvccSnapshotTest.java`

- [ ] **Step 1: Write failing bundle ownership tests**

Add to `MultiTenantDBShardingBundleTestBase`:

```java
@Test
void createsOneCalculatorPerTenant() {
    var bundle = getBundle();
    bundle.initialize(bootstrap);
    bundle.run(testConfig, environment);

    assertNotSame(
            bundle.getShardCalculator("TENANT1"),
            bundle.getShardCalculator("TENANT2"));
    assertSame(
            bundle.getShardCalculator("TENANT1"),
            bundle.getShardCalculators().get("TENANT1"));
}
```

Add a failure-path bundle whose second `createShardManager` call throws:

```java
AtomicInteger managersCreated = new AtomicInteger();
MultiTenantDBShardingBundleBase<TestConfig> bundle =
        new MultiTenantBalancedDBShardingBundle<TestConfig>(
                "io.appform.dropwizard.sharding.dao.testdata.entities") {
            @Override
            protected MultiTenantShardedHibernateFactory getConfig(TestConfig config) {
                return config.getShards();
            }

            @Override
            protected ShardManager createShardManager(
                    int numShards,
                    ShardBlacklistingStore blacklistingStore) {
                if (managersCreated.incrementAndGet() == 2) {
                    throw new IllegalStateException("second tenant failed");
                }
                return super.createShardManager(numShards, blacklistingStore);
            }
        };

bundle.initialize(bootstrap);
assertThrows(IllegalStateException.class, () -> bundle.run(testConfig, environment));
assertTrue(bundle.getShardCalculators().isEmpty());
assertThrows(IllegalStateException.class,
        () -> bundle.createParentObjectDao(Order.class));
```

Add to `BundleMvccSnapshotTest` a test that initializes two default-namespace bundles against
separate H2 databases with one and four data sources, then asserts:

```java
assertNotSame(writeBundle.getShardCalculator(), readBundle.getShardCalculator());
int writeShard = writeBundle.getShardCalculator().shardId("customer");
int readShard = readBundle.getShardCalculator().shardId("customer");
assertTrue(writeShard >= 0 && writeShard < writeBundle.getSessionFactories().size());
assertTrue(readShard >= 0 && readShard < readBundle.getSessionFactories().size());
```

Build the one-shard and four-shard `ShardedHibernateFactory` values with the existing
`buildWriteDataSourceFactory` helper and unique database URLs.

- [ ] **Step 2: Run the ownership tests and verify RED**

Run:

```bash
mvn -q -o -Dtest=MultiTenantBalancedDBShardingBundleWithEntityTest,BundleMvccSnapshotTest test
```

Expected: compilation fails because calculator accessors and bundle-owned maps do not exist.

- [ ] **Step 3: Add bundle-owned calculator publication**

In `MultiTenantDBShardingBundleBase`, add:

```java
private Map<String, ShardCalculator<String>> shardCalculators = Map.of();

Map<String, ShardCalculator<String>> getShardCalculators() {
    return shardCalculators;
}

ShardCalculator<String> getShardCalculator(String tenantId) {
    ShardCalculator<String> calculator = shardCalculators.get(tenantId);
    if (calculator == null) {
        throw new IllegalStateException(
                "ShardCalculator has not been initialized for tenant: " + tenantId);
    }
    return calculator;
}

private Map<String, ShardCalculator<String>> initializedShardCalculators() {
    Preconditions.checkState(
            !shardCalculators.isEmpty(),
            "Shard calculators have not been initialized");
    return shardCalculators;
}
```

At the start of `run()`, create:

```java
Map<String, ShardCalculator<String>> calculators = new LinkedHashMap<>();
```

After each `ShardManager` is created, add:

```java
Map<String, ShardManager> tenantManagers = Map.of(tenantId, shardManager);
calculators.put(
        tenantId,
        new ShardCalculator<>(
                tenantId,
                shardManager,
                new ConsistentHashBucketIdExtractor<>(tenantManagers)));
```

After the tenant loop completes successfully, publish:

```java
this.shardCalculators = Map.copyOf(calculators);
```

Do not assign the field inside the tenant loop.

In `DBShardingBundleBase`, add a package-private accessor:

```java
ShardCalculator<String> getShardCalculator() {
    return delegate.getShardCalculator(dbNamespace);
}
```

- [ ] **Step 4: Guard every DAO factory**

At the beginning of each `createParentObjectDao`, `createRelatedObjectDao`, and
`createWrapperDao` method in `MultiTenantDBShardingBundleBase`, obtain:

```java
Map<String, ShardCalculator<String>> calculators = initializedShardCalculators();
```

Keep the initialization check even though the legacy constructor calls still use shard managers in
this intermediate commit. Tasks 4, 5, and 7 replace those arguments with the calculator values.

- [ ] **Step 5: Run the ownership tests and verify GREEN**

Run:

```bash
mvn -q -o -Dtest=MultiTenantBalancedDBShardingBundleWithEntityTest,BundleMvccSnapshotTest test
```

Expected: tests pass, including failed initialization and same-namespace isolation.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/io/appform/dropwizard/sharding/dao/DBShardingBundleBase.java \
  src/main/java/io/appform/dropwizard/sharding/dao/MultiTenantDBShardingBundleBase.java \
  src/test/java/io/appform/dropwizard/sharding/dao/MultiTenantDBShardingBundleTestBase.java \
  src/test/java/io/appform/dropwizard/sharding/dao/BundleMvccSnapshotTest.java
git commit -m "feat: own tenant calculators in bundle"
```

### Task 4: Inject the shared calculator map into lookup DAOs

**Files:**
- Modify: `src/main/java/io/appform/dropwizard/sharding/dao/MultiTenantLookupDao.java`
- Modify: `src/main/java/io/appform/dropwizard/sharding/dao/MultiTenantCacheableLookupDao.java`
- Modify: `src/main/java/io/appform/dropwizard/sharding/dao/MultiTenantDBShardingBundleBase.java`
- Modify: `src/test/java/io/appform/dropwizard/sharding/dao/MultiTenantLookupDaoTest.java`
- Modify: `src/test/java/io/appform/dropwizard/sharding/dao/MultiTenantCacheableLookupDaoTest.java`

- [ ] **Step 1: Update lookup tests to express the intended constructor**

In each test setup, build calculators once:

```java
Map<String, ShardCalculator<String>> calculators = shardManagers.entrySet().stream()
        .collect(Collectors.toUnmodifiableMap(
                Map.Entry::getKey,
                entry -> {
                    String tenantId = entry.getKey();
                    ShardManager manager = entry.getValue();
                    return new ShardCalculator<>(
                            tenantId,
                            manager,
                            new ConsistentHashBucketIdExtractor<>(
                                    Map.of(tenantId, manager)));
                }));
```

Pass `calculators` to `MultiTenantLookupDao` and `MultiTenantCacheableLookupDao` instead of
`shardManagers`.

Add:

```java
@Test
void unknownTenantMessageIsPreserved() {
    IllegalArgumentException error = assertThrows(
            IllegalArgumentException.class,
            () -> lookupDao.get("UNKNOWN", "key"));

    assertEquals("Unknown tenant: UNKNOWN", error.getMessage());
}
```

- [ ] **Step 2: Run lookup tests and verify RED**

Run:

```bash
mvn -q -o -Dtest=MultiTenantLookupDaoTest,MultiTenantCacheableLookupDaoTest test
```

Expected: compilation fails because constructors still expect shard managers.

- [ ] **Step 3: Change lookup constructors and routing**

In `MultiTenantLookupDao`, replace the calculator field with:

```java
private final Map<String, ShardCalculator<String>> shardCalculators;
```

Change its constructor to package-private and accept:

```java
Map<String, ShardCalculator<String>> shardCalculators
```

Assign the reference directly:

```java
this.shardCalculators = shardCalculators;
```

Do not call `Map.copyOf` in the DAO.

Preserve every existing inline tenant guard. Replace each:

```java
shardCalculator.shardId(tenantId, key)
```

with:

```java
shardCalculators.get(tenantId).shardId(key)
```

Change `MultiTenantCacheableLookupDao` to a package-private constructor accepting the same map and
passing it to `super`.

Update the bundle factory calls to pass `initializedShardCalculators()`.

- [ ] **Step 4: Run lookup tests and verify GREEN**

Run:

```bash
mvn -q -o -Dtest=MultiTenantLookupDaoTest,MultiTenantCacheableLookupDaoTest test
```

Expected: all lookup tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/appform/dropwizard/sharding/dao/MultiTenantLookupDao.java \
  src/main/java/io/appform/dropwizard/sharding/dao/MultiTenantCacheableLookupDao.java \
  src/main/java/io/appform/dropwizard/sharding/dao/MultiTenantDBShardingBundleBase.java \
  src/test/java/io/appform/dropwizard/sharding/dao/MultiTenantLookupDaoTest.java \
  src/test/java/io/appform/dropwizard/sharding/dao/MultiTenantCacheableLookupDaoTest.java
git commit -m "refactor: inject tenant calculators into lookup daos"
```

### Task 5: Inject the shared calculator map into relational DAOs

**Files:**
- Modify: `src/main/java/io/appform/dropwizard/sharding/dao/MultiTenantRelationalDao.java`
- Modify: `src/main/java/io/appform/dropwizard/sharding/dao/MultiTenantCacheableRelationalDao.java`
- Modify: `src/main/java/io/appform/dropwizard/sharding/dao/MultiTenantDBShardingBundleBase.java`
- Modify: `src/test/java/io/appform/dropwizard/sharding/dao/MultiTenantRelationalDaoTest.java`
- Modify: `src/test/java/io/appform/dropwizard/sharding/dao/RelationalDaoTest.java`
- Modify: `src/test/java/io/appform/dropwizard/sharding/dao/MultiTenantRelationalReadOnlyLockedContextTest.java`
- Modify: `src/test/java/io/appform/dropwizard/sharding/dao/RelationalReadOnlyLockedContextTest.java`
- Modify: `src/test/java/io/appform/dropwizard/sharding/dao/MultiTenantDBShardingBundleTestBase.java`

- [ ] **Step 1: Update relational tests to pass immutable calculators**

Build the immutable calculator map explicitly:

```java
Map<String, ShardCalculator<String>> calculators = shardManagers.entrySet().stream()
        .collect(Collectors.toUnmodifiableMap(
                Map.Entry::getKey,
                entry -> new ShardCalculator<>(
                        entry.getKey(),
                        entry.getValue(),
                        new ConsistentHashBucketIdExtractor<>(
                                Map.of(entry.getKey(), entry.getValue())))));
```

Pass `calculators` to every direct `MultiTenantRelationalDao` and
`MultiTenantCacheableRelationalDao` construction.

Replace test uses of:

```java
relationalDao.getShardCalculator().shardId(tenantId, id)
```

with:

```java
calculators.get(tenantId).shardId(id)
```

Retain `calculators` as a test field where helper methods need it. Apply the same change in
`RelationalDaoTest`, using a single `ShardCalculator<String>` fixture and `shardId(id)`.

Add an unknown-tenant assertion:

```java
IllegalArgumentException error = assertThrows(
        IllegalArgumentException.class,
        () -> relationalDao.get("UNKNOWN", "parent", "id"));
assertEquals("Unknown tenant: UNKNOWN", error.getMessage());
```

- [ ] **Step 2: Run relational tests and verify RED**

Run:

```bash
mvn -q -o -Dtest=MultiTenantRelationalDaoTest,MultiTenantRelationalReadOnlyLockedContextTest,RelationalReadOnlyLockedContextTest test
```

Expected: compilation fails because constructors still expect shard managers.

- [ ] **Step 3: Change relational constructors and routing**

In `MultiTenantRelationalDao`, replace the single calculator field with:

```java
private final Map<String, ShardCalculator<String>> shardCalculators;
```

Make the constructor package-private, accept the immutable calculator map, and retain it without
copying.

Preserve all 36 existing inline tenant guards. Replace every tenant-parameter calculator call with:

```java
shardCalculators.get(tenantId).shardId(parentKey)
```

For context-bound operations, continue selecting:

```java
daos.get(context.getTenantId()).get(context.getShardId())
```

Do not recalculate the shard and do not add context ownership validation.

Make `MultiTenantCacheableRelationalDao`'s constructor package-private, accept the calculator map,
and pass it to `super`.

Update bundle factory calls to pass `initializedShardCalculators()`.

- [ ] **Step 4: Prove every multi-tenant factory shares the same map reference**

In `MultiTenantDBShardingBundleTestBase`, create lookup, cacheable lookup, relational, and cacheable
relational DAOs from one initialized bundle. Read each DAO's private `shardCalculators` field with
`FieldUtils.readField(dao, "shardCalculators", true)` and assert:

```java
Map<String, ShardCalculator<String>> expected = bundle.getShardCalculators();
assertSame(expected, FieldUtils.readField(lookupDao, "shardCalculators", true));
assertSame(expected, FieldUtils.readField(cacheableLookupDao, "shardCalculators", true));
assertSame(expected, FieldUtils.readField(relationalDao, "shardCalculators", true));
assertSame(expected, FieldUtils.readField(cacheableRelationalDao, "shardCalculators", true));
assertThrows(UnsupportedOperationException.class,
        () -> expected.put("OTHER", expected.get("TENANT1")));
```

Construct the cacheable variants with:

```java
Map<String, LookupCache<Order>> lookupCaches = Map.of(
        "TENANT1", mock(LookupCache.class),
        "TENANT2", mock(LookupCache.class));
Map<String, RelationalCache<Order>> relationalCaches = Map.of(
        "TENANT1", mock(RelationalCache.class),
        "TENANT2", mock(RelationalCache.class));
```

- [ ] **Step 5: Run relational and factory wiring tests**

Run:

```bash
mvn -q -o -Dtest=MultiTenantRelationalDaoTest,MultiTenantRelationalReadOnlyLockedContextTest,RelationalReadOnlyLockedContextTest,MultiTenantBalancedDBShardingBundleWithEntityTest test
```

Expected: all relational and context tests pass.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/io/appform/dropwizard/sharding/dao/MultiTenantRelationalDao.java \
  src/main/java/io/appform/dropwizard/sharding/dao/MultiTenantCacheableRelationalDao.java \
  src/main/java/io/appform/dropwizard/sharding/dao/MultiTenantDBShardingBundleBase.java \
  src/test/java/io/appform/dropwizard/sharding/dao/MultiTenantRelationalDaoTest.java \
  src/test/java/io/appform/dropwizard/sharding/dao/RelationalDaoTest.java \
  src/test/java/io/appform/dropwizard/sharding/dao/MultiTenantRelationalReadOnlyLockedContextTest.java \
  src/test/java/io/appform/dropwizard/sharding/dao/RelationalReadOnlyLockedContextTest.java \
  src/test/java/io/appform/dropwizard/sharding/dao/MultiTenantDBShardingBundleTestBase.java
git commit -m "refactor: inject tenant calculators into relational daos"
```

### Task 6: Make all DAO constructors package-private

**Files:**
- Create: `src/test/java/io/appform/dropwizard/sharding/api/DaoConstructorVisibilityTest.java`
- Modify: all nine DAO classes listed in the File Structure section
- Modify: affected direct-construction tests

- [ ] **Step 1: Write the constructor visibility test**

```java
package io.appform.dropwizard.sharding.api;

import io.appform.dropwizard.sharding.dao.CacheableLookupDao;
import io.appform.dropwizard.sharding.dao.CacheableRelationalDao;
import io.appform.dropwizard.sharding.dao.LookupDao;
import io.appform.dropwizard.sharding.dao.MultiTenantCacheableLookupDao;
import io.appform.dropwizard.sharding.dao.MultiTenantCacheableRelationalDao;
import io.appform.dropwizard.sharding.dao.MultiTenantLookupDao;
import io.appform.dropwizard.sharding.dao.MultiTenantRelationalDao;
import io.appform.dropwizard.sharding.dao.RelationalDao;
import io.appform.dropwizard.sharding.dao.WrapperDao;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class DaoConstructorVisibilityTest {

    @Test
    void daoConstructorsAreNotPublicOrProtected() {
        List.of(
                LookupDao.class,
                CacheableLookupDao.class,
                RelationalDao.class,
                CacheableRelationalDao.class,
                MultiTenantLookupDao.class,
                MultiTenantCacheableLookupDao.class,
                MultiTenantRelationalDao.class,
                MultiTenantCacheableRelationalDao.class,
                WrapperDao.class)
                .forEach(type -> assertTrue(
                        List.of(type.getDeclaredConstructors()).stream().allMatch(constructor -> {
                            int modifiers = constructor.getModifiers();
                            return !Modifier.isPublic(modifiers)
                                    && !Modifier.isProtected(modifiers)
                                    && !Modifier.isPrivate(modifiers);
                        }),
                        type.getName()));
    }
}
```

- [ ] **Step 2: Run the visibility test and verify RED**

Run:

```bash
mvn -q -o -Dtest=DaoConstructorVisibilityTest test
```

Expected: failure because DAO constructors are public.

- [ ] **Step 3: Remove constructor access modifiers**

For every constructor in the nine DAO classes, remove `public` so the declaration is
package-private:

```java
LookupDao(...)
```

Do not make constructors `protected` or `private`.

Because DAO behavior tests in `io.appform.dropwizard.sharding.dao` remain in the same package,
direct fixture construction continues to compile. Move `ScrollTest` into the DAO test package or
rewrite it to create its DAO through a test bundle.

- [ ] **Step 4: Run visibility and DAO tests**

Run:

```bash
mvn -q -o -Dtest=DaoConstructorVisibilityTest,LookupDaoTest,RelationalDaoTest,CacheableLookupDaoTest,WrapperDaoTest,WrapperDaoTransactionReuseTest,LockTest,ParentChildTest,ScrollTest test
```

Expected: all tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/appform/dropwizard/sharding/dao \
  src/test/java/io/appform/dropwizard/sharding/api/DaoConstructorVisibilityTest.java \
  src/test/java/io/appform/dropwizard/sharding
git commit -m "refactor: restrict dao construction to bundle package"
```

### Task 7: Inject the bundle calculator into `WrapperDao`

**Files:**
- Modify: `src/main/java/io/appform/dropwizard/sharding/dao/WrapperDao.java`
- Modify: `src/main/java/io/appform/dropwizard/sharding/dao/MultiTenantDBShardingBundleBase.java`
- Modify: `src/test/java/io/appform/dropwizard/sharding/dao/WrapperDaoTest.java`
- Modify: `src/test/java/io/appform/dropwizard/sharding/dao/WrapperDaoTransactionReuseTest.java`

- [ ] **Step 1: Update wrapper tests to pass a calculator**

Add a test field and construct it before the DAO:

```java
calculator = new ShardCalculator<>(
        DBShardingBundleBase.DEFAULT_NAMESPACE,
        shardManager,
        new ConsistentHashBucketIdExtractor<>(
                Map.of(DBShardingBundleBase.DEFAULT_NAMESPACE, shardManager)));
```

Pass `calculator` instead of `shardManager` to `WrapperDao`.

Replace every `dao.getShardCalculator().shardId(DEFAULT_NAMESPACE, parentKey)` in
`WrapperDaoTransactionReuseTest` with `calculator.shardId(parentKey)`.

- [ ] **Step 2: Run wrapper tests and verify RED**

Run:

```bash
mvn -q -o -Dtest=WrapperDaoTest,WrapperDaoTransactionReuseTest test
```

Expected: compilation fails because `WrapperDao` expects `ShardManager`.

- [ ] **Step 3: Change `WrapperDao`**

Retain:

```java
private final ShardCalculator<String> shardCalculator;
```

Change both package-private constructors to accept `ShardCalculator<String>` directly. Remove
`ShardManager`, `ConsistentHashBucketIdExtractor`, and temporary map construction.

Route with:

```java
return daos.get(shardCalculator.shardId(parentKey));
```

In both `MultiTenantDBShardingBundleBase.createWrapperDao` overloads, pass:

```java
getShardCalculator(tenantId)
```

- [ ] **Step 4: Run wrapper tests and verify GREEN**

Run:

```bash
mvn -q -o -Dtest=WrapperDaoTest,WrapperDaoTransactionReuseTest test
```

Expected: all wrapper tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/appform/dropwizard/sharding/dao/WrapperDao.java \
  src/main/java/io/appform/dropwizard/sharding/dao/MultiTenantDBShardingBundleBase.java \
  src/test/java/io/appform/dropwizard/sharding/dao/WrapperDaoTest.java \
  src/test/java/io/appform/dropwizard/sharding/dao/WrapperDaoTransactionReuseTest.java
git commit -m "refactor: inject calculator into wrapper dao"
```

### Task 8: Remove calculator accessors and `ShardedDao`

**Files:**
- Delete: `src/main/java/io/appform/dropwizard/sharding/dao/ShardedDao.java`
- Modify: `src/main/java/io/appform/dropwizard/sharding/dao/LookupDao.java`
- Modify: `src/main/java/io/appform/dropwizard/sharding/dao/RelationalDao.java`
- Modify: `src/main/java/io/appform/dropwizard/sharding/dao/MultiTenantLookupDao.java`
- Modify: `src/main/java/io/appform/dropwizard/sharding/dao/MultiTenantRelationalDao.java`
- Modify: `src/main/java/io/appform/dropwizard/sharding/dao/WrapperDao.java`
- Create: `src/test/java/io/appform/dropwizard/sharding/api/ShardCalculatorApiTest.java`

- [ ] **Step 1: Write failing API-removal tests**

Create:

```java
package io.appform.dropwizard.sharding.api;

import io.appform.dropwizard.sharding.dao.LookupDao;
import io.appform.dropwizard.sharding.dao.MultiTenantLookupDao;
import io.appform.dropwizard.sharding.dao.MultiTenantRelationalDao;
import io.appform.dropwizard.sharding.dao.RelationalDao;
import io.appform.dropwizard.sharding.dao.WrapperDao;
import io.appform.dropwizard.sharding.utils.ShardCalculator;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ShardCalculatorApiTest {

    @Test
    void daosDoNotExposeShardCalculators() {
        List.of(
                LookupDao.class,
                RelationalDao.class,
                MultiTenantLookupDao.class,
                MultiTenantRelationalDao.class,
                WrapperDao.class)
                .forEach(type -> assertFalse(
                        Arrays.stream(type.getMethods())
                                .anyMatch(method ->
                                        method.getName().equals("getShardCalculator"))));
    }

    @Test
    void shardedDaoTypeIsRemoved() {
        assertThrows(
                ClassNotFoundException.class,
                () -> Class.forName("io.appform.dropwizard.sharding.dao.ShardedDao"));
    }

    @Test
    void legacyShardCalculatorApiIsRemoved() {
        assertFalse(Arrays.stream(ShardCalculator.class.getConstructors())
                .anyMatch(constructor -> constructor.getParameterCount() == 2));
        assertFalse(Arrays.stream(ShardCalculator.class.getMethods())
                .anyMatch(method -> method.getName().equals("shardId")
                        && method.getParameterCount() == 2));
        assertFalse(Arrays.stream(ShardCalculator.class.getMethods())
                .anyMatch(method -> method.getName().equals("isOnValidShard")
                        && method.getParameterCount() == 2));
    }
}
```

- [ ] **Step 2: Run the API test and verify RED**

Run:

```bash
mvn -q -o -Dtest=ShardCalculatorApiTest test
```

Expected: failures because DAO accessors and `ShardedDao` still exist.

- [ ] **Step 3: Remove the API**

Delete `ShardedDao.java`. Remove the deprecated map constructor and tenant-parameter methods from
`ShardCalculator`, replace the temporary map field with `private final ShardManager shardManager`,
and make `shardId(T)` and `isOnValidShard(T)` route directly through that manager.

Remove `implements ShardedDao<T>` from all DAO classes. Remove Lombok `@Getter` annotations from
calculator fields and remove explicit `getShardCalculator()` forwarding methods from `LookupDao`
and `RelationalDao`.

Keep bundle-level calculator accessors used for ownership tests and wrapper construction.

- [ ] **Step 4: Run the API and DAO tests**

Run:

```bash
mvn -q -o -Dtest=ShardCalculatorApiTest,LookupDaoTest,RelationalDaoTest,WrapperDaoTest test
```

Expected: all tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/appform/dropwizard/sharding/dao \
  src/main/java/io/appform/dropwizard/sharding/utils/ShardCalculator.java \
  src/test/java/io/appform/dropwizard/sharding/api/ShardCalculatorApiTest.java
git commit -m "refactor: remove dao shard calculator api"
```

### Task 9: Update documentation and verify the complete alternative

**Files:**
- Modify: `README.md`
- Modify: every Java test or example still reported by the old-package and legacy-API searches below
- Test: full Maven suite

- [ ] **Step 1: Update public imports and construction guidance**

Replace README imports such as:

```java
import io.appform.dropwizard.sharding.BalancedDBShardingBundle;
```

with:

```java
import io.appform.dropwizard.sharding.dao.BalancedDBShardingBundle;
```

Document that DAO constructors are package-private and applications obtain DAOs through:

```java
bundle.createParentObjectDao(Entity.class);
bundle.createRelatedObjectDao(RelatedEntity.class);
bundle.createWrapperDao(CustomDao.class);
```

- [ ] **Step 2: Verify no old bundle package imports remain**

Run:

```bash
git grep -n -E 'io\.appform\.dropwizard\.sharding\.(DBShardingBundleBase|DBShardingBundle|BalancedDBShardingBundle|MultiTenantDBShardingBundleBase|MultiTenantDBShardingBundle|MultiTenantBalancedDBShardingBundle)' -- README.md src
```

Expected: no output.

- [ ] **Step 3: Verify no registry or legacy calculator API exists**

Run:

```bash
git grep -n -E 'ShardCalculatorRegistry|new ShardCalculator<.*shardManagers|shardCalculator\.shardId\(tenantId|getShardCalculator\(\)' -- src/main
```

Expected: no registry references, no map-based calculator construction, no tenant-parameter routing,
and only the intentional bundle-level `getShardCalculator()` method if the final pattern matches it.

- [ ] **Step 4: Run focused API tests**

Run:

```bash
mvn -q -o -Dtest=ShardCalculatorTest,BundlePackageTest,DaoConstructorVisibilityTest,ShardCalculatorApiTest test
```

Expected: all focused API tests pass.

- [ ] **Step 5: Run the complete suite**

Run:

```bash
mvn -q -o test
```

Expected: all tests pass with zero failures and errors.

- [ ] **Step 6: Review the branch diff**

Run:

```bash
git diff --check
git diff --stat origin/master...HEAD
git status --short
```

Expected: no whitespace errors and no unrelated changes.

- [ ] **Step 7: Commit**

```bash
git add README.md src
git commit -m "docs: update package-private dao usage"
```

- [ ] **Step 8: Request strict code review**

Review `origin/master...HEAD` for:

- bundle-instance calculator isolation
- failed initialization publication
- constructor visibility
- package migration completeness
- preserved inline tenant guards
- context-bound relational routing
- accidental public API beyond the approved breaking changes

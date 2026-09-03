# Per-Tenant ShardCalculator Registry Correction Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Simplify the per-tenant shard calculator implementation so DAOs resolve a static registry directly without registry constructor injection, calculator snapshots, tenant-validation helpers, or relational context changes.

**Architecture:** `ShardCalculatorRegistry` is a static concurrent tenant map. Bundles register one tenant-bound calculator per tenant, and DAO routing sites call `ShardCalculatorRegistry.get(tenantId)` directly. The correction restores pre-feature DAO validation and relational context behavior while retaining bundle-level calculator creation and accessors.

**Tech Stack:** Java 11+, Maven, JUnit 5, Guava, Dropwizard, Hibernate

---

## Task 1: Simplify the registry and bundle registration

**Files:**
- Modify: `src/main/java/io/appform/dropwizard/sharding/utils/ShardCalculatorRegistry.java`
- Modify: `src/test/java/io/appform/dropwizard/sharding/utils/ShardCalculatorRegistryTest.java`
- Modify: `src/test/java/io/appform/dropwizard/sharding/utils/ShardCalculatorTestUtils.java`
- Modify: `src/main/java/io/appform/dropwizard/sharding/MultiTenantDBShardingBundleBase.java`
- Modify: `src/test/java/io/appform/dropwizard/sharding/MultiTenantDBShardingBundleTestBase.java`
- Modify: `src/test/java/io/appform/dropwizard/sharding/BundleMvccSnapshotTest.java`

- [ ] **Step 1: Replace instance-registry tests with the static contract**

Rewrite `ShardCalculatorRegistryTest` around static setup and teardown:

```java
@AfterEach
void tearDown() {
    ShardCalculatorRegistry.clear();
}

@Test
void returnsCalculatorForEachTenant() {
    var tenant1 = calculatorFor("TENANT1", 2);
    var tenant2 = calculatorFor("TENANT2", 4);

    ShardCalculatorRegistry.register("TENANT1", tenant1);
    ShardCalculatorRegistry.register("TENANT2", tenant2);

    assertSame(tenant1, ShardCalculatorRegistry.get("TENANT1"));
    assertSame(tenant2, ShardCalculatorRegistry.get("TENANT2"));
}

@Test
void duplicateRegistrationOverwritesCalculator() {
    var first = calculatorFor("TENANT1", 2);
    var second = calculatorFor("TENANT1", 4);

    ShardCalculatorRegistry.register("TENANT1", first);
    ShardCalculatorRegistry.register("TENANT1", second);

    assertSame(second, ShardCalculatorRegistry.get("TENANT1"));
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
void clearRemovesEveryTenant() {
    ShardCalculatorRegistry.register(
            "TENANT1",
            calculatorFor("TENANT1", 2));

    ShardCalculatorRegistry.clear();

    assertThrows(
            IllegalStateException.class,
            () -> ShardCalculatorRegistry.get("TENANT1"));
}
```

Retain a bounded concurrent-read test after registration. Delete tests for
batch publication, defensive snapshots, independent registry instances, and
instance-only clearing.

- [ ] **Step 2: Run the registry tests to verify the current API fails**

Run:

```bash
mvn -q -o -Dtest=ShardCalculatorRegistryTest test
```

Expected: test compilation fails because `register(String, calculator)` and
static registry methods do not exist.

- [ ] **Step 3: Implement the static concurrent registry**

Replace the registry with:

```java
public final class ShardCalculatorRegistry {

    private static final ConcurrentMap<String, ShardCalculator<String>>
            CALCULATORS = new ConcurrentHashMap<>();

    private ShardCalculatorRegistry() {
    }

    public static void register(
            String tenantId,
            ShardCalculator<String> calculator) {
        CALCULATORS.put(
                Objects.requireNonNull(tenantId, "tenantId"),
                Objects.requireNonNull(calculator, "calculator"));
    }

    public static ShardCalculator<String> get(String tenantId) {
        var calculator = CALCULATORS.get(
                Objects.requireNonNull(tenantId, "tenantId"));
        if (calculator == null) {
            throw new IllegalStateException(
                    "ShardCalculator has not been registered for tenant: "
                            + tenantId);
        }
        return calculator;
    }

    @VisibleForTesting
    public static void clear() {
        CALCULATORS.clear();
    }
}
```

Remove immutable-snapshot, batch-map, and public-constructor code.

- [ ] **Step 4: Replace the test registry factory with static registration**

Change `ShardCalculatorTestUtils` to:

```java
public static void register(
        Map<String, ShardManager> shardManagers) {
    shardManagers.forEach((tenantId, shardManager) ->
            ShardCalculatorRegistry.register(
                    tenantId,
                    new ShardCalculator<>(
                            tenantId,
                            shardManager,
                            new ConsistentHashBucketIdExtractor<>(
                                    Map.of(tenantId, shardManager)))));
}
```

Delete `registryFor`.

- [ ] **Step 5: Register calculators directly from the bundle**

Delete the `shardCalculatorRegistry` field and local `shardCalculators` map
from `MultiTenantDBShardingBundleBase`.

After creating each tenant's `ShardManager`, register:

```java
ShardCalculatorRegistry.register(
        tenantId,
        new ShardCalculator<>(
                tenantId,
                shardManager,
                new ConsistentHashBucketIdExtractor<>(
                        Map.of(tenantId, shardManager))));
```

Change the bundle accessor to:

```java
public ShardCalculator<String> getShardCalculator(String tenantId) {
    return ShardCalculatorRegistry.get(tenantId);
}
```

Do not pass a registry from DAO factory methods; Task 2 changes their
constructors.

- [ ] **Step 6: Update bundle tests**

Add static cleanup to the shared bundle test bases:

```java
@AfterEach
void clearShardCalculatorRegistry() {
    ShardCalculatorRegistry.clear();
}
```

Change `BundleMvccSnapshotTest` to assert that both bundles initialize and the
latest default registration is accessible. Remove the assertion that the two
bundles own distinct calculators.

Remove failed-initialization assertions that depended on atomic batch
publication; partial bundle registration is not part of this design.

- [ ] **Step 7: Run focused registry and bundle tests**

Run:

```bash
mvn -q -o \
  -Dtest=ShardCalculatorRegistryTest,ShardCalculatorTest,MultiTenantBalancedDBShardingBundleWithEntityTest,BalancedDBShardingBundleWithEntityTest,BundleMvccSnapshotTest \
  test
```

Expected: PASS after Task 2 completes the constructor migration. If compilation
still fails only at DAO constructor calls, continue directly to Task 2 before
committing.

## Task 2: Remove registry injection and validation helpers from DAOs

**Files:**
- Modify: `src/main/java/io/appform/dropwizard/sharding/dao/MultiTenantLookupDao.java`
- Modify: `src/main/java/io/appform/dropwizard/sharding/dao/MultiTenantCacheableLookupDao.java`
- Modify: `src/main/java/io/appform/dropwizard/sharding/dao/MultiTenantRelationalDao.java`
- Modify: `src/main/java/io/appform/dropwizard/sharding/dao/MultiTenantCacheableRelationalDao.java`
- Modify: `src/main/java/io/appform/dropwizard/sharding/dao/WrapperDao.java`
- Modify: `src/main/java/io/appform/dropwizard/sharding/MultiTenantDBShardingBundleBase.java`

- [ ] **Step 1: Remove registry parameters and fields**

For `MultiTenantLookupDao` and `MultiTenantRelationalDao`, use constructors of
this form:

```java
public MultiTenantLookupDao(
        Map<String, List<SessionFactory>> sessionFactories,
        Class<T> entityClass,
        Map<String, ShardingBundleOptions> shardingOptions,
        Map<String, ShardInfoProvider> shardInfoProviders,
        TransactionObserver observer)
```

```java
public MultiTenantRelationalDao(
        Map<String, List<SessionFactory>> sessionFactories,
        Class<T> entityClass,
        Map<String, ShardingBundleOptions> shardingOptions,
        Map<String, ShardInfoProvider> shardInfoProviders,
        TransactionObserver observer)
```

Delete registry fields, `Objects.requireNonNull(registry, ...)`, and
registry-driven constructor consistency validation.

Remove the registry parameter from cacheable DAO constructors and their
`super(...)` calls.

Change `WrapperDao` constructors to remove the registry parameter and field.

- [ ] **Step 2: Replace lookup routing with direct static access**

Delete `shardId` and `validateTenant` from `MultiTenantLookupDao`.

Replace each routed expression with:

```java
ShardCalculatorRegistry.get(tenantId).shardId(key)
```

Use the actual routing variable (`key` or `id`) at each site. In batch grouping:

```java
Collectors.groupingBy(
        key -> ShardCalculatorRegistry.get(tenantId).shardId(key),
        Collectors.toList())
```

Remove every standalone `validateTenant(tenantId)` call. Restore cacheable
lookup methods to their pre-feature cache-first flow without validation calls.

- [ ] **Step 3: Replace relational routing with direct static access**

Delete `shardId`, `validateTenant`, and `validateDaoTenant` from
`MultiTenantRelationalDao`.

At every operation that calculates a shard, use:

```java
ShardCalculatorRegistry.get(tenantId).shardId(parentKey)
```

Use `id` where that is the routing key. Remove standalone validation calls.
Restore cacheable relational methods to their pre-feature flow.

- [ ] **Step 4: Remove context ownership changes**

Delete `validateContextOwnership`.

Restore context-bound DAO selection to:

```java
RelationalDaoPriv dao = daos.get(tenantId).get(context.getShardId());
```

Use the existing context session factory exactly as the code did before this
feature. Remove tests and production checks for foreign contexts, invalid
context shard IDs, or session-factory identity.

- [ ] **Step 5: Route wrappers through the static registry**

Use constructors without a registry parameter:

```java
public WrapperDao(
        String dbNamespace,
        List<SessionFactory> sessionFactories,
        Class<DaoType> daoClass)
```

```java
public WrapperDao(
        String dbNamespace,
        List<SessionFactory> sessionFactories,
        Class<DaoType> daoClass,
        Class[] extraConstructorParamClasses,
        Class[] extraConstructorParamObjects)
```

Route:

```java
public DaoType forParent(String parentKey) {
    return daos.get(
            ShardCalculatorRegistry.get(dbNamespace).shardId(parentKey));
}
```

Remove constructor registry validation and added shard-range validation.

- [ ] **Step 6: Update bundle DAO factories**

Remove `this.shardCalculatorRegistry` from all lookup, relational, cacheable,
and wrapper constructor calls.

- [ ] **Step 7: Run production compilation**

Run:

```bash
mvn -q -o -DskipTests compile
```

Expected: PASS after all DAO families and bundle factories use the new
signatures.

- [ ] **Step 8: Commit Tasks 1 and 2 together**

Tasks 1 and 2 form one compile-safe migration. Commit:

```bash
git add src/main/java \
        src/test/java/io/appform/dropwizard/sharding/utils \
        src/test/java/io/appform/dropwizard/sharding/MultiTenantDBShardingBundleTestBase.java \
        src/test/java/io/appform/dropwizard/sharding/BundleBasedTestBase.java \
        src/test/java/io/appform/dropwizard/sharding/MultiTenantBundleBasedTestBase.java \
        src/test/java/io/appform/dropwizard/sharding/BundleMvccSnapshotTest.java
git commit -m "refactor: simplify shard calculator registry access" \
  -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

## Task 3: Restore focused DAO test fixtures

**Files:**
- Modify: affected tests under `src/test/java/io/appform/dropwizard/sharding/dao/`
- Modify: `src/test/java/io/appform/dropwizard/sharding/ScrollTest.java`

- [ ] **Step 1: Replace isolated registry objects with static setup**

For each direct DAO test, replace:

```java
registry = ShardCalculatorTestUtils.registryFor(shardManagers);
```

with:

```java
ShardCalculatorTestUtils.register(shardManagers);
```

Remove registry fields and constructor arguments.

- [ ] **Step 2: Add static cleanup**

In every direct DAO test class that registers calculators, add or extend:

```java
@AfterEach
void tearDown() {
    ShardCalculatorRegistry.clear();
    // retain existing SessionFactory cleanup
}
```

- [ ] **Step 3: Remove out-of-scope tests**

Delete tests added only for:

- registry injection or registry-instance isolation;
- registry-superset DAO validation;
- missing calculator validation in DAO constructors;
- missing sharding options/provider/cache constructor validation;
- wrapper calculator/session-factory range mismatch;
- relational context ownership, foreign contexts, and invalid context shards;
- preserving contexts after an instance registry is cleared.

Retain tests for per-tenant routing, missing static registry entries, overwrite
behavior, and the existing DAO functionality.

- [ ] **Step 4: Update routing assertions**

Replace fixture-registry calls:

```java
registry.get(tenantId).shardId(key)
```

with:

```java
ShardCalculatorRegistry.get(tenantId).shardId(key)
```

- [ ] **Step 5: Run focused DAO tests**

Run:

```bash
mvn -q -o \
  -Dtest=MultiTenantLookupDaoTest,MultiTenantCacheableLookupDaoTest,LookupDaoTest,CacheableLookupDaoTest,MultiTenantRelationalDaoTest,MultiTenantRelationalReadOnlyLockedContextTest,RelationalDaoTest,RelationalReadOnlyLockedContextTest,WrapperDaoTest,WrapperDaoTransactionReuseTest,ScrollTest,EncryptionAtRestTest,LockTest,ParentChildTest \
  test
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/test/java
git commit -m "test: align daos with static calculator registry" \
  -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

## Task 4: Final verification

**Files:**
- No additional files unless verification exposes a correction required by the
  approved design.

- [ ] **Step 1: Verify DAO constructor boundaries**

Run:

```bash
grep -R "ShardCalculatorRegistry registry\\|ShardManager shardManager\\|Map<String, ShardManager> shardManagers" \
  -n src/main/java/io/appform/dropwizard/sharding/dao || true
```

Expected: no constructor dependency matches.

- [ ] **Step 2: Verify removed helpers and context changes**

Run:

```bash
grep -R "validateTenant\\|validateContextOwnership\\|validateDaoTenant" \
  -n src/main/java src/test/java || true
```

Expected: no output.

- [ ] **Step 3: Verify static registry routing**

Run:

```bash
grep -R "ShardCalculatorRegistry.get(tenantId)\\|ShardCalculatorRegistry.get(dbNamespace)" \
  -n src/main/java/io/appform/dropwizard/sharding/dao
```

Expected: lookup, relational, cacheable inherited routing, and wrapper call
sites resolve the static registry.

- [ ] **Step 4: Verify registry has no snapshot copying**

Run:

```bash
grep -n "Map.copyOf\\|new HashMap\\|new LinkedHashMap\\|volatile Map" \
  src/main/java/io/appform/dropwizard/sharding/utils/ShardCalculatorRegistry.java || true
```

Expected: no output.

- [ ] **Step 5: Run the complete suite**

Run:

```bash
mvn -q -o test
```

Expected: BUILD SUCCESS.

- [ ] **Step 6: Run repository checks**

Run:

```bash
git diff --check origin/master...HEAD
git status --short
```

Expected: no whitespace errors and a clean worktree.

# Shard Calculator — Package-Private Constructor Injection Design

**Date:** 2026-09-08
**Status:** Approved

---

## Problem

Same underlying problem as the bundle-level promotion effort: `ShardCalculator<String>` is
instantiated independently inside each DAO class (`MultiTenantLookupDao`, `MultiTenantRelationalDao`,
`WrapperDao`), building an identical calculator per DAO instance from the same `ShardManager` data.
This is wasteful and prevents external code from reusing a calculator without constructing a DAO.

This design achieves the same end goal — a single shared `ShardCalculator` built once at the bundle
level and handed to DAOs — but via a different mechanism than a public getter + deprecated
constructors:

- No global/static registry of any kind.
- DAO constructors become **package-private**, so DAOs can only ever be constructed by the bundle
  classes that own their session factories and shard managers.
- The bundle classes that create DAOs move into the same package as the DAOs
  (`io.appform.dropwizard.sharding.dao`) so they can call those package-private constructors.
- This is an intentional breaking change: old public constructors are replaced, not deprecated.

---

## Goal

1. Compute one `ShardCalculator<String>` **per tenant** inside `MultiTenantDBShardingBundleBase.run()`,
   right after that tenant's `ShardManager` is built.
2. Inject the calculator into DAOs via constructor parameter instead of each DAO building its own.
3. Enforce "DAOs can only be built by the bundle" using Java package-privacy — no registry, no
   reflection, no static state.
4. Move `DBShardingBundleBase` and `MultiTenantDBShardingBundleBase` (the only classes that construct
   DAOs) into `io.appform.dropwizard.sharding.dao`.

---

## Design

### `ShardCalculator<T>` (package: `io.appform.dropwizard.sharding.utils`, unchanged)

Simplified to be tenant-scoped — one instance per tenant, holding that tenant's `ShardManager`
directly instead of a `Map<String, ShardManager>`:

```java
public class ShardCalculator<T> {
    private final String tenantId;
    private final ShardManager shardManager;
    private final BucketIdExtractor<T> extractor;

    public ShardCalculator(String tenantId, ShardManager shardManager, BucketIdExtractor<T> extractor) {
        this.tenantId = Objects.requireNonNull(tenantId, "tenantId");
        this.shardManager = Objects.requireNonNull(shardManager, "shardManager");
        this.extractor = Objects.requireNonNull(extractor, "extractor");
    }

    public int shardId(T key) {
        return shardManager.shardForBucket(extractor.bucketId(tenantId, key));
    }

    public boolean isOnValidShard(T key) {
        return shardManager.isMappedToValidShard(extractor.bucketId(tenantId, key));
    }
}
```

The tenant-scoped overloads (`shardId(String tenantId, T key)`) that exist on `master` today are
removed — since each instance is already bound to one tenant, they're redundant. The constructor
stays `public`: `ShardCalculator` lives in the `utils` package, and the bundle (in the `dao` package)
needs to construct it. Only the *DAO* constructors need package-privacy.

`BucketIdExtractor<T>` and `ConsistentHashBucketIdExtractor<T>` are unchanged — the bundle builds one
`ConsistentHashBucketIdExtractor` per tenant using a single-entry `Map.of(tenantId, shardManager)`,
mirroring how `WrapperDao` already builds its own extractor today.

### `MultiTenantDBShardingBundleBase` (moves to `io.appform.dropwizard.sharding.dao`)

**Add field:**
```java
private final Map<String, ShardCalculator<String>> shardCalculators = Maps.newHashMap();
```

**In `run()`, immediately after `this.shardManagers.put(tenantId, shardManager)`:**
```java
this.shardCalculators.put(tenantId, new ShardCalculator<>(
        tenantId, shardManager,
        new ConsistentHashBucketIdExtractor<>(Map.of(tenantId, shardManager))));
```

**`createParentObjectDao` / `createRelatedObjectDao`:** pass `this.shardCalculators` (the whole map —
these DAOs serve every tenant) instead of `this.shardManagers`.

**`createWrapperDao(tenantId, ...)`:** pass `this.shardCalculators.get(tenantId)` (a single instance —
`WrapperDao` only ever serves one tenant) instead of building its own from `shardManagers.get(tenantId)`.

**New accessor:**
```java
public ShardCalculator<String> getShardCalculator(String tenantId) {
    Preconditions.checkArgument(shardCalculators.containsKey(tenantId), "Unknown tenant: " + tenantId);
    return shardCalculators.get(tenantId);
}
```
This replaces any registry-style lookup — it's a plain method on the object that owns the data.

### `DBShardingBundleBase` (moves to `io.appform.dropwizard.sharding.dao`)

```java
public ShardCalculator<String> getShardCalculator() {
    return delegate.getShardCalculator(dbNamespace);
}
```

### DAO constructors — package-private, constructor-injected calculator

| DAO | Old param | New param | Visibility |
|---|---|---|---|
| `MultiTenantLookupDao` | `Map<String, ShardManager> shardManagers` | `Map<String, ShardCalculator<String>> shardCalculators` | package-private |
| `MultiTenantRelationalDao` | `Map<String, ShardManager> shardManagers` | `Map<String, ShardCalculator<String>> shardCalculators` | package-private |
| `MultiTenantCacheableLookupDao` | (forwards to super) | (forwards to super) | package-private |
| `MultiTenantCacheableRelationalDao` | (forwards to super) | (forwards to super) | package-private |
| `WrapperDao` | `ShardManager shardManager` | `ShardCalculator<String> shardCalculator` | package-private |
| `LookupDao` | — (wraps delegate) | — (wraps delegate) | package-private |
| `RelationalDao` | — (wraps delegate) | — (wraps delegate) | package-private |
| `CacheableLookupDao` | — (wraps delegate) | — (wraps delegate) | package-private |
| `CacheableRelationalDao` | — (wraps delegate) | — (wraps delegate) | package-private |

Internals of `MultiTenantLookupDao`/`MultiTenantRelationalDao` that previously did
`new ShardCalculator<>(shardManagers, new ConsistentHashBucketIdExtractor<>(shardManagers))` are
deleted; the field is assigned directly from the constructor parameter for the relevant tenant when
needed (multi-tenant DAOs keep the full `Map<String, ShardCalculator<String>>` since they serve every
tenant per-call via a `tenantId` argument on each method).

### `ShardedDao<T>` interface

Unchanged — `ShardCalculator<String> getShardCalculator()` still returns the single-tenant DAO's own
calculator (for `LookupDao`/`RelationalDao`); for multi-tenant DAOs a per-tenant getter
(`getShardCalculator(tenantId)`) is added since one instance now serves many tenants.

### Package moves

| File | From | To |
|---|---|---|
| `DBShardingBundleBase.java` | `io.appform.dropwizard.sharding` | `io.appform.dropwizard.sharding.dao` |
| `MultiTenantDBShardingBundleBase.java` | `io.appform.dropwizard.sharding` | `io.appform.dropwizard.sharding.dao` |

`DBShardingBundle`, `BalancedDBShardingBundle`, `MultiTenantDBShardingBundle`,
`MultiTenantBalancedDBShardingBundle`, `BundleCommonBase`, `ShardInfoProvider` stay in
`io.appform.dropwizard.sharding` — they don't construct DAOs directly, they just need updated
imports for the two relocated base classes.

---

## Data Flow

```
MultiTenantDBShardingBundleBase.run()   [package: io.appform.dropwizard.sharding.dao]
  for each tenant:
    shardManager = createShardManager(...)
    shardManagers.put(tenantId, shardManager)
    shardCalculators.put(tenantId, new ShardCalculator<>(tenantId, shardManager, extractor))

createParentObjectDao(clazz):
  new MultiTenantLookupDao<>(sessionFactories, clazz, shardCalculators, shardingOptions,
                              shardInfoProviders, observer)
  // package-private constructor — only callable because the bundle now lives in the dao package

createWrapperDao(tenantId, daoClass):
  new WrapperDao<>(tenantId, sessionFactories.get(tenantId), daoClass,
                    shardCalculators.get(tenantId))

Application code at runtime:
  bundle.getShardCalculator().shardId("some-key")               // single-tenant bundle
  multiTenantBundle.getShardCalculator("tenantA").shardId("key") // multi-tenant bundle
```

---

## Backward Compatibility (Breaking Change)

| Change | Impact |
|---|---|
| DAO constructors now package-private | Any code constructing DAOs directly (outside the bundle) fails to compile |
| `DBShardingBundleBase`/`MultiTenantDBShardingBundleBase` package changed | Any code importing the old package path fails to compile |
| `ShardCalculator` constructor/API simplified (no `tenantId` param on `shardId`/`isOnValidShard`) | Any code calling `shardCalculator.shardId(tenantId, key)` fails to compile |
| `ShardManager`-based DAO constructor params replaced with `ShardCalculator` | Same as above |

This is a deliberate breaking change (per project direction) and should ship with a major/minor
version bump and a changelog entry calling out the new construction path
(`bundle.createParentObjectDao(...)` etc. — unchanged public API surface for typical consumers who
already only use the bundle factory methods).

---

## Testing

- Existing DAO tests already live in `io.appform.dropwizard.sharding.dao.*`, so they retain
  constructor access after visibility changes; test-base classes referencing the moved bundle classes
  need their imports (and, where they live outside `dao`, their own package) updated to match.
- New test: assert each tenant in a multi-tenant bundle gets a distinct `ShardCalculator` instance,
  and that `shardId(key)` for the same key can differ across tenants with different shard counts.
- New test: assert `DBShardingBundleBase.getShardCalculator()` returns the same instance as the one
  injected into DAOs created via `createParentObjectDao`/`createRelatedObjectDao`.
- Compile-level check (no test needed, verified by build): confirm no remaining caller outside the
  `dao` package attempts direct DAO construction.

---

## Implementation Steps (high level)

1. Create branch `feature/shard-calculator-package-private-di` off `master`.
2. Simplify `ShardCalculator<T>` to the per-tenant shape (drop `Map`-based constructor and
   tenant-parameterized methods).
3. Move `DBShardingBundleBase.java` and `MultiTenantDBShardingBundleBase.java` into
   `io.appform.dropwizard.sharding.dao`; fix imports in dependent classes.
4. Add `shardCalculators` map + per-tenant construction in `MultiTenantDBShardingBundleBase.run()`.
5. Update `MultiTenantLookupDao`, `MultiTenantRelationalDao`, `MultiTenantCacheableLookupDao`,
   `MultiTenantCacheableRelationalDao`, `WrapperDao`, `LookupDao`, `RelationalDao`,
   `CacheableLookupDao`, `CacheableRelationalDao`: swap constructor params, drop `public` modifier.
6. Update `createParentObjectDao`/`createRelatedObjectDao`/`createWrapperDao` factory methods to pass
   calculators instead of shard managers.
7. Add `getShardCalculator(tenantId)` on `MultiTenantDBShardingBundleBase` and
   `getShardCalculator()` on `DBShardingBundleBase`.
8. Fix all call sites/tests broken by the package move and constructor signature changes.
9. Add the new tests described above.
10. Run full test suite to confirm no regressions.

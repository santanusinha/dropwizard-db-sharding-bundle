# Per-Tenant ShardCalculator Registry Design

**Date:** 2026-09-02
**Status:** Approved

## Problem

Multi-tenant DAOs currently construct a `ShardCalculator` from the complete
`Map<String, ShardManager>`. This duplicates calculators across DAO instances,
forces DAO constructors to depend on shard managers, and lets each calculator
route for every tenant.

Shard calculators belong to the bundle lifecycle. Each calculator should route
for exactly one tenant and use only that tenant's `ShardManager`.

## Goals

1. Create one tenant-bound `ShardCalculator<String>` per configured tenant.
2. Register calculators by tenant after bundle initialization succeeds.
3. Remove shard-manager and calculator dependencies from DAO constructors.
4. Resolve the current tenant's calculator from the registry at every routing
   operation.
5. Expose calculators from bundle APIs, not DAO APIs.
6. Fail clearly for missing or duplicate tenant registrations.

## Non-Goals

- Preserve source compatibility with the existing map-based
  `ShardCalculator` API.
- Support two bundle instances that assign different shard configurations to
  the same tenant ID in one JVM.
- Add production unregister, replacement, or dynamic tenant-reconfiguration
  APIs.

Tenant IDs are JVM-global for calculator registration.

## Architecture

### Tenant-bound `ShardCalculator`

`ShardCalculator<T>` owns:

- one `tenantId`;
- one `ShardManager`;
- one `BucketIdExtractor<T>`.

Its constructor becomes:

```java
public ShardCalculator(
        String tenantId,
        ShardManager shardManager,
        BucketIdExtractor<T> extractor)
```

Its routing API becomes:

```java
public int shardId(T key)
public boolean isOnValidShard(T key)
```

Both methods pass the bound tenant ID to the bucket extractor and use the bound
shard manager. The map-based constructor and methods that accept a tenant ID
are removed.

### `ShardCalculatorRegistry`

`ShardCalculatorRegistry` is a static, process-wide registry backed by a
volatile, immutable copy-on-write snapshot:

```java
private static volatile Map<String, ShardCalculator<String>> calculators =
        Map.of();
```

It exposes:

```java
public static void register(
        Map<String, ShardCalculator<String>> calculators)
public static ShardCalculator<String> get(String tenantId)
@VisibleForTesting
public static void clear()
```

Registration is synchronized and all-or-nothing:

1. Validate the input map, tenant IDs, and calculators.
2. Capture the current immutable snapshot.
3. Check every tenant ID for an existing registration. If any tenant exists,
   throw `IllegalStateException` without publishing any entries.
4. Build an immutable merged snapshot without changing the published state.
5. Publish the complete batch with one volatile assignment.

This allows separate bundles with disjoint tenant IDs while preventing two
configurations from claiming the same JVM-global tenant.

`get(tenantId)` captures the volatile snapshot once and performs its lookup
against that snapshot. `clear()` publishes `Map.of()` with one volatile
assignment.

`get(tenantId)` throws:

```text
ShardCalculator has not been registered for tenant: <tenantId>
```

The registry does not overwrite registrations. `clear()` exists only to isolate
tests.

### Bundle lifecycle

`MultiTenantDBShardingBundleBase.run()` continues to initialize each tenant's
session factories, shard manager, observers, and administrative tasks. It also
builds a tenant-bound calculator for each tenant, but it keeps those calculators
private until every tenant finishes initialization.

After the tenant loop succeeds, the bundle registers the complete calculator
map in one operation. A failed tenant initialization therefore publishes no
calculators from that bundle.

The bundle exposes:

```java
public ShardCalculator<String> getShardCalculator(String tenantId)
```

The method delegates to the registry so callers receive the same missing-tenant
error as DAO operations.

`DBShardingBundleBase` exposes:

```java
public ShardCalculator<String> getShardCalculator()
```

It delegates to the multi-tenant bundle with `DEFAULT_NAMESPACE`.

## DAO Routing

`MultiTenantLookupDao` and `MultiTenantRelationalDao` do not retain calculator
fields. Every operation that needs a shard performs this lookup before
selecting a tenant-specific DAO:

```java
int shardId = ShardCalculatorRegistry.get(tenantId).shardId(key);
```

This lookup order ensures an unknown tenant fails with the registry's
`IllegalStateException`, not a null dereference from another tenant-indexed
map.

`MultiTenantCacheableLookupDao` and `MultiTenantCacheableRelationalDao` inherit
their parent DAO's routing behavior.

`WrapperDao` retains its fixed `dbNamespace`. Each `forParent(parentKey)` call
resolves `ShardCalculatorRegistry.get(dbNamespace)` before choosing the shard
DAO.

DAO constructors no longer accept any of these values:

- `ShardManager`;
- `Map<String, ShardManager>`;
- `ShardCalculator`;
- calculator maps or resolver objects.

Bundle DAO factories and direct test construction must use the reduced
constructor signatures.

## Public API Changes

Calculator access moves from DAOs to bundles:

- Remove calculator fields and `getShardCalculator()` methods from
  `MultiTenantLookupDao`, `MultiTenantRelationalDao`, and `WrapperDao`.
- Remove delegating calculator accessors from `LookupDao` and `RelationalDao`.
- Remove `ShardedDao`, whose only contract is the DAO-level calculator
  accessor.
- Add tenant-aware access to `MultiTenantDBShardingBundleBase`.
- Add default-namespace access to `DBShardingBundleBase`.

These are intentional breaking changes. No deprecated compatibility layer will
remain.

## Error Handling

- Missing tenant: `ShardCalculatorRegistry.get(tenantId)` throws
  `IllegalStateException` with the tenant ID.
- Duplicate tenant: batch registration throws `IllegalStateException` with the
  conflicting tenant ID and publishes none of the batch.
- Invalid registration input: reject null maps, tenant IDs, and calculators
  before changing registry state.
- Bundle initialization failure: register nothing from that bundle.

## Testing

### `ShardCalculatorTest`

- A calculator routes through its bound tenant and shard manager.
- Two calculators with different shard managers route independently.
- `isOnValidShard` uses the bound tenant.

### `ShardCalculatorRegistryTest`

- Register and retrieve multiple tenant calculators.
- Reject an unknown tenant with the exact error.
- Reject duplicate tenants without partially publishing a batch.
- Clear all entries between tests.
- Support concurrent reads after successful registration.
- Pause an insertion-ordered map during the actual batch-copy traversal and
  assert that a reader cannot observe the first new tenant while the last
  tenant remains missing. This regression test fails for entry-by-entry
  publication into a live concurrent map and passes for one-assignment snapshot
  publication.

### Bundle tests

- A two-tenant bundle registers distinct calculators.
- Each calculator uses only its tenant's shard manager.
- Bundle-level accessors return the registered calculators.
- Single-tenant bundle access uses `DEFAULT_NAMESPACE`.
- A failed bundle initialization does not publish its calculator batch.

### DAO tests

- Lookup, relational, cacheable, and wrapper routing use the correct tenant
  calculator.
- Operations resolve the registry each time rather than retaining a calculator.
- Unknown tenants fail with the registry error.
- Constructor tests confirm shard-manager and calculator parameters are gone.
- Test teardown clears the static registry.

### Final verification

- Run focused tests while implementing each change.
- Run the complete Maven test suite.
- Run `git diff --check`.
- Search production constructors for forbidden shard-manager or calculator
  dependencies.
- Search all routing call sites for tenant-aware registry lookup.

## Expected File Impact

| Action | Area |
| --- | --- |
| Create | `utils/ShardCalculatorRegistry.java` and its tests |
| Modify | `utils/ShardCalculator.java` and its tests |
| Modify | `MultiTenantDBShardingBundleBase` and `DBShardingBundleBase` |
| Modify | Multi-tenant lookup, relational, cacheable, and wrapper DAOs |
| Modify | Single-tenant DAO wrappers and affected tests |
| Delete | `dao/ShardedDao.java` |

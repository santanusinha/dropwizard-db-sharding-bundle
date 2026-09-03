# Per-Tenant ShardCalculator Registry Design

**Date:** 2026-09-02
**Status:** Approved

## Problem

Multi-tenant DAOs currently construct a `ShardCalculator` from the complete
`Map<String, ShardManager>`. This duplicates calculators across DAO instances,
forces DAO constructors to depend on shard managers, and lets each calculator
route for every tenant.

Shard calculators belong to a bundle's lifecycle. Each calculator should route
for one tenant, use only that tenant's `ShardManager`, and remain isolated from
other bundle instances.

## Goals

1. Create one tenant-bound `ShardCalculator<String>` per configured tenant.
2. Give each `MultiTenantDBShardingBundleBase` its own calculator registry.
3. Register a bundle's calculators only after every tenant initializes.
4. Remove shard-manager and calculator dependencies from DAO constructors.
5. Resolve the current tenant's calculator from the owning bundle's registry at
   every routing operation.
6. Expose calculators from bundle APIs, not DAO APIs.
7. Reject missing tenants and duplicate registrations within one bundle.
8. Let separate bundles use the same tenant ID or default namespace
   independently.

## Non-Goals

- Preserve source compatibility with the existing map-based
  `ShardCalculator` API.
- Add production unregister, replacement, or dynamic tenant-reconfiguration
  APIs.
- Share calculator registrations between bundle instances.

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

`ShardCalculatorRegistry` is a normal final class. Every
`MultiTenantDBShardingBundleBase` owns one final instance. Each instance stores
its calculators in a volatile immutable snapshot:

```java
private volatile Map<String, ShardCalculator<String>> calculators = Map.of();
```

It exposes instance methods:

```java
public synchronized void register(
        Map<String, ShardCalculator<String>> calculators)
public ShardCalculator<String> get(String tenantId)
@VisibleForTesting
public synchronized void clear()
```

Registration is synchronized and all-or-nothing:

1. Validate the input map, tenant IDs, and calculators.
2. Capture the current immutable snapshot.
3. Check every tenant ID for an existing registration in this registry. If any
   tenant exists, throw `IllegalStateException` without publishing the batch.
4. Build an immutable merged snapshot without changing the published state.
5. Publish the complete batch with one volatile assignment.

`get(tenantId)` captures the volatile snapshot once and performs its lookup
against that snapshot. It throws:

```text
ShardCalculator has not been registered for tenant: <tenantId>
```

The registry never overwrites registrations. A test may clear its own isolated
registry when it must prove that a DAO resolves the calculator again. No shared
test cleanup is required.

Duplicate detection is local to one registry. Two live bundles may register
the same tenant ID, including the default namespace, without conflict.

### Bundle lifecycle

`MultiTenantDBShardingBundleBase` owns:

```java
private final ShardCalculatorRegistry shardCalculatorRegistry =
        new ShardCalculatorRegistry();
```

`run()` continues to initialize each tenant's session factories, shard manager,
observers, and administrative tasks. It also builds a tenant-bound calculator
for each tenant in a local map. The bundle publishes that map to its registry
only after the tenant loop completes successfully. A failed tenant
initialization therefore leaves that bundle's registry unchanged.

The bundle exposes:

```java
public ShardCalculator<String> getShardCalculator(String tenantId)
```

The method delegates to the bundle's registry. DAO factories pass the same
registry instance to every DAO they create.

`DBShardingBundleBase` exposes:

```java
public ShardCalculator<String> getShardCalculator()
```

It delegates to its multi-tenant bundle with `DEFAULT_NAMESPACE`. Its DAO
factories also delegate, so single-tenant wrappers receive the delegate
bundle's registry.

### DAO routing

`MultiTenantLookupDao`, `MultiTenantCacheableLookupDao`,
`MultiTenantRelationalDao`, and `MultiTenantCacheableRelationalDao` receive one
`ShardCalculatorRegistry` in their constructors. They do not receive or retain
a `ShardManager`, calculator, calculator map, resolver, or scope token.

Every operation that needs a shard queries the passed registry before selecting
a tenant-specific DAO:

```java
int shardId = registry.get(tenantId).shardId(key);
```

DAOs retain the registry but never cache a returned calculator. This lookup
order makes an unknown tenant fail with the registry's `IllegalStateException`,
not a null dereference from another tenant-indexed map.

Cacheable DAOs validate the tenant through the registry before a cache lookup
that might otherwise dereference a missing tenant entry.

`WrapperDao` receives its fixed `dbNamespace` and the registry. Each
`forParent(parentKey)` call resolves:

```java
int shardId = registry.get(dbNamespace).shardId(parentKey);
```

### Public API changes

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

## Error handling

- Missing tenant: `registry.get(tenantId)` throws `IllegalStateException` with
  the tenant ID.
- Duplicate tenant in one registry: batch registration throws
  `IllegalStateException` with the conflicting tenant ID and publishes none of
  the batch.
- Invalid registration input: reject null maps, tenant IDs, and calculators
  before changing registry state.
- Bundle initialization failure: publish no calculators from that bundle.
- Separate bundle instances: registrations never conflict, even when tenant
  IDs match.

## Testing

### `ShardCalculatorTest`

- A calculator routes through its bound tenant and shard manager.
- Two calculators with different shard managers route independently.
- `isOnValidShard` uses the bound tenant.

### `ShardCalculatorRegistryTest`

- Register and retrieve multiple tenant calculators.
- Reject an unknown tenant with the exact error.
- Reject duplicate tenants without partially publishing a batch.
- Prove two registry instances can register the same tenant independently.
- Support concurrent reads after successful registration.
- Pause an insertion-ordered map during batch-copy traversal and assert that a
  reader cannot observe the first new tenant while the last tenant remains
  missing. The test passes only when registration publishes one completed
  immutable snapshot.

### Bundle tests

- A two-tenant bundle registers distinct calculators.
- Each calculator uses only its tenant's shard manager.
- Bundle-level accessors return the registered calculators.
- Single-tenant bundle access uses `DEFAULT_NAMESPACE`.
- A failed bundle initialization publishes no calculator batch.
- Two live bundles expose different calculators for the same namespace without
  collision.
- `BundleMvccSnapshotTest` continues to pass with two live default-namespace
  bundles.

### DAO tests

- Lookup, relational, cacheable, and wrapper routing use the correct tenant
  calculator.
- Operations query the passed registry each time.
- Unknown tenants fail with the registry error.
- Constructors accept the registry but no shard manager, calculator,
  calculator map, or scope token.
- Direct DAO tests create an isolated registry with
  `ShardCalculatorTestUtils.registryFor(...)`.

### Final verification

- Run focused tests while implementing each change.
- Run the complete Maven test suite.
- Run `git diff --check`.
- Confirm registry methods and state belong to instances.
- Confirm DAO constructors depend on `ShardCalculatorRegistry` but not shard
  managers or calculators.
- Confirm no shared registry cleanup remains.

## Expected file impact

| Action | Area |
| --- | --- |
| Create | `utils/ShardCalculatorRegistry.java` and its tests |
| Modify | `utils/ShardCalculator.java` and its tests |
| Modify | `MultiTenantDBShardingBundleBase` and `DBShardingBundleBase` |
| Modify | Multi-tenant lookup, relational, cacheable, and wrapper DAOs |
| Modify | Single-tenant DAO wrappers and affected tests |
| Delete | `dao/ShardedDao.java` |

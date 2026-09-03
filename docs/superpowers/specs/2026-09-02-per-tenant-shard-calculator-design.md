# Per-Tenant ShardCalculator Registry Design

**Date:** 2026-09-02
**Status:** Approved

## Problem

Multi-tenant DAOs currently construct `ShardCalculator` instances from shard
managers. This duplicates calculators and forces DAO constructors to receive
shard-manager dependencies used only for routing.

Shard calculators should be created by the bundle, one per tenant, and
retrieved by DAOs without adding another constructor dependency.

## Goals

1. Create one tenant-bound `ShardCalculator<String>` per configured tenant.
2. Register calculators in a static tenant-keyed registry.
3. Remove shard managers and calculators from DAO constructors.
4. Let each routing operation retrieve its tenant's calculator directly.
5. Expose calculator access from bundles rather than DAOs.

## Non-Goals

- Preserve the existing map-based `ShardCalculator` API.
- Inject `ShardCalculatorRegistry`, calculators, resolver functions, or
  calculator maps into DAO constructors.
- Validate DAO tenant-map consistency as part of this change.
- Change relational context ownership or validation.
- Provide atomic multi-tenant batch publication.

## Tenant-Bound `ShardCalculator`

`ShardCalculator<T>` owns one tenant ID, one `ShardManager`, and one
`BucketIdExtractor<T>`:

```java
public ShardCalculator(
        String tenantId,
        ShardManager shardManager,
        BucketIdExtractor<T> extractor)
```

Its public routing API is:

```java
public int shardId(T key)
public boolean isOnValidShard(T key)
```

Both methods pass the bound tenant ID to the extractor and use the bound shard
manager. The map constructor, default-namespace fallback, and tenant-parameter
overloads are removed.

## Static `ShardCalculatorRegistry`

`ShardCalculatorRegistry` is a static utility backed by:

```java
private static final ConcurrentMap<String, ShardCalculator<String>>
        CALCULATORS = new ConcurrentHashMap<>();
```

It exposes:

```java
public static void register(
        String tenantId,
        ShardCalculator<String> calculator)
public static ShardCalculator<String> get(String tenantId)
@VisibleForTesting
public static void clear()
```

`register` validates its arguments and stores the calculator with `put`.
Registering the same tenant again replaces the previous calculator. This
supports tests and applications that initialize more than one bundle with the
same namespace.

`get` throws:

```text
ShardCalculator has not been registered for tenant: <tenantId>
```

The registry stores calculator references directly. It does not copy the
registry or create calculator snapshots during reads or writes.

## Bundle Lifecycle

`MultiTenantDBShardingBundleBase.run()` creates a tenant-bound calculator after
creating each tenant's `ShardManager`:

```java
ShardCalculatorRegistry.register(
        tenantId,
        new ShardCalculator<>(
                tenantId,
                shardManager,
                new ConsistentHashBucketIdExtractor<>(
                        Map.of(tenantId, shardManager))));
```

Each calculator contains only its tenant's manager. The bundle does not retain
a calculator map or registry instance.

The multi-tenant bundle exposes:

```java
public ShardCalculator<String> getShardCalculator(String tenantId)
```

The single-tenant bundle exposes:

```java
public ShardCalculator<String> getShardCalculator()
```

Both methods delegate to the static registry.

## DAO Routing

DAO constructors do not accept a `ShardManager`, shard-manager map,
`ShardCalculator`, `ShardCalculatorRegistry`, calculator map, or resolver.

Every operation that calculates a shard calls the registry directly:

```java
int shardId = ShardCalculatorRegistry.get(tenantId).shardId(key);
```

This applies to:

- `MultiTenantLookupDao`;
- `MultiTenantRelationalDao`;
- inherited cacheable DAO routing;
- `WrapperDao`, using its fixed `dbNamespace`.

Cacheable DAO behavior otherwise remains unchanged. This feature does not add
tenant-map validation before cache access.

Relational operations that already receive a context with a shard ID and
session factory remain unchanged. This feature does not add context ownership
validation.

## Public API Changes

- Remove shard-manager constructor parameters from multi-tenant and wrapper
  DAOs.
- Do not add registry constructor parameters.
- Remove DAO calculator fields and calculator accessors.
- Remove `ShardedDao`, whose only contract is DAO-level calculator access.
- Add calculator accessors to bundle classes.

These are intentional breaking changes. No compatibility layer remains.

## Error Handling

- Null registry inputs are rejected with `Objects.requireNonNull`.
- Missing tenants fail through `ShardCalculatorRegistry.get(tenantId)`.
- Duplicate registration replaces the previous calculator.
- Existing DAO and context errors remain outside this feature's scope.

## Testing

### `ShardCalculatorTest`

- Verifies the calculator passes its bound tenant to the extractor.
- Verifies calculators with different managers route independently.
- Verifies only the tenant-bound public API remains.

### `ShardCalculatorRegistryTest`

- Registers and retrieves separate tenant calculators.
- Verifies same-tenant registration overwrites the previous calculator.
- Verifies the exact missing-tenant error.
- Verifies `clear()` removes all entries.
- Verifies concurrent reads of registered calculators.

### Bundle tests

- Verify each configured tenant is registered.
- Verify bundle-level accessors return registry calculators.
- Verify two bundles using the default namespace can initialize because later
  registration overwrites the shared entry.

### DAO tests

- Register tenant calculators in setup and clear the static registry in
  teardown.
- Verify lookup, relational, cacheable, and wrapper routing uses the correct
  tenant calculator.
- Verify DAO constructors no longer accept shard managers or registries.
- Keep existing relational context behavior unchanged.

## Expected File Impact

| Action | Area |
| --- | --- |
| Create | `utils/ShardCalculatorRegistry.java` and its tests |
| Modify | `utils/ShardCalculator.java` and its tests |
| Modify | `MultiTenantDBShardingBundleBase` and `DBShardingBundleBase` |
| Modify | Multi-tenant lookup, relational, cacheable, and wrapper DAOs |
| Modify | Affected DAO and bundle tests |
| Delete | `dao/ShardedDao.java` |

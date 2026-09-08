# Package-Private Per-Tenant Shard Calculator Design

## Goal

Implement tenant-bound shard calculators without process-global state. DAO construction is
restricted to the bundle/DAO package so calculators can be passed privately rather than resolved
through a static registry.

This is an intentionally breaking alternative to the registry-based implementation.

## Package Layout

Move the complete public bundle hierarchy into `io.appform.dropwizard.sharding.dao`:

- `DBShardingBundleBase`
- `DBShardingBundle`
- `BalancedDBShardingBundle`
- `MultiTenantDBShardingBundleBase`
- `MultiTenantDBShardingBundle`
- `MultiTenantBalancedDBShardingBundle`

The old fully qualified bundle class names will not be retained as compatibility facades. Users
must update imports to the new package.

Placing bundles and DAOs in the same package allows DAO constructors to be package-private without
introducing a public factory or internal bridge API.

## Calculator Ownership

`MultiTenantDBShardingBundleBase` owns the calculators for its bundle instance.

During `run()`:

1. Create the tenant's `ShardManager`.
2. Create a tenant-bound `ShardCalculator<String>`.
3. Add it to a local calculator map.
4. Complete initialization for every tenant.
5. Publish the calculators once using `Map.copyOf(...)`.

Publishing only after successful initialization prevents partially initialized calculator maps
from becoming available through DAO factory methods.

Each bundle instance owns its own immutable map. Two bundle instances may therefore use the same
tenant or database namespace with different shard topologies without affecting one another.

## Shard Calculator API

`ShardCalculator<T>` is bound to one tenant:

```java
ShardCalculator(
    String tenantId,
    ShardManager shardManager,
    BucketIdExtractor<T> bucketIdExtractor)
```

Its routing methods do not accept a tenant ID:

```java
int shardId(T key)
boolean isOnValidShard(T key)
```

The old map-based constructor and tenant-parameter routing methods are removed.

## DAO Construction

The following constructors become package-private:

- `MultiTenantLookupDao`
- `MultiTenantCacheableLookupDao`
- `MultiTenantRelationalDao`
- `MultiTenantCacheableRelationalDao`
- `LookupDao`
- `CacheableLookupDao`
- `RelationalDao`
- `CacheableRelationalDao`
- `WrapperDao`

Clients create DAOs only through the public bundle factory methods.

Multi-tenant DAO constructors receive the bundle's immutable
`Map<String, ShardCalculator<String>>`. Every DAO created by one bundle retains the same map
reference; constructors do not create snapshots or copies.

`WrapperDao` receives the single tenant calculator for its database namespace.

The single-tenant wrapper DAOs do not need calculators for routing because they delegate to their
multi-tenant DAO. Their constructors are package-private only to enforce bundle-owned creation.

## Routing

Tenant-aware DAO methods preserve the existing inline tenant guard:

```java
Preconditions.checkArgument(
    daos.containsKey(tenantId),
    "Unknown tenant: " + tenantId);
```

Routing then uses the bundle-owned calculator:

```java
int shardId = shardCalculators.get(tenantId).shardId(key);
```

The guard remains inline. No `validateTenant`, `validateDaoTenant`, or context-ownership helper is
introduced.

Relational context operations continue to use the tenant and shard already captured in the
context:

```java
daos.get(context.getTenantId()).get(context.getShardId())
```

They do not recalculate a shard or validate context ownership as part of this change.

## Failure Behavior

- Unknown DAO tenants fail with `IllegalArgumentException: Unknown tenant: <tenantId>`.
- Calculator maps are published only after successful bundle initialization.
- DAO construction before successful initialization is rejected by the bundle factory rather than
  exposing an empty or partial calculator map.
- There is no duplicate-registration behavior because calculators are not global.
- There is no registry lifecycle, synchronization, or cleanup requirement.

## Removed Components

Remove:

- `ShardCalculatorRegistry`
- registry registration and lookup calls
- registry test utilities
- registry resource locks and teardown cleanup
- registry-specific tests
- `ShardedDao` and DAO calculator accessors
- legacy map-based `ShardCalculator` APIs

## Compatibility

This design intentionally breaks:

- imports of bundle classes from `io.appform.dropwizard.sharding`
- direct public construction of DAO classes
- legacy `ShardCalculator` constructors and tenant-parameter methods
- `ShardedDao` usage and DAO calculator accessors

Configuration formats and bundle factory method behavior remain unchanged apart from the new bundle
class package.

## Testing

Use test-driven development for each behavior:

1. Tenant-bound `ShardCalculator` routing and shard validation.
2. Different tenants with different shard counts in one bundle.
3. Two bundle instances using the same namespace with different shard counts remain isolated.
4. Failed bundle initialization does not publish a partial calculator map.
5. DAO constructors are not public.
6. Bundle factory methods create and wire all DAO variants.
7. Unknown tenants preserve the existing error.
8. Relational contexts preserve their captured shard.
9. `ShardCalculatorRegistry` and its test synchronization infrastructure are absent.
10. Updated examples and tests compile with the new bundle package.

Run focused tests throughout implementation, then the complete Maven test suite.

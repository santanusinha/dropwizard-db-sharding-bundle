# Bundle-level ShardCalculator and DAO instantiation lockdown

Date: 2026-09-10
Status: Approved
Target version: 2.1.12-10

## Goals

1. Promote `ShardCalculator` to a bundle-owned, tenant-scoped component instead of an object each DAO builds for itself.
2. Prevent clients from instantiating the `*Dao` classes directly.

## Non-goals

- The JPMS interface/implementation split (`module-info.java`, unexported `...dao.internal` package). Deferred to a separate spec.
- Any change to DAO query, transaction, caching, or observer behaviour. This refactor is behaviour-preserving.
- Refactoring the parallel tenant-keyed maps inside the DAOs into a per-tenant composed context.

## Current state

`ShardCalculator<T>` holds a `Map<String, ShardManager>` and a `BucketIdExtractor<T>`, and exposes `shardId(tenantId, key)` alongside no-arg overloads that default to `DBShardingBundleBase.DEFAULT_NAMESPACE`.

Each DAO builds its own copy:

- `MultiTenantLookupDao:148`
- `MultiTenantRelationalDao:307`
- `WrapperDao:84`

The bundle builds a third copy of the extractor inside `BucketResolver` at `BundleCommonBase:131`. An application with twenty DAOs therefore constructs twenty identical calculators and twenty identical extractors.

All DAO constructors are public, so clients can build DAOs directly and bypass the bundle. `ShardedDao<T>` exists solely to expose `getShardCalculator()`; no library code consumes it.

## Design

### 1. `ShardCalculator` becomes single-tenant

`ShardCalculator<T>` stays public in `io.appform.dropwizard.sharding.utils` but holds one `ShardManager` and one `BucketIdExtractor<T>`:

```java
public class ShardCalculator<T> {
    private final ShardManager shardManager;
    private final BucketIdExtractor<T> extractor;

    public ShardCalculator(ShardManager shardManager, BucketIdExtractor<T> extractor) { ... }

    public int shardId(T key) { ... }

    public boolean isOnValidShard(T key) { ... }
}
```

Dropping the tenant parameter also removes the `utils -> DBShardingBundleBase` dependency that exists today only to reach `DEFAULT_NAMESPACE`.

`BucketIdExtractor<T>` changes to `int bucketId(T id)`, and `ConsistentHashBucketIdExtractor<T>` holds a single `ShardManager`.

Two other components consume the extractor and must follow:

- `BucketResolver<T>` keeps its tenant-aware API. Its constructor takes `Map<String, BucketIdExtractor<T>>` so `BundleCommonBase.getBucketInfo(tenantId, shardingKey, clazz)` is unchanged for callers. `registerBucketIdExtractor(Map<String, ShardManager>)` builds one extractor per tenant. `getBucketInfo` keeps returning `null` when entity metadata is absent, and throws `IllegalArgumentException("Unknown tenant: <id>")` when the tenant has no extractor.
- `BucketKeyPersistor` is already constructed per tenant (`MultiTenantDBShardingBundleBase:323`) with a `tenantId` and an extractor, so it simply receives `new ConsistentHashBucketIdExtractor<>(shardManagers.get(tenantId))` and calls `bucketId(key)`. Its stored `tenantId` field is retained only if still used for other purposes; otherwise it is dropped.

### 2. The bundle owns the calculators

`MultiTenantDBShardingBundleBase` gains:

```java
@Getter
private final Map<String, ShardCalculator<String>> shardCalculators = Maps.newHashMap();
```

populated inside the existing per-tenant loop in `run()`, immediately after the tenant's `ShardManager` is created:

```java
final var shardManager = createShardManager(shardCount, blacklistingStore);
this.shardManagers.put(tenantId, shardManager);
this.shardCalculators.put(tenantId,
        new ShardCalculator<>(shardManager, new ConsistentHashBucketIdExtractor<>(shardManager)));
```

One calculator per tenant, created once, for the life of the bundle.

### 3. DAOs receive the calculator map

Every `MultiTenant*Dao` constructor replaces its `Map<String, ShardManager> shardManagers` parameter with `Map<String, ShardCalculator<String>> shardCalculators` and stores it. No DAO calls `new ShardCalculator<>(...)`.

Affected constructors:

- `MultiTenantLookupDao`
- `MultiTenantRelationalDao`
- `MultiTenantCacheableLookupDao` (passes through to super)
- `MultiTenantCacheableRelationalDao` (passes through to super)

The roughly thirty-seven internal call sites change from `shardCalculator.shardId(tenantId, key)` to `calculatorFor(tenantId).shardId(key)`, where:

```java
private ShardCalculator<String> calculatorFor(String tenantId) {
    final var calculator = shardCalculators.get(tenantId);
    if (calculator == null) {
        throw new IllegalArgumentException("Unknown tenant: " + tenantId);
    }
    return calculator;
}
```

This replaces today's silent `NullPointerException` on an unknown tenant with a named failure.

`WrapperDao` is single-tenant: its constructors take one `ShardCalculator<String>` instead of a `ShardManager`, supplied by the bundle from `shardCalculators.get(tenantId)`. `WrapperDao:120` becomes `daos.get(shardCalculator.shardId(parentKey))`.

### 4. `ShardedDao` is deleted

`io.appform.dropwizard.sharding.dao.ShardedDao` is removed, together with every `getShardCalculator()` implementation and the `@Getter` on the `shardCalculator` field. The five implementors (`LookupDao`, `RelationalDao`, `MultiTenantLookupDao`, `MultiTenantRelationalDao`, `WrapperDao`) drop the `implements` clause.

### 5. Java 17 release target and sealed hierarchies

`maven-compiler-plugin` moves from `<release>11</release>` to `<release>17</release>` (source and target are already 17). This makes `sealed` available and is a breaking runtime requirement for consumers.

The DAO hierarchies become explicit:

| Class | Modifier |
| --- | --- |
| `MultiTenantLookupDao` | `sealed ... permits MultiTenantCacheableLookupDao` |
| `MultiTenantCacheableLookupDao` | `final` |
| `MultiTenantRelationalDao` | `sealed ... permits MultiTenantCacheableRelationalDao` |
| `MultiTenantCacheableRelationalDao` | `final` |
| `LookupDao` | `sealed ... permits CacheableLookupDao` |
| `CacheableLookupDao` | `final` |
| `RelationalDao` | `sealed ... permits CacheableRelationalDao` |
| `CacheableRelationalDao` | `final` |
| `WrapperDao` | `final` |

### 6. Package-private constructors

Every constructor on the nine DAO classes above loses `public`. Combined with `sealed`, this closes both instantiation and subclassing from outside `io.appform.dropwizard.sharding.dao`.

### 7. `DaoFactory` enum singleton

A new `public enum DaoFactory { INSTANCE; ... }` in `io.appform.dropwizard.sharding.dao` is the only construction path. Being an enum, it cannot be instantiated by clients. Its methods take bundle-internal objects a client cannot obtain, so it is not a practical bypass.

```java
public enum DaoFactory {
    INSTANCE;

    public <T> MultiTenantLookupDao<T> createParentObjectDao(
            Map<String, List<SessionFactory>> sessionFactories,
            Class<T> entityClass,
            Map<String, ShardCalculator<String>> shardCalculators,
            Map<String, ShardingBundleOptions> shardingOptions,
            Map<String, ShardInfoProvider> shardInfoProviders,
            TransactionObserver observer) { ... }

    // cacheable lookup, relational, cacheable relational, wrapper,
    // plus the single-tenant wrappers LookupDao / CacheableLookupDao /
    // RelationalDao / CacheableRelationalDao built over a delegate.
}
```

### 8. Bundle wiring

`MultiTenantDBShardingBundleBase.createParentObjectDao`, `createRelatedObjectDao` and `createWrapperDao` delegate to `DaoFactory.INSTANCE`, passing `this.shardCalculators` in place of `this.shardManagers`. `DBShardingBundleBase`'s single-tenant wrappers (`DBShardingBundleBase:177-201`) likewise route through the factory instead of `new LookupDao<>(...)`.

The `Preconditions.checkArgument` tenant guard in `createWrapperDao` now checks `sessionFactories` and `shardCalculators`.

### 9. Sealed jar

`maven-jar-plugin` gains manifest sealing for the DAO packages:

```xml
<archive>
  <manifestSections>
    <manifestSection>
      <name>io/appform/dropwizard/sharding/dao/</name>
      <manifestEntries>
        <Sealed>true</Sealed>
      </manifestEntries>
    </manifestSection>
    <manifestSection>
      <name>io/appform/dropwizard/sharding/dao/operations/</name>
      <manifestEntries>
        <Sealed>true</Sealed>
      </manifestEntries>
    </manifestSection>
  </manifestSections>
</archive>
```

A consumer that ships a class in those packages from another jar gets a `SecurityException` at class definition time, so the package-private constructors cannot be reached by package-squatting.

Sealing applies to the packaged jar only, so the project's own tests (loaded from `target/test-classes` against `target/classes`) are unaffected.

## Data flow

```
run() per tenant
  createShardManager -> ShardManager
                     -> ConsistentHashBucketIdExtractor(shardManager)
                     -> ShardCalculator(shardManager, extractor)
                     -> shardCalculators[tenantId]

createParentObjectDao(clazz)
  -> DaoFactory.INSTANCE.createParentObjectDao(sessionFactories, clazz,
         shardCalculators, shardingOptions, shardInfoProviders, rootObserver)
  -> MultiTenantLookupDao holds shardCalculators

dao.get(tenantId, key)
  -> calculatorFor(tenantId).shardId(key) -> int shard
  -> daos[tenantId][shard]
```

## Error handling

- Unknown tenant in any DAO lookup: `IllegalArgumentException("Unknown tenant: <id>")` from `calculatorFor`, replacing the current `NullPointerException`.
- `createWrapperDao` keeps its eager `Preconditions.checkArgument` guard, now validating against `shardCalculators`.
- Blacklisted-shard behaviour is unchanged: it lives in `ShardManager.shardForBucket` and is merely delegated to.

## Testing

Existing coverage is the regression net. Behaviour must be identical; only construction changes.

**Migrated tests.** The 54 direct `new ...Dao<>(...)` sites across 14 test files move to `DaoFactory.INSTANCE`, which is reachable from `ScrollTest` and `dao/locktest/*` even though they sit outside the `dao` package.

Tests reading `dao.getShardCalculator()` (`WrapperDaoTransactionReuseTest` at lines 87, 133, 184, 238; `RelationalDaoTest:111`; `MultiTenantRelationalDaoTest:115`) construct their own calculator in setup:

```java
this.shardCalculator = new ShardCalculator<>(shardManager, new ConsistentHashBucketIdExtractor<>(shardManager));
```

They already build the `ShardManager`, so shard-placement assertions recompute the expected shard rather than borrowing DAO internals.

**New tests.**

- `ShardCalculatorTest`: `shardId` and `isOnValidShard` against a known `ShardManager`, including a blacklisted shard.
- `ConsistentHashBucketIdExtractorTest` is updated for the single-`ShardManager` constructor and the `bucketId(id)` signature; its existing assertions on hash-to-bucket mapping must produce identical bucket ids.
- `BucketResolverTest` coverage for the tenant-keyed extractor map, including the unknown-tenant failure.
- `DaoFactoryTest`: each `create...` method returns a DAO wired to the supplied calculator map and performs a real read/write.
- `DaoEncapsulationTest` (reflection): every constructor on the nine DAO classes is non-public; leaf classes report `isFinal`; base classes report `isSealed()` with the expected `getPermittedSubclasses()`; `ShardedDao` no longer exists.
- Unknown-tenant test: `IllegalArgumentException` whose message contains the tenant id.

**Build verification.** `mvn -q test` for behaviour, `mvn -q package` to confirm the Java 17 target and the sealed manifest. Manifest sealing is checked manually in the release checklist with `unzip -p target/*.jar META-INF/MANIFEST.MF | grep Sealed`, since the manifest exists only after `package`.

## Migration and breaking changes

Version moves from `2.1.12-9` to `2.1.12-10`, keeping the existing scheme.

Breaking changes for consumers:

1. Java 17 runtime is now required (was Java 11).
2. `ShardCalculator.shardId(tenantId, key)` becomes `shardId(key)`; `isOnValidShard(tenantId, key)` becomes `isOnValidShard(key)`. Callers resolve the tenant's calculator from the bundle's `getShardCalculators()` map.
3. `BucketIdExtractor.bucketId(tenantId, id)` becomes `bucketId(id)`. Custom implementations must be updated.
4. `ShardedDao` and all `getShardCalculator()` methods are removed.
5. DAO constructors are package-private and the classes are `sealed` or `final`. DAOs must be obtained from the bundle's `createParentObjectDao` / `createRelatedObjectDao` / `createWrapperDao` methods.

README gains a short "Creating DAOs" section pointing at the bundle factory methods, and the changelog records the five items above.

# Changelog

All notable changes to this project will be documented in this file.

## [2.1.12-12]
- `ShardCalculator` is now a bundle-owned, sealed, per-tenant construct published to `ShardCalculatorRegistry`.
- DAOs no longer receive `ShardManager`s; one calculator and one bucket extractor exist per tenant.

  **Breaking changes**
  1. Java 17 runtime is required.
  2. `ShardCalculator` moved to `io.appform.dropwizard.sharding`, is a sealed non-generic interface, and is single-tenant (`shardId(key)`, `isOnValidShard(key)`, `tenantId()`). Obtain it from `ShardCalculatorRegistry.get(tenantId)` or the bundle's `getShardCalculator(...)`. It cannot be constructed or implemented by applications.
  3. `BucketIdExtractor.bucketId(tenantId, id)` is now `bucketId(id)`; `ConsistentHashBucketIdExtractor` takes a single `ShardManager`.
  4. `ShardedDao` and every DAO `getShardCalculator()` were removed.
  5. DAO classes are sealed or final with package-private constructors. Obtain DAOs from the bundle's `create...Dao` methods.
  6. A tenant id can be registered once per JVM; two bundles sharing a tenant id (including two single-tenant bundles on the default namespace) fail at startup.
  7. The `io.appform.dropwizard.sharding` and `io.appform.dropwizard.sharding.dao` packages are sealed in the jar; application classes in those packages will fail to load.
  8. `getShardManagers()`, `getSessionFactories()` and `getShardingOptions()` return unmodifiable maps.
  9. `BucketKeyPersistor` now takes `(ToIntFunction<String> bucketIdFunction, Map<String, EntityMeta> initialisedEntitiesMeta)`; `BucketResolver` takes a per-tenant `Map<String, ToIntFunction<T>>` plus `Map<String, EntityMeta> initialisedEntitiesMeta`, and throws `IllegalArgumentException` for an unknown tenant.
  10. `BundleCommonBase.initialisedEntitiesMeta` is no longer `protected`; use `getInitialisedEntitiesMeta()` (read-only, available once the bundle is initialised). The `bucketResolver` and `rootObserver` fields are no longer `protected`. `registerBucketIdExtractor` was removed.

## [2.1.12-7]
- Added logs to print the original exception during DB exception handling.

  **Reason**: For DB-related exceptions, rollback is attempted in the `catch` block and then the exception is rethrown.
  - If rollback also fails (for example, due to a database connectivity issue), the rollback exception can override the original exception.
  - Logging the original exception preserves the root cause and improves debugging.

## [2.1.10-10]
- Added changes for new central deployment.
- Exposed interface for entity registration in `DbShardingBundle`. ([#130](https://github.com/santanusinha/dropwizard-db-sharding-bundle/pull/130))
- Allowed locked context operations to add more operations during `execute()` phase. ([#133](https://github.com/santanusinha/dropwizard-db-sharding-bundle/pull/133))
- Added Shard Order Test. ([#129](https://github.com/santanusinha/dropwizard-db-sharding-bundle/pull/129))
- LookupDAO ScatterGather pagination support with `QuerySpec`. ([#126](https://github.com/santanusinha/dropwizard-db-sharding-bundle/pull/126))
- Upgraded H2 database version.
- Fixed `BucketKeyPersistorTest`.
- Updated and fixed changelog.

## [2.1.10-9]

### Added
- Replaced `dropwizard-hibernate` with direct use of `hibernate-core`.

  **Reason**: `dropwizard-hibernate` is a Dropwizard bundle.
  - With `-Db.shards` deprecated in favor of multi-tenancy, shard configuration is only available during `run()`. This meant Hibernate bundles couldn't be initialized in `initialize()`, violating Dropwizard lifecycle expectations. 
  - Parallel initialization of SessionFactory instances was previously not feasible, as Dropwizard requires all bundles to be registered sequentially, and the Environment's managedObjects is not thread-safe.
  
- Added support for parallel `SessionFactory` initialization per tenant.
- Introduced `BucketObserver` support:
  - Bucket IDs are now automatically populated in the column annotated with `@BucketKey`.
  - Population is based on `@LookupKey` in `LookupDao` or `@ShardingKey` in `RelationalDao`.

### Changed
- `skipNativeHealthCheck` is now part of `ShardingBundleOptions` (moved from `BlacklistConfig`).
- Defaulted `skipNativeHealthCheck` to `true`, meaning native health checks are skipped by default.

### Deprecated
- `BlacklistConfig` has been removed.

### Notes
- To enable the blacklisting feature:
  - Provide a concrete implementation of `ShardBlacklistingStore` during bundle initialization.
  - By default, a `NoopShardBlacklistingStore` is used.
- If blacklisting is enabled, native health checks will be skipped automatically, regardless of the `skipNativeHealthCheck` option.
- Important: Skipping health checks means the application will report as healthy even if one or all database shards are down.

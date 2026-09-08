# Changelog

All notable changes to this project will be documented in this file.

## [Unreleased]
- Replaced the global mutable shard-calculator lookup with per-tenant `ShardCalculator` instances built once inside the bundle's `run()` and injected directly into each DAO's constructor.
- Made all DAO constructors (`LookupDao`, `RelationalDao`, `WrapperDao`, `CacheableLookupDao`, `CacheableRelationalDao`, and their multi-tenant counterparts) package-private so DAOs can only be constructed by bundle classes.
- Moved `DBShardingBundleBase`, `MultiTenantDBShardingBundleBase`, and `BundleCommonBase` from `io.appform.dropwizard.sharding` to `io.appform.dropwizard.sharding.dao` so they retain access to the now package-private DAO constructors; `BundleCommonBase#getBucketInfo` remains `protected`.
- `ShardCalculator` is now tenant-scoped: its constructor takes a `tenantId`, and `shardId`/`isOnValidShard` are now single-arg methods (the two-arg `(tenantId, key)` overloads were removed).
- Removed the `ShardedDao<T>` interface entirely, along with the `getShardCalculator()` accessors it required on `LookupDao`, `RelationalDao`, `WrapperDao`, and the package-private `getShardCalculator(String tenantId)` on `MultiTenantLookupDao`/`MultiTenantRelationalDao`. DAOs no longer expose their internal `ShardCalculator` in any form; bundle-level `getShardCalculator(...)` accessors on `DBShardingBundleBase`/`MultiTenantDBShardingBundleBase` are unaffected.

  **Reason**: This is a breaking change for any consumer that directly constructed DAOs, imported the old `io.appform.dropwizard.sharding.DBShardingBundleBase`/`MultiTenantDBShardingBundleBase`/`BundleCommonBase` package paths, called the two-arg `ShardCalculator` methods, or relied on `ShardedDao<T>`/`dao.getShardCalculator()`. It removes a static/global shard-calculator registry in favor of instances scoped to and owned by the bundle, eliminating a class of bugs where shard-routing state could leak or be shared unexpectedly across tenants.

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

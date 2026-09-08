# Shard Calculator Package-Private Injection Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build one `ShardCalculator<String>` per tenant inside the bundle and inject it directly into DAO constructors, replacing per-DAO calculator construction — enforced by making DAO constructors package-private and moving the bundle classes that build DAOs into `io.appform.dropwizard.sharding.dao`.

**Architecture:** `ShardCalculator<T>` becomes tenant-scoped (holds one `ShardManager` + `tenantId`, no more `Map`-based multi-tenant API). `MultiTenantDBShardingBundleBase` (relocated to the `dao` package) builds a `Map<String, ShardCalculator<String>>` once per tenant in `run()` and passes it (or a single entry) into DAO constructors, which are now package-private so nothing outside `io.appform.dropwizard.sharding.dao` can construct a DAO directly.

**Tech Stack:** Java 11, Dropwizard, Hibernate, JUnit 5, Maven.

**Spec:** `docs/superpowers/specs/2026-09-08-shard-calculator-package-private-injection-design.md`

---

## Before You Start

Work in the branch `feature/shard-calculator-package-private-di` (already created off `origin/master`). Confirm you're on it:

```bash
git branch --show-current
```
Expected: `feature/shard-calculator-package-private-di`

All file paths below are relative to the repo root.

---

### Task 1: Simplify `ShardCalculator<T>` to be tenant-scoped

**Files:**
- Modify: `src/main/java/io/appform/dropwizard/sharding/utils/ShardCalculator.java`

- [ ] **Step 1: Replace the whole file contents**

```java
/*
 * Copyright 2016 Santanu Sinha <santanu.sinha@gmail.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package io.appform.dropwizard.sharding.utils;

import io.appform.dropwizard.sharding.sharding.BucketIdExtractor;
import io.appform.dropwizard.sharding.sharding.ShardManager;

import java.util.Objects;

/**
 * Utility class for calculating shards. One instance is bound to a single tenant.
 */
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

- [ ] **Step 2: Compile just this file's package to catch obvious syntax issues**

Run: `mvn -q -pl . compile -DskipTests 2>&1 | head -100`
Expected: Many errors from other files still referencing the old `Map`-based constructor/API — that's expected at this point. Confirm the errors are NOT inside `ShardCalculator.java` itself (no errors reported for that file specifically).

- [ ] **Step 3: Commit**

```bash
git add src/main/java/io/appform/dropwizard/sharding/utils/ShardCalculator.java
git commit -m "refactor: make ShardCalculator tenant-scoped"
```

---

### Task 2: Move bundle base classes into the `dao` package

**Files:**
- Move: `src/main/java/io/appform/dropwizard/sharding/DBShardingBundleBase.java` → `src/main/java/io/appform/dropwizard/sharding/dao/DBShardingBundleBase.java`
- Move: `src/main/java/io/appform/dropwizard/sharding/MultiTenantDBShardingBundleBase.java` → `src/main/java/io/appform/dropwizard/sharding/dao/MultiTenantDBShardingBundleBase.java`

- [ ] **Step 1: Move the files with git (preserves history)**

```bash
git mv src/main/java/io/appform/dropwizard/sharding/DBShardingBundleBase.java \
       src/main/java/io/appform/dropwizard/sharding/dao/DBShardingBundleBase.java
git mv src/main/java/io/appform/dropwizard/sharding/MultiTenantDBShardingBundleBase.java \
       src/main/java/io/appform/dropwizard/sharding/dao/MultiTenantDBShardingBundleBase.java
```

- [ ] **Step 2: Update the package declaration in both moved files**

In `src/main/java/io/appform/dropwizard/sharding/dao/DBShardingBundleBase.java`, change:
```java
package io.appform.dropwizard.sharding;
```
to:
```java
package io.appform.dropwizard.sharding.dao;
```

In `src/main/java/io/appform/dropwizard/sharding/dao/MultiTenantDBShardingBundleBase.java`, change:
```java
package io.appform.dropwizard.sharding;
```
to:
```java
package io.appform.dropwizard.sharding.dao;
```

- [ ] **Step 3: Fix imports inside the two moved files**

Both files reference types that are still in `io.appform.dropwizard.sharding` (not `.dao`) — those now need explicit imports since the file itself moved out of that package. In `DBShardingBundleBase.java`, the DAO types it imports (`LookupDao`, `RelationalDao`, `CacheableLookupDao`, `CacheableRelationalDao`, `WrapperDao`, `AbstractDAO`) are already same-package — **delete** those now-redundant imports:
```java
import io.appform.dropwizard.sharding.dao.AbstractDAO;
import io.appform.dropwizard.sharding.dao.CacheableLookupDao;
import io.appform.dropwizard.sharding.dao.CacheableRelationalDao;
import io.appform.dropwizard.sharding.dao.LookupDao;
import io.appform.dropwizard.sharding.dao.RelationalDao;
import io.appform.dropwizard.sharding.dao.WrapperDao;
```
and **add** imports for the types that stayed behind in `io.appform.dropwizard.sharding`:
```java
import io.appform.dropwizard.sharding.ShardInfoProvider;
```
(`ShardInfoProvider` is referenced by `DBShardingBundleBase`; verify by searching the file for `ShardInfoProvider` — if unused there, skip this import.)

Do the same for `MultiTenantDBShardingBundleBase.java`: remove the now-redundant `io.appform.dropwizard.sharding.dao.*` imports for DAO types (`MultiTenantCacheableLookupDao`, `MultiTenantCacheableRelationalDao`, `MultiTenantLookupDao`, `MultiTenantRelationalDao`, `WrapperDao`, `AbstractDAO`), and add imports for types that stayed in the parent package:
```java
import io.appform.dropwizard.sharding.BundleCommonBase;
import io.appform.dropwizard.sharding.ShardInfoProvider;
```

- [ ] **Step 4: Update the 4 subclasses that extend the moved base classes**

In `src/main/java/io/appform/dropwizard/sharding/DBShardingBundle.java`, add after the `package` line:
```java
import io.appform.dropwizard.sharding.dao.DBShardingBundleBase;
```

In `src/main/java/io/appform/dropwizard/sharding/BalancedDBShardingBundle.java`, add:
```java
import io.appform.dropwizard.sharding.dao.DBShardingBundleBase;
```

In `src/main/java/io/appform/dropwizard/sharding/MultiTenantDBShardingBundle.java`, add:
```java
import io.appform.dropwizard.sharding.dao.MultiTenantDBShardingBundleBase;
```

In `src/main/java/io/appform/dropwizard/sharding/MultiTenantBalancedDBShardingBundle.java`, add:
```java
import io.appform.dropwizard.sharding.dao.MultiTenantDBShardingBundleBase;
```

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "refactor: move bundle base classes into dao package"
```

---

### Task 3: Add per-tenant `ShardCalculator` map to `MultiTenantDBShardingBundleBase`

**Files:**
- Modify: `src/main/java/io/appform/dropwizard/sharding/dao/MultiTenantDBShardingBundleBase.java`

- [ ] **Step 1: Add the field and import**

Add import:
```java
import io.appform.dropwizard.sharding.sharding.impl.ConsistentHashBucketIdExtractor;
import io.appform.dropwizard.sharding.utils.ShardCalculator;
```
(`ConsistentHashBucketIdExtractor` is already imported for the `BucketKeyPersistor` setup — check first and don't duplicate.)

Add field next to `shardManagers`:
```java
  @Getter
  private Map<String, ShardManager> shardManagers = Maps.newHashMap();

  @Getter
  private Map<String, ShardCalculator<String>> shardCalculators = Maps.newHashMap();
```

- [ ] **Step 2: Build the calculator right after the shard manager, inside `run()`**

Find:
```java
        final var blacklistingStore = getBlacklistingStore();
        final var shardManager = createShardManager(shardCount, blacklistingStore);
        this.shardManagers.put(tenantId, shardManager);
```
Replace with:
```java
        final var blacklistingStore = getBlacklistingStore();
        final var shardManager = createShardManager(shardCount, blacklistingStore);
        this.shardManagers.put(tenantId, shardManager);
        this.shardCalculators.put(tenantId, new ShardCalculator<>(tenantId, shardManager,
                new ConsistentHashBucketIdExtractor<>(Map.of(tenantId, shardManager))));
```

- [ ] **Step 3: Update DAO factory methods to inject calculators instead of shard managers**

Find:
```java
  public <EntityType, T extends Configuration>
  MultiTenantLookupDao<EntityType> createParentObjectDao(Class<EntityType> clazz) {
    return new MultiTenantLookupDao<>(this.sessionFactories, clazz,
        this.shardManagers,
        this.shardingOptions,
        shardInfoProviders,
        rootObserver);
  }
```
Replace with:
```java
  public <EntityType, T extends Configuration>
  MultiTenantLookupDao<EntityType> createParentObjectDao(Class<EntityType> clazz) {
    return new MultiTenantLookupDao<>(this.sessionFactories, clazz,
        this.shardCalculators,
        this.shardingOptions,
        shardInfoProviders,
        rootObserver);
  }
```

Find:
```java
  public <EntityType, T extends Configuration>
  MultiTenantCacheableLookupDao<EntityType> createParentObjectDao(Class<EntityType> clazz,
      Map<String, LookupCache<EntityType>> cacheManager) {
    return new MultiTenantCacheableLookupDao<>(this.sessionFactories,
        clazz, this.shardManagers,
        cacheManager,
        this.shardingOptions,
        shardInfoProviders,
        rootObserver);
  }
```
Replace with:
```java
  public <EntityType, T extends Configuration>
  MultiTenantCacheableLookupDao<EntityType> createParentObjectDao(Class<EntityType> clazz,
      Map<String, LookupCache<EntityType>> cacheManager) {
    return new MultiTenantCacheableLookupDao<>(this.sessionFactories,
        clazz, this.shardCalculators,
        cacheManager,
        this.shardingOptions,
        shardInfoProviders,
        rootObserver);
  }
```

Find:
```java
  public <EntityType, T extends Configuration>
  MultiTenantRelationalDao<EntityType> createRelatedObjectDao(Class<EntityType> clazz) {
    return new MultiTenantRelationalDao<>(this.sessionFactories, clazz,
        this.shardManagers,
        this.shardingOptions,
        shardInfoProviders,
        rootObserver);
  }
```
Replace with:
```java
  public <EntityType, T extends Configuration>
  MultiTenantRelationalDao<EntityType> createRelatedObjectDao(Class<EntityType> clazz) {
    return new MultiTenantRelationalDao<>(this.sessionFactories, clazz,
        this.shardCalculators,
        this.shardingOptions,
        shardInfoProviders,
        rootObserver);
  }
```

Find:
```java
  public <EntityType, T extends Configuration>
  MultiTenantCacheableRelationalDao<EntityType> createRelatedObjectDao(Class<EntityType> clazz,
      Map<String, RelationalCache<EntityType>> cacheManager) {
    return new MultiTenantCacheableRelationalDao<>(this.sessionFactories,
        clazz,
        this.shardManagers,
        cacheManager,
        this.shardingOptions,
        shardInfoProviders,
        rootObserver);
  }
```
Replace with:
```java
  public <EntityType, T extends Configuration>
  MultiTenantCacheableRelationalDao<EntityType> createRelatedObjectDao(Class<EntityType> clazz,
      Map<String, RelationalCache<EntityType>> cacheManager) {
    return new MultiTenantCacheableRelationalDao<>(this.sessionFactories,
        clazz,
        this.shardCalculators,
        cacheManager,
        this.shardingOptions,
        shardInfoProviders,
        rootObserver);
  }
```

Find:
```java
  public <EntityType, DaoType extends AbstractDAO<EntityType>, T extends Configuration>
  WrapperDao<EntityType, DaoType> createWrapperDao(String tenantId, Class<DaoType> daoTypeClass) {
    Preconditions.checkArgument(
            this.sessionFactories.containsKey(tenantId) && this.shardManagers.containsKey(tenantId),
            "Unknown tenant: " + tenantId);
    return new WrapperDao<>(tenantId, this.sessionFactories.get(tenantId), daoTypeClass, this.shardManagers.get(tenantId));
  }

  public <EntityType, DaoType extends AbstractDAO<EntityType>, T extends Configuration>
  WrapperDao<EntityType, DaoType> createWrapperDao(String tenantId,
      Class<DaoType> daoTypeClass,
      Class[] extraConstructorParamClasses,
      Class[] extraConstructorParamObjects) {
    Preconditions.checkArgument(
            this.sessionFactories.containsKey(tenantId) && this.shardManagers.containsKey(tenantId),
            "Unknown tenant: " + tenantId);
    return new WrapperDao<>(tenantId, this.sessionFactories.get(tenantId), daoTypeClass,
        extraConstructorParamClasses, extraConstructorParamObjects, this.shardManagers.get(tenantId));
  }
```
Replace with:
```java
  public <EntityType, DaoType extends AbstractDAO<EntityType>, T extends Configuration>
  WrapperDao<EntityType, DaoType> createWrapperDao(String tenantId, Class<DaoType> daoTypeClass) {
    Preconditions.checkArgument(
            this.sessionFactories.containsKey(tenantId) && this.shardCalculators.containsKey(tenantId),
            "Unknown tenant: " + tenantId);
    return new WrapperDao<>(tenantId, this.sessionFactories.get(tenantId), daoTypeClass, this.shardCalculators.get(tenantId));
  }

  public <EntityType, DaoType extends AbstractDAO<EntityType>, T extends Configuration>
  WrapperDao<EntityType, DaoType> createWrapperDao(String tenantId,
      Class<DaoType> daoTypeClass,
      Class[] extraConstructorParamClasses,
      Class[] extraConstructorParamObjects) {
    Preconditions.checkArgument(
            this.sessionFactories.containsKey(tenantId) && this.shardCalculators.containsKey(tenantId),
            "Unknown tenant: " + tenantId);
    return new WrapperDao<>(tenantId, this.sessionFactories.get(tenantId), daoTypeClass,
        extraConstructorParamClasses, extraConstructorParamObjects, this.shardCalculators.get(tenantId));
  }
```

- [ ] **Step 4: Add a public per-tenant accessor**

Add this method near the other public getters (below `createWrapperDao` overloads):
```java
  public ShardCalculator<String> getShardCalculator(String tenantId) {
    Preconditions.checkArgument(shardCalculators.containsKey(tenantId), "Unknown tenant: " + tenantId);
    return shardCalculators.get(tenantId);
  }
```

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/appform/dropwizard/sharding/dao/MultiTenantDBShardingBundleBase.java
git commit -m "feat: build per-tenant ShardCalculator in bundle run()"
```

---

### Task 4: Add `getShardCalculator()` delegation to `DBShardingBundleBase`

**Files:**
- Modify: `src/main/java/io/appform/dropwizard/sharding/dao/DBShardingBundleBase.java`

- [ ] **Step 1: Add the delegating accessor**

Add import:
```java
import io.appform.dropwizard.sharding.utils.ShardCalculator;
```

Add method next to `getSessionFactories()`:
```java
    public List<SessionFactory> getSessionFactories() {
        return delegate.getSessionFactories().get(dbNamespace);
    }

    public ShardCalculator<String> getShardCalculator() {
        return delegate.getShardCalculator(dbNamespace);
    }
```

- [ ] **Step 2: Commit**

```bash
git add src/main/java/io/appform/dropwizard/sharding/dao/DBShardingBundleBase.java
git commit -m "feat: expose getShardCalculator() on DBShardingBundleBase"
```

---

### Task 5: Update `MultiTenantLookupDao` — inject calculator map, drop `ShardedDao`, package-private constructor

**Files:**
- Modify: `src/main/java/io/appform/dropwizard/sharding/dao/MultiTenantLookupDao.java`

- [ ] **Step 1: Change the class declaration and fields**

Find:
```java
public class MultiTenantLookupDao<T> implements ShardedDao<T> {

    private final Map<String, List<SessionFactory>> sessionFactories;
    private final Map<String, List<LookupDaoPriv>> daos = Maps.newHashMap();
    private final Class<T> entityClass;
    @Getter
    private final ShardCalculator<String> shardCalculator;
    @Getter
    private final Map<String, ShardingBundleOptions> shardingOptions;
```
Replace with:
```java
public class MultiTenantLookupDao<T> {

    private final Map<String, List<SessionFactory>> sessionFactories;
    private final Map<String, List<LookupDaoPriv>> daos = Maps.newHashMap();
    private final Class<T> entityClass;
    private final Map<String, ShardCalculator<String>> shardCalculators;
    @Getter
    private final Map<String, ShardingBundleOptions> shardingOptions;
```

- [ ] **Step 2: Change the constructor signature and visibility**

Find:
```java
    public MultiTenantLookupDao(
            Map<String, List<SessionFactory>> sessionFactories,
            Class<T> entityClass,
            Map<String, ShardManager> shardManagers,
            Map<String, ShardingBundleOptions> shardingOptions,
            final Map<String, ShardInfoProvider> shardInfoProviders,
            final TransactionObserver observer) {
        this.sessionFactories = sessionFactories;
        sessionFactories.forEach((tenantId, factories) -> {
            daos.put(tenantId, factories.stream().map(LookupDaoPriv::new).collect(Collectors.toList()));
        });
        this.entityClass = entityClass;
        this.shardCalculator = new ShardCalculator<>(shardManagers, new ConsistentHashBucketIdExtractor<>(shardManagers));
        this.shardingOptions = shardingOptions;
```
Replace with:
```java
    MultiTenantLookupDao(
            Map<String, List<SessionFactory>> sessionFactories,
            Class<T> entityClass,
            Map<String, ShardCalculator<String>> shardCalculators,
            Map<String, ShardingBundleOptions> shardingOptions,
            final Map<String, ShardInfoProvider> shardInfoProviders,
            final TransactionObserver observer) {
        this.sessionFactories = sessionFactories;
        sessionFactories.forEach((tenantId, factories) -> {
            daos.put(tenantId, factories.stream().map(LookupDaoPriv::new).collect(Collectors.toList()));
        });
        this.entityClass = entityClass;
        this.shardCalculators = shardCalculators;
        this.shardingOptions = shardingOptions;
```

- [ ] **Step 3: Add the per-tenant accessor**

Add this method right after the constructor (before the first public data-access method):
```java
    public ShardCalculator<String> getShardCalculator(String tenantId) {
        return shardCalculators.get(tenantId);
    }
```

- [ ] **Step 4: Rewrite every call site from `shardCalculator.shardId(tenantId, X)` to `shardCalculators.get(tenantId).shardId(X)`**

Run this from the repo root — the pattern is identical at all 14 call sites in this file:
```bash
sed -i '' -E 's/shardCalculator\.shardId\(tenantId, (\w+)\)/shardCalculators.get(tenantId).shardId(\1)/g' \
    src/main/java/io/appform/dropwizard/sharding/dao/MultiTenantLookupDao.java
```

- [ ] **Step 5: Remove the now-unused imports**

Remove (no longer referenced after the constructor no longer builds its own calculator):
```java
import io.appform.dropwizard.sharding.sharding.ShardManager;
import io.appform.dropwizard.sharding.sharding.impl.ConsistentHashBucketIdExtractor;
```
(Only remove these if `mvn compile` in Task 12 reports them as unused — `ShardManager` may still be referenced elsewhere in the file; check with `grep -n "ShardManager" src/main/java/io/appform/dropwizard/sharding/dao/MultiTenantLookupDao.java` before deleting.)

- [ ] **Step 6: Verify no remaining references to the old field name**

Run: `grep -n "shardCalculator\b" src/main/java/io/appform/dropwizard/sharding/dao/MultiTenantLookupDao.java`
Expected: no output (all call sites now use `shardCalculators`).

- [ ] **Step 7: Commit**

```bash
git add src/main/java/io/appform/dropwizard/sharding/dao/MultiTenantLookupDao.java
git commit -m "refactor: inject per-tenant ShardCalculator map into MultiTenantLookupDao"
```

---

### Task 6: Update `MultiTenantRelationalDao` — same treatment

**Files:**
- Modify: `src/main/java/io/appform/dropwizard/sharding/dao/MultiTenantRelationalDao.java`

- [ ] **Step 1: Change the class declaration and fields**

Find:
```java
public class MultiTenantRelationalDao<T> implements ShardedDao<T> {
```
Replace with:
```java
public class MultiTenantRelationalDao<T> {
```

Find:
```java
    private final Map<String, List<RelationalDaoPriv>> daos = Maps.newHashMap();
    @Getter
    private final Class<T> entityClass;
    @Getter
    private final ShardCalculator<String> shardCalculator;
    @Getter
    private final Map<String, ShardingBundleOptions> shardingOptions;
```
Replace with:
```java
    private final Map<String, List<RelationalDaoPriv>> daos = Maps.newHashMap();
    @Getter
    private final Class<T> entityClass;
    private final Map<String, ShardCalculator<String>> shardCalculators;
    @Getter
    private final Map<String, ShardingBundleOptions> shardingOptions;
```

- [ ] **Step 2: Change the constructor signature and visibility**

Find:
```java
    public MultiTenantRelationalDao(
            Map<String, List<SessionFactory>> sessionFactories,
            Class<T> entityClass,
            Map<String, ShardManager> shardManagers,
            Map<String, ShardingBundleOptions> shardingOptions,
            final Map<String, ShardInfoProvider> shardInfoProviders,
            final TransactionObserver observer) {
        this.shardCalculator = new ShardCalculator<>(shardManagers, new ConsistentHashBucketIdExtractor<>(shardManagers));
        this.shardingOptions = shardingOptions;
```
Replace with:
```java
    MultiTenantRelationalDao(
            Map<String, List<SessionFactory>> sessionFactories,
            Class<T> entityClass,
            Map<String, ShardCalculator<String>> shardCalculators,
            Map<String, ShardingBundleOptions> shardingOptions,
            final Map<String, ShardInfoProvider> shardInfoProviders,
            final TransactionObserver observer) {
        this.shardCalculators = shardCalculators;
        this.shardingOptions = shardingOptions;
```

- [ ] **Step 3: Add the per-tenant accessor**

Add right after the constructor:
```java
    public ShardCalculator<String> getShardCalculator(String tenantId) {
        return shardCalculators.get(tenantId);
    }
```

- [ ] **Step 4: Rewrite every call site (21 occurrences)**

```bash
sed -i '' -E 's/shardCalculator\.shardId\(tenantId, (\w+)\)/shardCalculators.get(tenantId).shardId(\1)/g' \
    src/main/java/io/appform/dropwizard/sharding/dao/MultiTenantRelationalDao.java
```

- [ ] **Step 5: Verify no remaining references to the old field name**

Run: `grep -n "shardCalculator\b" src/main/java/io/appform/dropwizard/sharding/dao/MultiTenantRelationalDao.java`
Expected: no output.

- [ ] **Step 6: Remove now-unused imports if flagged by the compiler in Task 12** (`ShardManager`, `ConsistentHashBucketIdExtractor` — check usage with grep before deleting, same caution as Task 5 Step 5).

- [ ] **Step 7: Commit**

```bash
git add src/main/java/io/appform/dropwizard/sharding/dao/MultiTenantRelationalDao.java
git commit -m "refactor: inject per-tenant ShardCalculator map into MultiTenantRelationalDao"
```

---

### Task 7: Update `MultiTenantCacheableLookupDao` and `MultiTenantCacheableRelationalDao`

**Files:**
- Modify: `src/main/java/io/appform/dropwizard/sharding/dao/MultiTenantCacheableLookupDao.java`
- Modify: `src/main/java/io/appform/dropwizard/sharding/dao/MultiTenantCacheableRelationalDao.java`

- [ ] **Step 1: `MultiTenantCacheableLookupDao` — update constructor**

Find:
```java
  public MultiTenantCacheableLookupDao(Map<String, List<SessionFactory>> sessionFactories,
                                       Class<T> entityClass,
                                       Map<String, ShardManager> shardManagers,
                                       Map<String, LookupCache<T>> cache,
                                       Map<String, ShardingBundleOptions> shardingOptions,
                                       Map<String, ShardInfoProvider> shardInfoProvider,
                                       TransactionObserver observer) {
    super(sessionFactories, entityClass, shardManagers, shardingOptions, shardInfoProvider, observer);
    this.cache = cache;
  }
```
Replace with:
```java
  MultiTenantCacheableLookupDao(Map<String, List<SessionFactory>> sessionFactories,
                                       Class<T> entityClass,
                                       Map<String, ShardCalculator<String>> shardCalculators,
                                       Map<String, LookupCache<T>> cache,
                                       Map<String, ShardingBundleOptions> shardingOptions,
                                       Map<String, ShardInfoProvider> shardInfoProvider,
                                       TransactionObserver observer) {
    super(sessionFactories, entityClass, shardCalculators, shardingOptions, shardInfoProvider, observer);
    this.cache = cache;
  }
```
Update the import: replace `import io.appform.dropwizard.sharding.sharding.ShardManager;` with `import io.appform.dropwizard.sharding.utils.ShardCalculator;`.

- [ ] **Step 2: `MultiTenantCacheableRelationalDao` — update constructor**

Find:
```java
  public MultiTenantCacheableRelationalDao(Map<String, List<SessionFactory>> sessionFactories,
      Class<T> entityClass,
      Map<String, ShardManager> shardManagers,
      Map<String, RelationalCache<T>> cache,
      Map<String, ShardingBundleOptions> shardingOptions,
      Map<String, ShardInfoProvider> shardInfoProvider,
      TransactionObserver observer) {
    super(sessionFactories, entityClass, shardManagers, shardingOptions, shardInfoProvider, observer);
    this.cache = cache;
  }
```
Replace with:
```java
  MultiTenantCacheableRelationalDao(Map<String, List<SessionFactory>> sessionFactories,
      Class<T> entityClass,
      Map<String, ShardCalculator<String>> shardCalculators,
      Map<String, RelationalCache<T>> cache,
      Map<String, ShardingBundleOptions> shardingOptions,
      Map<String, ShardInfoProvider> shardInfoProvider,
      TransactionObserver observer) {
    super(sessionFactories, entityClass, shardCalculators, shardingOptions, shardInfoProvider, observer);
    this.cache = cache;
  }
```
Update the import: replace `import io.appform.dropwizard.sharding.sharding.ShardManager;` with `import io.appform.dropwizard.sharding.utils.ShardCalculator;`.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/io/appform/dropwizard/sharding/dao/MultiTenantCacheableLookupDao.java \
        src/main/java/io/appform/dropwizard/sharding/dao/MultiTenantCacheableRelationalDao.java
git commit -m "refactor: inject ShardCalculator map into cacheable multi-tenant DAOs"
```

---

### Task 8: Update `WrapperDao` — inject calculator, package-private constructors

**Files:**
- Modify: `src/main/java/io/appform/dropwizard/sharding/dao/WrapperDao.java`

- [ ] **Step 1: Replace the constructors and `forParent`**

Find:
```java
    public WrapperDao(String dbNamespace,
                      List<SessionFactory> sessionFactories,
                      Class<DaoType> daoClass,
                      ShardManager shardManager) {
        this(dbNamespace, sessionFactories, daoClass, null, null, shardManager);
    }
```
Replace with:
```java
    WrapperDao(String dbNamespace,
                      List<SessionFactory> sessionFactories,
                      Class<DaoType> daoClass,
                      ShardCalculator<String> shardCalculator) {
        this(dbNamespace, sessionFactories, daoClass, null, null, shardCalculator);
    }
```

Find:
```java
     public WrapperDao(
             String dbNamespace,
             List<SessionFactory> sessionFactories, Class<DaoType> daoClass,
             Class[] extraConstructorParamClasses,
             Class[] extraConstructorParamObjects,
             ShardManager shardManager) {
        this.dbNamespace = dbNamespace;
        this.shardCalculator = new ShardCalculator<>(Map.of(dbNamespace, shardManager),
                new ConsistentHashBucketIdExtractor<>(Map.of(dbNamespace, shardManager)));
        this.daos = sessionFactories.stream().map((SessionFactory sessionFactory) -> {
```
Replace with:
```java
     WrapperDao(
             String dbNamespace,
             List<SessionFactory> sessionFactories, Class<DaoType> daoClass,
             Class[] extraConstructorParamClasses,
             Class[] extraConstructorParamObjects,
             ShardCalculator<String> shardCalculator) {
        this.dbNamespace = dbNamespace;
        this.shardCalculator = shardCalculator;
        this.daos = sessionFactories.stream().map((SessionFactory sessionFactory) -> {
```

Find:
```java
    public DaoType forParent(final String parentKey) {
        return daos.get(shardCalculator.shardId(dbNamespace, parentKey));
    }
```
Replace with:
```java
    public DaoType forParent(final String parentKey) {
        return daos.get(shardCalculator.shardId(parentKey));
    }
```

- [ ] **Step 2: Remove now-unused imports**

Remove:
```java
import io.appform.dropwizard.sharding.sharding.ShardManager;
import io.appform.dropwizard.sharding.sharding.impl.ConsistentHashBucketIdExtractor;
```
(Check with `grep -n "ShardManager\|ConsistentHashBucketIdExtractor" src/main/java/io/appform/dropwizard/sharding/dao/WrapperDao.java` first — both should now be unreferenced.)

Also remove the now-unused `import java.util.Map;` if `Map.of(...)` is no longer used anywhere else in the file (check with grep first).

- [ ] **Step 3: Commit**

```bash
git add src/main/java/io/appform/dropwizard/sharding/dao/WrapperDao.java
git commit -m "refactor: inject ShardCalculator into WrapperDao instead of building it"
```

---

### Task 9: Update `LookupDao` and `RelationalDao` — package-private constructor + per-tenant delegate lookup

**Files:**
- Modify: `src/main/java/io/appform/dropwizard/sharding/dao/LookupDao.java`
- Modify: `src/main/java/io/appform/dropwizard/sharding/dao/RelationalDao.java`

- [ ] **Step 1: `LookupDao` — constructor visibility**

Find:
```java
    public LookupDao(final String dbNamespace,
                     final MultiTenantLookupDao<T> delegate) {
        this.dbNamespace = dbNamespace;
        this.delegate = delegate;
    }
```
Replace with:
```java
    LookupDao(final String dbNamespace,
                     final MultiTenantLookupDao<T> delegate) {
        this.dbNamespace = dbNamespace;
        this.delegate = delegate;
    }
```

- [ ] **Step 2: `LookupDao` — fix `getShardCalculator()`**

Find:
```java
    @Override
    public ShardCalculator<String> getShardCalculator() {
        return delegate.getShardCalculator();
    }
```
Replace with:
```java
    @Override
    public ShardCalculator<String> getShardCalculator() {
        return delegate.getShardCalculator(dbNamespace);
    }
```

- [ ] **Step 3: `RelationalDao` — constructor visibility**

Find:
```java
    public RelationalDao(final String tenantId,
                         final MultiTenantRelationalDao<T> delegate) {
        this.tenantId = tenantId;
        this.delegate = delegate;
    }
```
Replace with:
```java
    RelationalDao(final String tenantId,
                         final MultiTenantRelationalDao<T> delegate) {
        this.tenantId = tenantId;
        this.delegate = delegate;
    }
```

- [ ] **Step 4: `RelationalDao` — fix `getShardCalculator()`**

Find:
```java
    @Override
    public ShardCalculator<String> getShardCalculator() {
        return delegate.getShardCalculator();
    }
```
Replace with:
```java
    @Override
    public ShardCalculator<String> getShardCalculator() {
        return delegate.getShardCalculator(tenantId);
    }
```

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/appform/dropwizard/sharding/dao/LookupDao.java \
        src/main/java/io/appform/dropwizard/sharding/dao/RelationalDao.java
git commit -m "refactor: make LookupDao/RelationalDao constructors package-private"
```

---

### Task 10: Make `CacheableLookupDao` and `CacheableRelationalDao` constructors package-private

**Files:**
- Modify: `src/main/java/io/appform/dropwizard/sharding/dao/CacheableLookupDao.java`
- Modify: `src/main/java/io/appform/dropwizard/sharding/dao/CacheableRelationalDao.java`

- [ ] **Step 1: `CacheableLookupDao`**

Find:
```java
    public CacheableLookupDao(final String tenantId,
                              final MultiTenantCacheableLookupDao<T> delegate) {
```
Replace with:
```java
    CacheableLookupDao(final String tenantId,
                              final MultiTenantCacheableLookupDao<T> delegate) {
```

- [ ] **Step 2: `CacheableRelationalDao`**

Find:
```java
    public CacheableRelationalDao(String dbNamespace,
                                  MultiTenantCacheableRelationalDao<T> delegate) {
```
Replace with:
```java
    CacheableRelationalDao(String dbNamespace,
                                  MultiTenantCacheableRelationalDao<T> delegate) {
```

- [ ] **Step 3: Commit**

```bash
git add src/main/java/io/appform/dropwizard/sharding/dao/CacheableLookupDao.java \
        src/main/java/io/appform/dropwizard/sharding/dao/CacheableRelationalDao.java
git commit -m "refactor: make Cacheable DAO constructors package-private"
```

---

### Task 11: Confirm `ShardedDao` still fits (no code change expected)

**Files:**
- Verify: `src/main/java/io/appform/dropwizard/sharding/dao/ShardedDao.java` (keep as-is — still implemented by `LookupDao`, `RelationalDao`, `WrapperDao`)

- [ ] **Step 1: Verify `MultiTenantLookupDao`/`MultiTenantRelationalDao` no longer implement `ShardedDao<T>`**

Run: `grep -n "implements ShardedDao" src/main/java/io/appform/dropwizard/sharding/dao/MultiTenantLookupDao.java src/main/java/io/appform/dropwizard/sharding/dao/MultiTenantRelationalDao.java`
Expected: no output (already removed in Tasks 5 and 6).

- [ ] **Step 2: No file changes needed — `ShardedDao` stays as-is.** Skip to Task 12.

---

### Task 12: Move the 3 test files that construct DAOs directly from outside the `dao` package

Three test files construct `LookupDao`/`MultiTenantLookupDao`/`RelationalDao`/`MultiTenantRelationalDao` directly but live outside `io.appform.dropwizard.sharding.dao`, so they lose access once those constructors become package-private. Move them into the `dao` package.

**Files:**
- Move: `src/test/java/io/appform/dropwizard/sharding/ScrollTest.java` → `src/test/java/io/appform/dropwizard/sharding/dao/ScrollTest.java`
- Move: `src/test/java/io/appform/dropwizard/sharding/dao/locktest/LockTest.java` → `src/test/java/io/appform/dropwizard/sharding/dao/LockTest.java`
- Move: `src/test/java/io/appform/dropwizard/sharding/dao/locktest/ParentChildTest.java` → `src/test/java/io/appform/dropwizard/sharding/dao/ParentChildTest.java`

- [ ] **Step 1: Move `ScrollTest.java`**

```bash
git mv src/test/java/io/appform/dropwizard/sharding/ScrollTest.java \
       src/test/java/io/appform/dropwizard/sharding/dao/ScrollTest.java
```
Change its package line from `package io.appform.dropwizard.sharding;` to `package io.appform.dropwizard.sharding.dao;`.
Remove these now-redundant imports (same package now):
```java
import io.appform.dropwizard.sharding.dao.LookupDao;
import io.appform.dropwizard.sharding.dao.MultiTenantLookupDao;
```
Add this import (type is still in the parent package):
```java
import io.appform.dropwizard.sharding.ShardInfoProvider;
```

- [ ] **Step 2: Move `LockTest.java` and `ParentChildTest.java`**

```bash
git mv src/test/java/io/appform/dropwizard/sharding/dao/locktest/LockTest.java \
       src/test/java/io/appform/dropwizard/sharding/dao/LockTest.java
git mv src/test/java/io/appform/dropwizard/sharding/dao/locktest/ParentChildTest.java \
       src/test/java/io/appform/dropwizard/sharding/dao/ParentChildTest.java
```
Change both files' package line from `package io.appform.dropwizard.sharding.dao.locktest;` to `package io.appform.dropwizard.sharding.dao;`.

Both files reference test entities that stay behind in `io.appform.dropwizard.sharding.dao.locktest` (`Category`, `ChildAClass`, `ChildBClass`, `ParentClass`, `SomeLookupObject`, `SomeOtherObject`). Add imports to both moved files for whichever of these six each file actually references (check with `grep -n "Category\|ChildAClass\|ChildBClass\|ParentClass\|SomeLookupObject\|SomeOtherObject" <file>` first):
```java
import io.appform.dropwizard.sharding.dao.locktest.Category;
import io.appform.dropwizard.sharding.dao.locktest.ChildAClass;
import io.appform.dropwizard.sharding.dao.locktest.ChildBClass;
import io.appform.dropwizard.sharding.dao.locktest.ParentClass;
import io.appform.dropwizard.sharding.dao.locktest.SomeLookupObject;
import io.appform.dropwizard.sharding.dao.locktest.SomeOtherObject;
```

Also remove the now-redundant `import io.appform.dropwizard.sharding.DBShardingBundleBase;` from both (same package now).

- [ ] **Step 3: Commit**

```bash
git add -A
git commit -m "test: move DAO-constructing tests into the dao package"
```

---

### Task 13: Fix every remaining compile error in the test suite

The remaining test-compile errors are all one of these three mechanical patterns. Work through them by running the compiler and fixing each reported error using the matching rule below.

**Rule A — stale import.** Any test file with `import io.appform.dropwizard.sharding.DBShardingBundleBase;` that lives inside `io.appform.dropwizard.sharding.dao` (including the files moved in Task 12) should have that import line **deleted** — the class is in the same package now. If the test lives in a genuinely different package (e.g. `io.appform.dropwizard.sharding.observers`), change the import to:
```java
import io.appform.dropwizard.sharding.dao.DBShardingBundleBase;
```

**Rule B — single-tenant DAO construction passing a `ShardManager` where a `ShardCalculator` is now expected.** Any call like:
```java
Map.of(DBShardingBundleBase.DEFAULT_NAMESPACE, shardManager)
```
used as the 3rd constructor argument to `MultiTenantLookupDao`/`MultiTenantRelationalDao`/`MultiTenantCacheableLookupDao`/`MultiTenantCacheableRelationalDao`, or a bare `ShardManager` passed to `WrapperDao`, needs a `ShardCalculator<String>` built from that `shardManager` first. Add this line right after the `shardManager` is constructed in the test's setup method:
```java
final ShardCalculator<String> shardCalculator = new ShardCalculator<>(DBShardingBundleBase.DEFAULT_NAMESPACE, shardManager,
        new ConsistentHashBucketIdExtractor<>(Map.of(DBShardingBundleBase.DEFAULT_NAMESPACE, shardManager)));
```
then replace `Map.of(DBShardingBundleBase.DEFAULT_NAMESPACE, shardManager)` with `Map.of(DBShardingBundleBase.DEFAULT_NAMESPACE, shardCalculator)` (or the bare `shardManager` argument to `WrapperDao` with `shardCalculator`) at each call site in that file. Add imports as needed:
```java
import io.appform.dropwizard.sharding.sharding.impl.ConsistentHashBucketIdExtractor;
import io.appform.dropwizard.sharding.utils.ShardCalculator;
```

Worked example — `src/test/java/io/appform/dropwizard/sharding/dao/RelationalDaoTest.java`: the field `private ShardCalculator<String> shardCalculator;` already exists and is assigned *after* construction via `this.shardCalculator = relationalDao.getShardCalculator();`. Move the calculator construction to happen *before* the two `new RelationalDao<>(...)` calls, assign directly to the field, pass `Map.of(DBShardingBundleBase.DEFAULT_NAMESPACE, this.shardCalculator)` in place of `Map.of(DBShardingBundleBase.DEFAULT_NAMESPACE, shardManager)` in both constructions, and delete the now-redundant `this.shardCalculator = relationalDao.getShardCalculator();` line at the end of `before()`. Also fix the later usage:
```java
if (shardCalculator.shardId(DBShardingBundleBase.DEFAULT_NAMESPACE, id) == expectedShardIndex) {
```
becomes:
```java
if (shardCalculator.shardId(id) == expectedShardIndex) {
```

Worked example — `src/test/java/io/appform/dropwizard/sharding/dao/WrapperDaoTransactionReuseTest.java`: change
```java
        ShardManager shardManager = new BalancedShardManager(sessionFactories.size());
        dao = new WrapperDao<>(DBShardingBundleBase.DEFAULT_NAMESPACE, sessionFactories,
                OrderDao.class, shardManager);
```
to
```java
        ShardManager shardManager = new BalancedShardManager(sessionFactories.size());
        ShardCalculator<String> shardCalculator = new ShardCalculator<>(DBShardingBundleBase.DEFAULT_NAMESPACE, shardManager,
                new ConsistentHashBucketIdExtractor<>(Map.of(DBShardingBundleBase.DEFAULT_NAMESPACE, shardManager)));
        dao = new WrapperDao<>(DBShardingBundleBase.DEFAULT_NAMESPACE, sessionFactories,
                OrderDao.class, shardCalculator);
```
and every subsequent `dao.getShardCalculator().shardId(DBShardingBundleBase.DEFAULT_NAMESPACE, parentKey)` becomes `dao.getShardCalculator().shardId(parentKey)` (4 occurrences in this file — lines computing `shardId` in each `@Test` method).

Apply Rule B to these files (all confirmed to need it): `CacheableLookupDaoTest.java`, `EncryptionAtRestTest.java`, `LookupDaoTest.java`, `RelationalDaoTest.java`, `RelationalReadOnlyLockedContextTest.java`, `WrapperDaoTest.java`, `WrapperDaoTransactionReuseTest.java`, `LockTest.java`, `ParentChildTest.java`, `ScrollTest.java` (all under `src/test/java/io/appform/dropwizard/sharding/dao/`).

**Rule C — multi-tenant DAO construction passing a `Map<String, ShardManager>` where a `Map<String, ShardCalculator<String>>` is now expected.** Any test that builds `Map<String, ShardManager> shardManager` (a per-tenant map, e.g. `{"TENANT1": ..., "TENANT2": ...}`) and passes it as the 3rd constructor argument to a `MultiTenantXxxDao` needs a derived calculator map. Add this right after the `shardManager` map is fully populated:
```java
final Map<String, ShardCalculator<String>> shardCalculators = shardManager.entrySet().stream()
        .collect(Collectors.toMap(Map.Entry::getKey,
                entry -> new ShardCalculator<>(entry.getKey(), entry.getValue(),
                        new ConsistentHashBucketIdExtractor<>(Map.of(entry.getKey(), entry.getValue())))));
```
then replace the bare `shardManager` argument with `shardCalculators` at each `new MultiTenantXxxDao<>(...)` call site in that file (keep the `shardManager` field/variable itself — it's usually still used elsewhere for shard-blacklisting assertions). Add imports as needed:
```java
import io.appform.dropwizard.sharding.sharding.impl.ConsistentHashBucketIdExtractor;
import io.appform.dropwizard.sharding.utils.ShardCalculator;
import java.util.stream.Collectors;
```

Worked example — `src/test/java/io/appform/dropwizard/sharding/dao/MultiTenantRelationalDaoTest.java`: the field is `private ShardCalculator<String> shardCalculator;` and it's assigned via `shardCalculator = relationalDao.getShardCalculator();` after construction — since `MultiTenantRelationalDao` no longer has a no-arg `getShardCalculator()`, change the field type to `private Map<String, ShardCalculator<String>> shardCalculators;`, build it with the stream shown above right after `this.shardManager` is populated, pass `shardCalculators` (not `this.shardManager`) into both `new MultiTenantRelationalDao<>(...)` calls, delete the `shardCalculator = relationalDao.getShardCalculator();` line, and change the later usage:
```java
if (shardCalculator.shardId(tenantId, id) == expectedShardIndex) {
```
to:
```java
if (shardCalculators.get(tenantId).shardId(id) == expectedShardIndex) {
```

Apply Rule C to these files (all confirmed to need it): `MultiTenantLookupDaoTest.java`, `MultiTenantCacheableLookupDaoTest.java`, `MultiTenantRelationalDaoTest.java`, `MultiTenantRelationalReadOnlyLockedContextTest.java` (all under `src/test/java/io/appform/dropwizard/sharding/dao/`).

- [ ] **Step 1: Compile the test sources and capture errors**

```bash
mvn -q test-compile 2>&1 | tee /tmp/compile-errors.txt | head -200
```

- [ ] **Step 2: Fix errors file by file, applying Rule A, B, or C as appropriate, then re-run `mvn -q test-compile` after each file until the file's errors are gone.**

- [ ] **Step 3: Repeat until the build is clean**

```bash
mvn -q test-compile
```
Expected: exits 0 with no output.

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "fix: update test suite for ShardCalculator constructor injection"
```

---

### Task 14: Add a test proving each tenant gets its own `ShardCalculator` instance

**Files:**
- Create: `src/test/java/io/appform/dropwizard/sharding/dao/PerTenantShardCalculatorTest.java`

- [ ] **Step 1: Write the test**

```java
package io.appform.dropwizard.sharding.dao;

import io.appform.dropwizard.sharding.sharding.BalancedShardManager;
import io.appform.dropwizard.sharding.sharding.ShardManager;
import io.appform.dropwizard.sharding.sharding.impl.ConsistentHashBucketIdExtractor;
import io.appform.dropwizard.sharding.utils.ShardCalculator;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class PerTenantShardCalculatorTest {

    @Test
    void eachTenantGetsItsOwnShardCalculatorInstance() {
        ShardManager tenant1Manager = new BalancedShardManager(4);
        ShardManager tenant2Manager = new BalancedShardManager(2);

        ShardCalculator<String> tenant1Calculator = new ShardCalculator<>("TENANT1", tenant1Manager,
                new ConsistentHashBucketIdExtractor<>(Map.of("TENANT1", tenant1Manager)));
        ShardCalculator<String> tenant2Calculator = new ShardCalculator<>("TENANT2", tenant2Manager,
                new ConsistentHashBucketIdExtractor<>(Map.of("TENANT2", tenant2Manager)));

        assertNotSame(tenant1Calculator, tenant2Calculator);

        String key = "some-consistent-routing-key";
        int tenant1Shard = tenant1Calculator.shardId(key);
        int tenant2Shard = tenant2Calculator.shardId(key);

        // Both must be valid indices into their own (differently sized) shard pools.
        assertTrue(tenant1Shard >= 0 && tenant1Shard < 4);
        assertTrue(tenant2Shard >= 0 && tenant2Shard < 2);
    }
}
```

- [ ] **Step 2: Run it**

```bash
mvn -q -Dtest=PerTenantShardCalculatorTest test
```
Expected: `BUILD SUCCESS`, 1 test run, 0 failures.

- [ ] **Step 3: Commit**

```bash
git add src/test/java/io/appform/dropwizard/sharding/dao/PerTenantShardCalculatorTest.java
git commit -m "test: verify per-tenant ShardCalculator isolation"
```

---

### Task 15: Add a test proving the bundle's `getShardCalculator()` matches the instance injected into DAOs

**Files:**
- Modify: `src/test/java/io/appform/dropwizard/sharding/DBShardingBundleTestBase.java`

- [ ] **Step 1: Add imports**

```java
import io.appform.dropwizard.sharding.dao.DBShardingBundleBase;
```
(Needed because the class moved packages in Task 2 — this test base lives in `io.appform.dropwizard.sharding`, not `.dao`. Also confirm `import static org.junit.jupiter.api.Assertions.assertEquals;` is present — add it if not.)

- [ ] **Step 2: Add the test method**

Add this test method inside `DBShardingBundleTestBase`, alongside the other `@Test` methods that call `getBundle()`:
```java
    @Test
    public void testShardCalculatorMatchesInjectedInstance() {
        DBShardingBundleBase<TestConfig> bundle = getBundle();
        RelationalDao<Order> relationalDao = bundle.createRelatedObjectDao(Order.class);
        assertEquals(bundle.getShardCalculator(), relationalDao.getShardCalculator());
    }
```
(This asserts reference equality via `Object.equals`, since `ShardCalculator` has no custom `equals()` override — exactly what we want: same instance injected into both the bundle accessor and the DAO.)

- [ ] **Step 3: Run the full bundle test suite that extends this base**

```bash
mvn -q -Dtest=BalancedDBShardingBundleWithAnnotationTest test
```
Expected: `BUILD SUCCESS`.

- [ ] **Step 4: Commit**

```bash
git add src/test/java/io/appform/dropwizard/sharding/DBShardingBundleTestBase.java
git commit -m "test: verify bundle-level ShardCalculator matches DAO-injected instance"
```

---

### Task 16: Full verification

- [ ] **Step 1: Run the full test suite**

```bash
mvn -q test 2>&1 | tail -200
```
Expected: `BUILD SUCCESS`, no test failures.

- [ ] **Step 2: Confirm no remaining direct DAO construction exists outside the `dao` package**

```bash
grep -rln "new MultiTenantLookupDao<\|new MultiTenantRelationalDao<\|new MultiTenantCacheableLookupDao<\|new MultiTenantCacheableRelationalDao<\|new LookupDao<\|new RelationalDao<\|new WrapperDao<\|new CacheableLookupDao<\|new CacheableRelationalDao<" \
    src/main/java src/test/java | grep -v "/dao/"
```
Expected: no output.

- [ ] **Step 3: Confirm no leftover references to the old package path**

```bash
grep -rln "io.appform.dropwizard.sharding.DBShardingBundleBase\|io.appform.dropwizard.sharding.MultiTenantDBShardingBundleBase" src/main/java src/test/java
```
Expected: no output (all references now go through `io.appform.dropwizard.sharding.dao.*`).

- [ ] **Step 4: Final commit if anything is outstanding**

```bash
git status
git add -A
git commit -m "chore: final cleanup for package-private shard calculator injection" --allow-empty
```

---

## Self-Review Notes (for whoever executes this plan)

- Every DAO-constructor change in Tasks 5–10 removes the `public` modifier — double check no other production code outside `io.appform.dropwizard.sharding.dao` calls these constructors (Task 16 Step 2 verifies this for both `src/main` and `src/test`).
- The compiler is your source of truth for Task 13 — don't try to guess every call site up front; let `mvn test-compile` errors drive you through Rules A/B/C file by file.
- If `mvn test-compile` reports an unused-import warning treated as an error (unlikely with default Maven config, but possible if a strict linter plugin is configured), remove the flagged import.

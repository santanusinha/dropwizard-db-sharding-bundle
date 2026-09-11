# Bundle-Level ShardCalculator and DAO Lockdown Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make `ShardCalculator` a single-tenant object owned by the sharding bundle (one per tenant, held in a map) and make the `*Dao` classes impossible for clients to instantiate or subclass.

**Architecture:** `ShardCalculator` and `BucketIdExtractor` lose their tenant parameters and hold a single `ShardManager`. `MultiTenantDBShardingBundleBase` builds `Map<String, ShardCalculator<String>>` once per tenant in `run()` and passes it to DAOs, which no longer construct calculators. A `DaoFactory` enum singleton in the `dao` package becomes the only construction path; DAO constructors go package private and the classes become `sealed`/`final`. The jar is sealed so no outside class can be planted in the DAO packages.

**Tech Stack:** Java 17, Maven, Dropwizard 2.x, Hibernate 5, JUnit 5, H2 (in-memory, for tests), Lombok.

**Spec:** `docs/superpowers/specs/2026-09-10-shard-calculator-and-dao-lockdown-design.md`

---

## Background for the implementer

This is a library (`io.appform:db-sharding-bundle`) that shards Hibernate entities across several databases. Key concepts:

- **Tenant / namespace**: one logical database group. Single-tenant apps use the constant `DBShardingBundleBase.DEFAULT_NAMESPACE` (value `"default"`). Almost everything in the codebase is keyed by tenant id.
- **`ShardManager`**: maps a *bucket id* (0..N) to a *shard index*. Implementations: `BalancedShardManager`, `LegacyShardManager`. It also tracks blacklisted shards.
- **`BucketIdExtractor`**: hashes a sharding key (a `String`) into a bucket id. Only implementation: `ConsistentHashBucketIdExtractor`.
- **`ShardCalculator`**: composes the two — key to bucket to shard index.
- **DAO layering**: `MultiTenantLookupDao` / `MultiTenantRelationalDao` do the real work and take a `tenantId` on every method. `LookupDao` / `RelationalDao` are thin single-tenant wrappers holding a namespace plus a delegate. `MultiTenantCacheableLookupDao extends MultiTenantLookupDao`, and `CacheableLookupDao extends LookupDao` (same shape for the relational pair). `WrapperDao` is a separate single-tenant cglib-proxying DAO.

**Build commands** (run from repo root):

- Compile main only: `mvn -q -DskipTests compile`
- Full test suite: `mvn -q test`
- Single test class: `mvn -q test -Dtest=ShardCalculatorTest`
- Package (needed for manifest checks): `mvn -q -DskipTests package`

The full suite spins up in-memory H2 databases and takes a few minutes. Run the targeted test during a task, and the full suite before each commit that touches DAO or bundle code.

## File structure

**Created:**

- `src/main/java/io/appform/dropwizard/sharding/dao/DaoFactory.java` — the only public construction path for every DAO type.
- `src/test/java/io/appform/dropwizard/sharding/testutils/ShardCalculators.java` — test helper that builds calculator maps from a `ShardManager`.
- `src/test/java/io/appform/dropwizard/sharding/utils/ShardCalculatorTest.java` — unit tests for the single-tenant calculator.
- `src/test/java/io/appform/dropwizard/sharding/dao/DaoFactoryTest.java` — factory wiring tests.
- `src/test/java/io/appform/dropwizard/sharding/dao/DaoEncapsulationTest.java` — reflection tests asserting constructors are non-public and classes are sealed/final.

**Modified (main):**

- `pom.xml` — Java 17 release target, jar sealing, version bump.
- `src/main/java/io/appform/dropwizard/sharding/utils/ShardCalculator.java`
- `src/main/java/io/appform/dropwizard/sharding/sharding/BucketIdExtractor.java`
- `src/main/java/io/appform/dropwizard/sharding/sharding/impl/ConsistentHashBucketIdExtractor.java`
- `src/main/java/io/appform/dropwizard/sharding/sharding/BucketResolver.java`
- `src/main/java/io/appform/dropwizard/sharding/observers/bucket/BucketKeyPersistor.java`
- `src/main/java/io/appform/dropwizard/sharding/BundleCommonBase.java`
- `src/main/java/io/appform/dropwizard/sharding/MultiTenantDBShardingBundleBase.java`
- `src/main/java/io/appform/dropwizard/sharding/DBShardingBundleBase.java`
- `src/main/java/io/appform/dropwizard/sharding/dao/MultiTenantLookupDao.java`
- `src/main/java/io/appform/dropwizard/sharding/dao/MultiTenantRelationalDao.java`
- `src/main/java/io/appform/dropwizard/sharding/dao/MultiTenantCacheableLookupDao.java`
- `src/main/java/io/appform/dropwizard/sharding/dao/MultiTenantCacheableRelationalDao.java`
- `src/main/java/io/appform/dropwizard/sharding/dao/LookupDao.java`
- `src/main/java/io/appform/dropwizard/sharding/dao/RelationalDao.java`
- `src/main/java/io/appform/dropwizard/sharding/dao/CacheableLookupDao.java`
- `src/main/java/io/appform/dropwizard/sharding/dao/CacheableRelationalDao.java`
- `src/main/java/io/appform/dropwizard/sharding/dao/WrapperDao.java`
- `README.md`
- `CHANGELOG.md`

**Deleted:**

- `src/main/java/io/appform/dropwizard/sharding/dao/ShardedDao.java`

**Modified (test) — the 14 files that construct DAOs directly:**

`ScrollTest`, `dao/MultiTenantCacheableLookupDaoTest`, `dao/MultiTenantRelationalReadOnlyLockedContextTest`, `dao/CacheableLookupDaoTest`, `dao/locktest/ParentChildTest`, `dao/locktest/LockTest`, `dao/WrapperDaoTransactionReuseTest`, `dao/LookupDaoTest`, `dao/MultiTenantLookupDaoTest`, `dao/MultiTenantRelationalDaoTest`, `dao/RelationalReadOnlyLockedContextTest`, `dao/RelationalDaoTest`, `dao/WrapperDaoTest`, `dao/EncryptionAtRestTest`, plus `sharding/impl/ConsistentHashBucketIdExtractorTest`.

---

### Task 1: Move the build to a Java 17 release target

`sealed` classes require a Java 17 class-file target. The compiler plugin currently sets `source`/`target` to 17 but `release` to 11, and `release` wins — so `sealed` is rejected today.

**Files:**
- Modify: `pom.xml:223-231` (the `maven-compiler-plugin` block inside `<pluginManagement>`)

- [ ] **Step 1: Confirm the current setting**

Run: `grep -n "release>" pom.xml`
Expected: `228:                        <release>11</release>`

- [ ] **Step 2: Change the release target**

In `pom.xml`, inside the `maven-compiler-plugin` configuration, replace:

```xml
                        <source>17</source>
                        <target>17</target>
                        <release>11</release>
```

with:

```xml
                        <release>17</release>
```

(`release` supersedes `source`/`target`; keeping all three invites the exact confusion that made `sealed` unavailable.)

- [ ] **Step 3: Verify the project still builds**

Run: `mvn -q -DskipTests compile`
Expected: BUILD SUCCESS, no output on stdout.

- [ ] **Step 4: Verify the class files are version 61 (Java 17)**

Run: `javap -verbose -cp target/classes io.appform.dropwizard.sharding.utils.ShardCalculator | grep major`
Expected: `major version: 61`

- [ ] **Step 5: Commit**

```bash
git add pom.xml
git commit -m "build: target Java 17 release to enable sealed classes"
```

---

### Task 2: Add the `DaoFactory` enum singleton

Introduced first, delegating to the existing public constructors, so every call site can migrate to it *before* any signature changes. That way the later `ShardCalculator` flip touches the factory rather than 60+ scattered `new` expressions.

**Files:**
- Create: `src/main/java/io/appform/dropwizard/sharding/dao/DaoFactory.java`
- Test: `src/test/java/io/appform/dropwizard/sharding/dao/DaoFactoryTest.java`

- [ ] **Step 1: Check the test entity's lookup key**

Run: `grep -n "LookupKey" -A 2 src/test/java/io/appform/dropwizard/sharding/dao/testdata/entities/Order.java`
Expected: the annotation sits on `customerId`. If it is on another field, use that field in the test below.

- [ ] **Step 2: Write the failing test**

Create `src/test/java/io/appform/dropwizard/sharding/dao/DaoFactoryTest.java`:

```java
package io.appform.dropwizard.sharding.dao;

import com.google.common.collect.Lists;
import io.appform.dropwizard.sharding.DBShardingBundleBase;
import io.appform.dropwizard.sharding.ShardInfoProvider;
import io.appform.dropwizard.sharding.config.ShardingBundleOptions;
import io.appform.dropwizard.sharding.dao.testdata.OrderDao;
import io.appform.dropwizard.sharding.dao.testdata.entities.Order;
import io.appform.dropwizard.sharding.dao.testdata.entities.OrderItem;
import io.appform.dropwizard.sharding.observers.internal.TerminalTransactionObserver;
import io.appform.dropwizard.sharding.sharding.BalancedShardManager;
import io.appform.dropwizard.sharding.sharding.ShardManager;
import org.hibernate.SessionFactory;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class DaoFactoryTest {

    private static final String NS = DBShardingBundleBase.DEFAULT_NAMESPACE;

    private final List<SessionFactory> sessionFactories = Lists.newArrayList();
    private ShardManager shardManager;

    private SessionFactory buildSessionFactory(final String dbName) {
        final Configuration configuration = new Configuration();
        configuration.setProperty("hibernate.dialect", "org.hibernate.dialect.H2Dialect");
        configuration.setProperty("hibernate.connection.driver_class", "org.h2.Driver");
        configuration.setProperty("hibernate.connection.url", "jdbc:h2:mem:" + dbName);
        configuration.setProperty("hibernate.hbm2ddl.auto", "create");
        configuration.setProperty("hibernate.current_session_context_class", "managed");
        configuration.addAnnotatedClass(Order.class);
        configuration.addAnnotatedClass(OrderItem.class);
        return configuration.buildSessionFactory(
                new StandardServiceRegistryBuilder().applySettings(configuration.getProperties()).build());
    }

    @BeforeEach
    void before() {
        for (int i = 0; i < 2; i++) {
            sessionFactories.add(buildSessionFactory(String.format("factory_db_%d", i)));
        }
        shardManager = new BalancedShardManager(sessionFactories.size());
    }

    @AfterEach
    void after() {
        sessionFactories.forEach(SessionFactory::close);
    }

    @Test
    void createsWorkingMultiTenantLookupDao() throws Exception {
        final MultiTenantLookupDao<Order> dao = DaoFactory.INSTANCE.createMultiTenantLookupDao(
                Map.of(NS, sessionFactories),
                Order.class,
                Map.of(NS, shardManager),
                Map.of(NS, new ShardingBundleOptions()),
                Map.of(NS, new ShardInfoProvider(NS)),
                new TerminalTransactionObserver());
        assertNotNull(dao);
        dao.save(NS, Order.builder().customerId("customer-1").build());
        assertEquals("customer-1", dao.get(NS, "customer-1").orElseThrow().getCustomerId());
    }

    @Test
    void createsWorkingSingleTenantLookupDao() throws Exception {
        final LookupDao<Order> dao = DaoFactory.INSTANCE.createLookupDao(
                NS,
                DaoFactory.INSTANCE.createMultiTenantLookupDao(
                        Map.of(NS, sessionFactories),
                        Order.class,
                        Map.of(NS, shardManager),
                        Map.of(NS, new ShardingBundleOptions()),
                        Map.of(NS, new ShardInfoProvider(NS)),
                        new TerminalTransactionObserver()));
        dao.save(Order.builder().customerId("customer-2").build());
        assertEquals("customer-2", dao.get("customer-2").orElseThrow().getCustomerId());
    }

    @Test
    void createsWorkingWrapperDao() {
        final WrapperDao<Order, OrderDao> dao = DaoFactory.INSTANCE.createWrapperDao(
                NS, sessionFactories, OrderDao.class, shardManager);
        assertNotNull(dao.forParent("customer-3"));
    }
}
```

- [ ] **Step 3: Run the test to verify it fails**

Run: `mvn -q test -Dtest=DaoFactoryTest`
Expected: COMPILATION ERROR — `cannot find symbol: variable DaoFactory`.

- [ ] **Step 4: Create the factory**

Create `src/main/java/io/appform/dropwizard/sharding/dao/DaoFactory.java`:

```java
package io.appform.dropwizard.sharding.dao;

import io.appform.dropwizard.sharding.ShardInfoProvider;
import io.appform.dropwizard.sharding.caching.LookupCache;
import io.appform.dropwizard.sharding.caching.RelationalCache;
import io.appform.dropwizard.sharding.config.ShardingBundleOptions;
import io.appform.dropwizard.sharding.observers.TransactionObserver;
import io.appform.dropwizard.sharding.sharding.ShardManager;
import io.dropwizard.hibernate.AbstractDAO;
import org.hibernate.SessionFactory;

import java.util.List;
import java.util.Map;

/**
 * The only supported way to build the DAOs in this package.
 * <p>
 * This is an enum so it cannot be instantiated by client code, and its methods take
 * bundle-internal collaborators that clients cannot obtain. DAO constructors are package
 * private, so this factory is the sole construction path.
 */
public enum DaoFactory {
    INSTANCE;

    public <T> MultiTenantLookupDao<T> createMultiTenantLookupDao(
            final Map<String, List<SessionFactory>> sessionFactories,
            final Class<T> entityClass,
            final Map<String, ShardManager> shardManagers,
            final Map<String, ShardingBundleOptions> shardingOptions,
            final Map<String, ShardInfoProvider> shardInfoProviders,
            final TransactionObserver observer) {
        return new MultiTenantLookupDao<>(sessionFactories, entityClass, shardManagers,
                shardingOptions, shardInfoProviders, observer);
    }

    public <T> MultiTenantCacheableLookupDao<T> createMultiTenantCacheableLookupDao(
            final Map<String, List<SessionFactory>> sessionFactories,
            final Class<T> entityClass,
            final Map<String, ShardManager> shardManagers,
            final Map<String, LookupCache<T>> cache,
            final Map<String, ShardingBundleOptions> shardingOptions,
            final Map<String, ShardInfoProvider> shardInfoProviders,
            final TransactionObserver observer) {
        return new MultiTenantCacheableLookupDao<>(sessionFactories, entityClass, shardManagers,
                cache, shardingOptions, shardInfoProviders, observer);
    }

    public <T> MultiTenantRelationalDao<T> createMultiTenantRelationalDao(
            final Map<String, List<SessionFactory>> sessionFactories,
            final Class<T> entityClass,
            final Map<String, ShardManager> shardManagers,
            final Map<String, ShardingBundleOptions> shardingOptions,
            final Map<String, ShardInfoProvider> shardInfoProviders,
            final TransactionObserver observer) {
        return new MultiTenantRelationalDao<>(sessionFactories, entityClass, shardManagers,
                shardingOptions, shardInfoProviders, observer);
    }

    public <T> MultiTenantCacheableRelationalDao<T> createMultiTenantCacheableRelationalDao(
            final Map<String, List<SessionFactory>> sessionFactories,
            final Class<T> entityClass,
            final Map<String, ShardManager> shardManagers,
            final Map<String, RelationalCache<T>> cache,
            final Map<String, ShardingBundleOptions> shardingOptions,
            final Map<String, ShardInfoProvider> shardInfoProviders,
            final TransactionObserver observer) {
        return new MultiTenantCacheableRelationalDao<>(sessionFactories, entityClass, shardManagers,
                cache, shardingOptions, shardInfoProviders, observer);
    }

    public <T> LookupDao<T> createLookupDao(final String tenantId,
                                            final MultiTenantLookupDao<T> delegate) {
        return new LookupDao<>(tenantId, delegate);
    }

    public <T> CacheableLookupDao<T> createCacheableLookupDao(
            final String tenantId,
            final MultiTenantCacheableLookupDao<T> delegate) {
        return new CacheableLookupDao<>(tenantId, delegate);
    }

    public <T> RelationalDao<T> createRelationalDao(final String tenantId,
                                                    final MultiTenantRelationalDao<T> delegate) {
        return new RelationalDao<>(tenantId, delegate);
    }

    public <T> CacheableRelationalDao<T> createCacheableRelationalDao(
            final String tenantId,
            final MultiTenantCacheableRelationalDao<T> delegate) {
        return new CacheableRelationalDao<>(tenantId, delegate);
    }

    public <T, DaoType extends AbstractDAO<T>> WrapperDao<T, DaoType> createWrapperDao(
            final String tenantId,
            final List<SessionFactory> sessionFactories,
            final Class<DaoType> daoClass,
            final ShardManager shardManager) {
        return new WrapperDao<>(tenantId, sessionFactories, daoClass, shardManager);
    }

    public <T, DaoType extends AbstractDAO<T>> WrapperDao<T, DaoType> createWrapperDao(
            final String tenantId,
            final List<SessionFactory> sessionFactories,
            final Class<DaoType> daoClass,
            final Class[] extraConstructorParamClasses,
            final Class[] extraConstructorParamObjects,
            final ShardManager shardManager) {
        return new WrapperDao<>(tenantId, sessionFactories, daoClass, extraConstructorParamClasses,
                extraConstructorParamObjects, shardManager);
    }
}
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `mvn -q test -Dtest=DaoFactoryTest`
Expected: PASS, 3 tests.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/io/appform/dropwizard/sharding/dao/DaoFactory.java src/test/java/io/appform/dropwizard/sharding/dao/DaoFactoryTest.java
git commit -m "feat: add DaoFactory as the single construction path for DAOs"
```

---

### Task 3: Route the bundles through `DaoFactory`

**Files:**
- Modify: `src/main/java/io/appform/dropwizard/sharding/MultiTenantDBShardingBundleBase.java:211-271`
- Modify: `src/main/java/io/appform/dropwizard/sharding/DBShardingBundleBase.java:174-201`

- [ ] **Step 1: Replace the `new` calls in the multi-tenant bundle**

In `MultiTenantDBShardingBundleBase.java`, replace the bodies of the four entity-DAO methods:

```java
  public <EntityType, T extends Configuration>
  MultiTenantLookupDao<EntityType> createParentObjectDao(Class<EntityType> clazz) {
    return DaoFactory.INSTANCE.createMultiTenantLookupDao(this.sessionFactories, clazz,
        this.shardManagers, this.shardingOptions, shardInfoProviders, rootObserver);
  }

  public <EntityType, T extends Configuration>
  MultiTenantCacheableLookupDao<EntityType> createParentObjectDao(Class<EntityType> clazz,
      Map<String, LookupCache<EntityType>> cacheManager) {
    return DaoFactory.INSTANCE.createMultiTenantCacheableLookupDao(this.sessionFactories, clazz,
        this.shardManagers, cacheManager, this.shardingOptions, shardInfoProviders, rootObserver);
  }

  public <EntityType, T extends Configuration>
  MultiTenantRelationalDao<EntityType> createRelatedObjectDao(Class<EntityType> clazz) {
    return DaoFactory.INSTANCE.createMultiTenantRelationalDao(this.sessionFactories, clazz,
        this.shardManagers, this.shardingOptions, shardInfoProviders, rootObserver);
  }

  public <EntityType, T extends Configuration>
  MultiTenantCacheableRelationalDao<EntityType> createRelatedObjectDao(Class<EntityType> clazz,
      Map<String, RelationalCache<EntityType>> cacheManager) {
    return DaoFactory.INSTANCE.createMultiTenantCacheableRelationalDao(this.sessionFactories, clazz,
        this.shardManagers, cacheManager, this.shardingOptions, shardInfoProviders, rootObserver);
  }
```

and the return statements of both `createWrapperDao` overloads (keep their existing `Preconditions.checkArgument` guards exactly as they are):

```java
    return DaoFactory.INSTANCE.createWrapperDao(tenantId, this.sessionFactories.get(tenantId),
        daoTypeClass, this.shardManagers.get(tenantId));
```

```java
    return DaoFactory.INSTANCE.createWrapperDao(tenantId, this.sessionFactories.get(tenantId),
        daoTypeClass, extraConstructorParamClasses, extraConstructorParamObjects,
        this.shardManagers.get(tenantId));
```

Add the import `io.appform.dropwizard.sharding.dao.DaoFactory`.

- [ ] **Step 2: Replace the `new` calls in the single-tenant bundle**

In `DBShardingBundleBase.java`, replace the four wrapper-creating methods:

```java
    public <EntityType, T extends Configuration>
    LookupDao<EntityType> createParentObjectDao(Class<EntityType> clazz) {
        return DaoFactory.INSTANCE.createLookupDao(dbNamespace, delegate.createParentObjectDao(clazz));
    }

    public <EntityType, T extends Configuration>
    CacheableLookupDao<EntityType> createParentObjectDao(
            Class<EntityType> clazz,
            LookupCache<EntityType> cacheManager) {
        return DaoFactory.INSTANCE.createCacheableLookupDao(dbNamespace,
                delegate.createParentObjectDao(clazz, Map.of(dbNamespace, cacheManager)));
    }

    public <EntityType, T extends Configuration>
    RelationalDao<EntityType> createRelatedObjectDao(Class<EntityType> clazz) {
        return DaoFactory.INSTANCE.createRelationalDao(dbNamespace, delegate.createRelatedObjectDao(clazz));
    }

    public <EntityType, T extends Configuration>
    CacheableRelationalDao<EntityType> createRelatedObjectDao(
            Class<EntityType> clazz,
            RelationalCache<EntityType> cacheManager) {
        return DaoFactory.INSTANCE.createCacheableRelationalDao(dbNamespace,
                delegate.createRelatedObjectDao(clazz, Map.of(dbNamespace, cacheManager)));
    }
```

Add the import `io.appform.dropwizard.sharding.dao.DaoFactory`.

- [ ] **Step 3: Verify no production `new …Dao<>` calls remain**

Run: `grep -rn "new MultiTenantLookupDao\|new MultiTenantRelationalDao\|new MultiTenantCacheable\|new LookupDao<\|new RelationalDao<\|new CacheableLookupDao\|new CacheableRelationalDao\|new WrapperDao" --include=*.java src/main`
Expected: only matches inside `DaoFactory.java`.

- [ ] **Step 4: Run the bundle tests**

Run: `mvn -q test -Dtest='*DBShardingBundle*Test'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/appform/dropwizard/sharding/MultiTenantDBShardingBundleBase.java src/main/java/io/appform/dropwizard/sharding/DBShardingBundleBase.java
git commit -m "refactor: build DAOs through DaoFactory in both bundles"
```

---

### Task 4: Route the tests through `DaoFactory`

Purely mechanical: every `new X<>(args)` becomes `DaoFactory.INSTANCE.createX(args)` with identical arguments. Doing this now means Task 5's signature change edits argument lists that are already in one shape.

**Files (all under `src/test/java/io/appform/dropwizard/sharding/`):**
- Modify: `ScrollTest.java:57`
- Modify: `dao/MultiTenantCacheableLookupDaoTest.java:99,142,185,313`
- Modify: `dao/MultiTenantRelationalReadOnlyLockedContextTest.java:89,92,95`
- Modify: `dao/CacheableLookupDaoTest.java:93,120,147,215`
- Modify: `dao/locktest/ParentChildTest.java:52`
- Modify: `dao/locktest/LockTest.java:104,110`
- Modify: `dao/WrapperDaoTransactionReuseTest.java:62`
- Modify: `dao/LookupDaoTest.java:100,106,112,118,124`
- Modify: `dao/MultiTenantLookupDaoTest.java:113,117,122,125,129`
- Modify: `dao/MultiTenantRelationalDaoTest.java:108,111`
- Modify: `dao/RelationalReadOnlyLockedContextTest.java:80,86,92`
- Modify: `dao/RelationalDaoTest.java:99,105`
- Modify: `dao/WrapperDaoTest.java:69`
- Modify: `dao/EncryptionAtRestTest.java:72`

- [ ] **Step 1: Apply this mapping in every listed file**

| Old expression | New expression |
| --- | --- |
| `new MultiTenantLookupDao<>(a, b, c, d, e, f)` | `DaoFactory.INSTANCE.createMultiTenantLookupDao(a, b, c, d, e, f)` |
| `new MultiTenantCacheableLookupDao<>(a, b, c, d, e, f, g)` | `DaoFactory.INSTANCE.createMultiTenantCacheableLookupDao(a, b, c, d, e, f, g)` |
| `new MultiTenantRelationalDao<>(a, b, c, d, e, f)` | `DaoFactory.INSTANCE.createMultiTenantRelationalDao(a, b, c, d, e, f)` |
| `new MultiTenantCacheableRelationalDao<>(a, b, c, d, e, f, g)` | `DaoFactory.INSTANCE.createMultiTenantCacheableRelationalDao(a, b, c, d, e, f, g)` |
| `new LookupDao<>(ns, delegate)` | `DaoFactory.INSTANCE.createLookupDao(ns, delegate)` |
| `new CacheableLookupDao<>(ns, delegate)` | `DaoFactory.INSTANCE.createCacheableLookupDao(ns, delegate)` |
| `new RelationalDao<>(ns, delegate)` | `DaoFactory.INSTANCE.createRelationalDao(ns, delegate)` |
| `new CacheableRelationalDao<>(ns, delegate)` | `DaoFactory.INSTANCE.createCacheableRelationalDao(ns, delegate)` |
| `new WrapperDao<>(ns, sfs, daoClass, shardManager)` | `DaoFactory.INSTANCE.createWrapperDao(ns, sfs, daoClass, shardManager)` |

Concrete example — `ScrollTest.java:57-61` becomes:

```java
        lookupDao = DaoFactory.INSTANCE.createLookupDao(DBShardingBundleBase.DEFAULT_NAMESPACE,
                DaoFactory.INSTANCE.createMultiTenantLookupDao(
                        Map.of(DBShardingBundleBase.DEFAULT_NAMESPACE, sessionFactories),
                        ScrollTestEntity.class, Map.of(DBShardingBundleBase.DEFAULT_NAMESPACE, shardManager),
                        Map.of(DBShardingBundleBase.DEFAULT_NAMESPACE, shardingOptions),
                        Map.of(DBShardingBundleBase.DEFAULT_NAMESPACE, shardInfoProvider), observer));
```

Files outside the `io.appform.dropwizard.sharding.dao` package (`ScrollTest`, `dao/locktest/ParentChildTest`, `dao/locktest/LockTest`) need `import io.appform.dropwizard.sharding.dao.DaoFactory;`. Files already in the `dao` package need no import.

Generics note: the factory infers `T` from the `Class<T>` argument, so no type witness is normally needed. If a nested call fails inference, add one: `DaoFactory.INSTANCE.<TestEntity>createMultiTenantLookupDao(…)`.

- [ ] **Step 2: Verify no test constructs a DAO directly**

Run: `grep -rn "new MultiTenantLookupDao\|new MultiTenantRelationalDao\|new MultiTenantCacheable\|new LookupDao<\|new RelationalDao<\|new CacheableLookupDao\|new CacheableRelationalDao\|new WrapperDao" --include=*.java src/test`
Expected: no output.

- [ ] **Step 3: Run the full suite**

Run: `mvn -q test`
Expected: BUILD SUCCESS, same test count as before this task.

- [ ] **Step 4: Commit**

```bash
git add src/test
git commit -m "test: construct DAOs through DaoFactory"
```

---

### Task 5: Make `ShardCalculator` and `BucketIdExtractor` single-tenant

The core change. The extractor and the calculator must flip together — one extractor cannot serve multiple tenants once it holds a single `ShardManager` — so this lands as one compiling commit. Work through the steps in order; the code will not compile until Step 15 is done.

**Files:**
- Create: `src/test/java/io/appform/dropwizard/sharding/utils/ShardCalculatorTest.java`
- Create: `src/test/java/io/appform/dropwizard/sharding/testutils/ShardCalculators.java`
- Modify: `src/main/java/io/appform/dropwizard/sharding/sharding/BucketIdExtractor.java`
- Modify: `src/main/java/io/appform/dropwizard/sharding/sharding/impl/ConsistentHashBucketIdExtractor.java`
- Modify: `src/main/java/io/appform/dropwizard/sharding/utils/ShardCalculator.java`
- Modify: `src/main/java/io/appform/dropwizard/sharding/sharding/BucketResolver.java`
- Modify: `src/main/java/io/appform/dropwizard/sharding/observers/bucket/BucketKeyPersistor.java`
- Modify: `src/main/java/io/appform/dropwizard/sharding/BundleCommonBase.java:130-132`
- Modify: `src/main/java/io/appform/dropwizard/sharding/MultiTenantDBShardingBundleBase.java`
- Modify: `src/main/java/io/appform/dropwizard/sharding/dao/MultiTenantLookupDao.java`
- Modify: `src/main/java/io/appform/dropwizard/sharding/dao/MultiTenantRelationalDao.java`
- Modify: `src/main/java/io/appform/dropwizard/sharding/dao/MultiTenantCacheableLookupDao.java`
- Modify: `src/main/java/io/appform/dropwizard/sharding/dao/MultiTenantCacheableRelationalDao.java`
- Modify: `src/main/java/io/appform/dropwizard/sharding/dao/WrapperDao.java`
- Modify: `src/main/java/io/appform/dropwizard/sharding/dao/DaoFactory.java`
- Modify: `src/test/java/io/appform/dropwizard/sharding/sharding/impl/ConsistentHashBucketIdExtractorTest.java`
- Modify: the 14 test files from Task 4

- [ ] **Step 1: Check the shard manager APIs the new test relies on**

Run: `grep -n "public BalancedShardManager\|public void blacklist\|isMappedToValidShard\|shardForBucket\|numBuckets" src/main/java/io/appform/dropwizard/sharding/sharding/BalancedShardManager.java src/main/java/io/appform/dropwizard/sharding/sharding/ShardManager.java src/main/java/io/appform/dropwizard/sharding/sharding/InMemoryLocalShardBlacklistingStore.java`
Expected: a `BalancedShardManager(int)` constructor, `shardForBucket(int)`, `isMappedToValidShard(int)` and `numBuckets()`. Note whether a `(int, ShardBlacklistingStore)` constructor and a `blacklist(int)` method exist — if either is missing, skip the third test in the next step.

- [ ] **Step 2: Write the failing test for the new calculator**

Create `src/test/java/io/appform/dropwizard/sharding/utils/ShardCalculatorTest.java`:

```java
package io.appform.dropwizard.sharding.utils;

import io.appform.dropwizard.sharding.sharding.BalancedShardManager;
import io.appform.dropwizard.sharding.sharding.InMemoryLocalShardBlacklistingStore;
import io.appform.dropwizard.sharding.sharding.ShardManager;
import io.appform.dropwizard.sharding.sharding.impl.ConsistentHashBucketIdExtractor;
import org.junit.jupiter.api.Test;

import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShardCalculatorTest {

    @Test
    void shardIdIsWithinShardCountAndStable() {
        final ShardManager shardManager = new BalancedShardManager(8);
        final ShardCalculator<String> calculator =
                new ShardCalculator<>(shardManager, new ConsistentHashBucketIdExtractor<>(shardManager));

        final int first = calculator.shardId("customer-1");
        assertTrue(first >= 0 && first < 8);
        assertEquals(first, calculator.shardId("customer-1"));
    }

    @Test
    void keysAreDistributedAcrossShards() {
        final ShardManager shardManager = new BalancedShardManager(4);
        final ShardCalculator<String> calculator =
                new ShardCalculator<>(shardManager, new ConsistentHashBucketIdExtractor<>(shardManager));

        final long distinctShards = IntStream.range(0, 200)
                .mapToObj(i -> "customer-" + i)
                .map(calculator::shardId)
                .distinct()
                .count();
        assertTrue(distinctShards > 1, "expected keys to spread over more than one shard");
    }

    @Test
    void blacklistedShardIsReportedAsInvalid() {
        final InMemoryLocalShardBlacklistingStore blacklistingStore = new InMemoryLocalShardBlacklistingStore();
        final ShardManager shardManager = new BalancedShardManager(4, blacklistingStore);
        final ShardCalculator<String> calculator =
                new ShardCalculator<>(shardManager, new ConsistentHashBucketIdExtractor<>(shardManager));

        final String key = "customer-1";
        assertTrue(calculator.isOnValidShard(key));

        blacklistingStore.blacklist(calculator.shardId(key));
        assertFalse(calculator.isOnValidShard(key));
    }
}
```

- [ ] **Step 3: Run it to verify it fails**

Run: `mvn -q test -Dtest=ShardCalculatorTest`
Expected: COMPILATION ERROR — no `ShardCalculator(ShardManager, BucketIdExtractor)` constructor and no `shardId(String)` method.

- [ ] **Step 4: Flip the `BucketIdExtractor` interface**

`src/main/java/io/appform/dropwizard/sharding/sharding/BucketIdExtractor.java`:

```java
package io.appform.dropwizard.sharding.sharding;

/**
 * Extracts bucket id from key. One instance serves exactly one tenant.
 */
public interface BucketIdExtractor<T> {
    int bucketId(T id);
}
```

- [ ] **Step 5: Flip `ConsistentHashBucketIdExtractor`**

In `src/main/java/io/appform/dropwizard/sharding/sharding/impl/ConsistentHashBucketIdExtractor.java`, keep the licence header and replace the class body:

```java
public class ConsistentHashBucketIdExtractor<T> implements BucketIdExtractor<T> {

    private final ShardManager shardManager;

    public ConsistentHashBucketIdExtractor(ShardManager shardManager) {
        this.shardManager = shardManager;
    }

    @Override
    public int bucketId(T id) {
        int hashKey = Hashing.murmur3_128().hashString(id.toString(), StandardCharsets.UTF_8).asInt();
        hashKey *= hashKey < 0 ? -1 : 1;
        return hashKey % shardManager.numBuckets();
    }
}
```

Remove the now-unused `java.util.Map` import. The hashing arithmetic is untouched, so bucket ids stay identical to today's — that is what keeps existing rows on the same shards.

- [ ] **Step 6: Flip `ShardCalculator`**

In `src/main/java/io/appform/dropwizard/sharding/utils/ShardCalculator.java`, keep the licence header and replace everything below it:

```java
package io.appform.dropwizard.sharding.utils;

import io.appform.dropwizard.sharding.sharding.BucketIdExtractor;
import io.appform.dropwizard.sharding.sharding.ShardManager;
import lombok.extern.slf4j.Slf4j;

/**
 * Calculates the shard for a key. One instance serves exactly one tenant.
 */
@Slf4j
public class ShardCalculator<T> {

    private final ShardManager shardManager;
    private final BucketIdExtractor<T> extractor;

    public ShardCalculator(ShardManager shardManager, BucketIdExtractor<T> extractor) {
        this.shardManager = shardManager;
        this.extractor = extractor;
    }

    public int shardId(T key) {
        return shardManager.shardForBucket(extractor.bucketId(key));
    }

    public boolean isOnValidShard(T key) {
        return shardManager.isMappedToValidShard(extractor.bucketId(key));
    }
}
```

The `DBShardingBundleBase` and `java.util.Map` imports disappear, which also removes the `utils` to bundle package cycle.

- [ ] **Step 7: Give `BucketResolver` a per-tenant extractor map**

In `src/main/java/io/appform/dropwizard/sharding/sharding/BucketResolver.java`, replace the field and constructor:

```java
    private final Map<String, BucketIdExtractor<T>> bucketIdExtractors;
    private final Map<String, EntityMeta> initialisedEntitiesMeta;

    public BucketResolver(final Map<String, BucketIdExtractor<T>> bucketIdExtractors,
                          final Map<String, EntityMeta> initialisedEntitiesMeta) {
        Preconditions.checkArgument(initialisedEntitiesMeta != null, "initialisedEntitiesMeta must not be null");
        Preconditions.checkArgument(bucketIdExtractors != null, "BucketIdExtractors must not be null");
        this.bucketIdExtractors = bucketIdExtractors;
        this.initialisedEntitiesMeta = initialisedEntitiesMeta;
    }
```

and inside `getBucketInfo`, replace `final var bucketId = bucketIdExtractor.bucketId(tenantId, shardingKey);` with:

```java
        final var bucketIdExtractor = bucketIdExtractors.get(tenantId);
        if (bucketIdExtractor == null) {
            throw new IllegalArgumentException("Unknown tenant: " + tenantId);
        }
        final var bucketId = bucketIdExtractor.bucketId(shardingKey);
```

The early `null` returns for missing entity metadata stay exactly as they are.

- [ ] **Step 8: Make `BucketKeyPersistor` single-tenant**

In `src/main/java/io/appform/dropwizard/sharding/observers/bucket/BucketKeyPersistor.java`, drop the now-unused `tenantId` field and parameter:

```java
    private final BucketIdExtractor<String> bucketIdExtractor;
    private final Map<String, EntityMeta> initialisedEntitiesMeta;

    public BucketKeyPersistor(final BucketIdExtractor<String> bucketIdExtractor,
                              final Map<String, EntityMeta> initialisedEntitiesMeta) {
        Preconditions.checkArgument(bucketIdExtractor != null, "BucketIdExtractor must not be null");
        this.bucketIdExtractor = bucketIdExtractor;
        this.initialisedEntitiesMeta = initialisedEntitiesMeta;
    }
```

At line 278 replace `this.bucketIdExtractor.bucketId(this.tenantId, shardingKey)` with `this.bucketIdExtractor.bucketId(shardingKey)`. Remove the `StringUtils` import if it becomes unused (`grep -n StringUtils` on the file).

- [ ] **Step 9: Update `BundleCommonBase.registerBucketIdExtractor`**

`src/main/java/io/appform/dropwizard/sharding/BundleCommonBase.java:130-132` becomes:

```java
  protected void registerBucketIdExtractor(final Map<String, ShardManager> shardManagers) {
    final Map<String, BucketIdExtractor<String>> extractors = shardManagers.entrySet().stream()
        .collect(Collectors.toMap(Map.Entry::getKey,
            entry -> new ConsistentHashBucketIdExtractor<>(entry.getValue())));
    this.bucketResolver = new BucketResolver<>(extractors, getInitialisedEntitiesMeta());
  }
```

Add imports `io.appform.dropwizard.sharding.sharding.BucketIdExtractor` and `java.util.stream.Collectors` if missing.

- [ ] **Step 10: Have the bundle own the calculators**

In `MultiTenantDBShardingBundleBase.java`, add a field next to `shardManagers`:

```java
  @Getter
  private Map<String, ShardCalculator<String>> shardCalculators = Maps.newHashMap();
```

Inside `run()`, immediately after `this.shardManagers.put(tenantId, shardManager);`, add:

```java
        this.shardCalculators.put(tenantId,
            new ShardCalculator<>(shardManager, new ConsistentHashBucketIdExtractor<>(shardManager)));
```

In `setupObservers`, change the `BucketKeyPersistor` construction to the new two-argument form with the tenant's own extractor:

```java
      rootObserver = new BucketKeyObserver(new BucketKeyPersistor(
              new ConsistentHashBucketIdExtractor<>(shardManagers.get(tenantId)),
              initialisedEntityMeta)).setNext(rootObserver);
```

Add the import `io.appform.dropwizard.sharding.utils.ShardCalculator`.

- [ ] **Step 11: Switch `MultiTenantLookupDao` to the calculator map**

In `MultiTenantLookupDao.java`:

- Change the constructor parameter `Map<String, ShardManager> shardManagers` to `Map<String, ShardCalculator<String>> shardCalculators`.
- Change the field to `private final Map<String, ShardCalculator<String>> shardCalculators;` (drop the `@Getter`), and replace line 148 with `this.shardCalculators = shardCalculators;`.
- Add the lookup helper:

```java
    private ShardCalculator<String> calculatorFor(final String tenantId) {
        final var calculator = shardCalculators.get(tenantId);
        if (calculator == null) {
            throw new IllegalArgumentException("Unknown tenant: " + tenantId);
        }
        return calculator;
    }
```

- Replace every `shardCalculator.shardId(tenantId, X)` with `calculatorFor(tenantId).shardId(X)` — lines 209, 225, 281, 299, 330, 351, 373, 440, 468, 504, 535, 804, 840, 889.
- Remove the `ShardManager` and `ConsistentHashBucketIdExtractor` imports if unused.
- Update the constructor javadoc: replace the `@param shardManagers` line with `@param shardCalculators A map of tenant id to that tenant's ShardCalculator.`

- [ ] **Step 12: Switch `MultiTenantRelationalDao` the same way**

Identical treatment in `MultiTenantRelationalDao.java`: constructor at 300-307, plus `shardCalculator.shardId(tenantId, X)` at lines 355, 386, 412, 425, 783, 830, 883, 930, 957, 981, 1008, 1031, 1141, 1186, 1242, 1289, 1309, 1320, 1351, 1604, 1647, 1696. Add the same `calculatorFor` helper.

- [ ] **Step 13: Update the two cacheable subclasses**

In `MultiTenantCacheableLookupDao.java:61-68` and `MultiTenantCacheableRelationalDao.java:61-68`, change the constructor parameter type from `Map<String, ShardManager> shardManagers` to `Map<String, ShardCalculator<String>> shardCalculators` and pass it to `super(...)` in the same position. Swap the `ShardManager` import for `io.appform.dropwizard.sharding.utils.ShardCalculator`.

- [ ] **Step 14: Switch `WrapperDao` to a single calculator**

In `WrapperDao.java`, replace the `ShardManager shardManager` parameter on both constructors with `ShardCalculator<String> shardCalculator` (forwarding it from the short constructor to the long one), replace lines 84-85 with `this.shardCalculator = shardCalculator;`, and change line 120 to:

```java
        return daos.get(shardCalculator.shardId(parentKey));
```

Remove the `ShardManager`, `ConsistentHashBucketIdExtractor` and `java.util.Map` imports if they become unused. Update the `@param shardManager` javadoc lines to `@param shardCalculator The tenant's ShardCalculator.`

- [ ] **Step 15: Update `DaoFactory` and the bundle call sites**

In `DaoFactory.java`, change every `Map<String, ShardManager> shardManagers` parameter to `Map<String, ShardCalculator<String>> shardCalculators` (the four multi-tenant methods) and both `ShardManager shardManager` parameters on `createWrapperDao` to `ShardCalculator<String> shardCalculator`, forwarding them unchanged. Swap the `ShardManager` import for `io.appform.dropwizard.sharding.utils.ShardCalculator`.

In `MultiTenantDBShardingBundleBase.java`, change `this.shardManagers` to `this.shardCalculators` in the four `create*ObjectDao` methods, and `this.shardManagers.get(tenantId)` to `this.shardCalculators.get(tenantId)` in both `createWrapperDao` methods. Change the `Preconditions.checkArgument` guards to check `this.shardCalculators.containsKey(tenantId)` instead of `this.shardManagers.containsKey(tenantId)`.

- [ ] **Step 16: Add the test helper**

Create `src/test/java/io/appform/dropwizard/sharding/testutils/ShardCalculators.java`:

```java
package io.appform.dropwizard.sharding.testutils;

import io.appform.dropwizard.sharding.sharding.ShardManager;
import io.appform.dropwizard.sharding.sharding.impl.ConsistentHashBucketIdExtractor;
import io.appform.dropwizard.sharding.utils.ShardCalculator;

import java.util.Map;
import java.util.stream.Collectors;

/**
 * Builds shard calculators the way the bundle does, for tests that wire DAOs by hand.
 */
public final class ShardCalculators {

    private ShardCalculators() {
    }

    public static ShardCalculator<String> calculator(final ShardManager shardManager) {
        return new ShardCalculator<>(shardManager, new ConsistentHashBucketIdExtractor<>(shardManager));
    }

    public static Map<String, ShardCalculator<String>> forTenant(final String tenantId,
                                                                 final ShardManager shardManager) {
        return Map.of(tenantId, calculator(shardManager));
    }

    public static Map<String, ShardCalculator<String>> forTenants(
            final Map<String, ShardManager> shardManagers) {
        return shardManagers.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, entry -> calculator(entry.getValue())));
    }
}
```

- [ ] **Step 17: Update the DAO tests to pass calculators**

In the 14 test files from Task 4, change the argument in the *shard manager* position of each factory call:

- Single-tenant tests (`ScrollTest`, `LookupDaoTest`, `RelationalDaoTest`, `CacheableLookupDaoTest`, `RelationalReadOnlyLockedContextTest`, `locktest/LockTest`, `locktest/ParentChildTest`, `EncryptionAtRestTest`): replace `Map.of(DBShardingBundleBase.DEFAULT_NAMESPACE, shardManager)` in that position with `ShardCalculators.forTenant(DBShardingBundleBase.DEFAULT_NAMESPACE, shardManager)`. Be careful — the same `Map.of(NS, …)` shape is used for session factories, sharding options and shard info providers; only the third argument changes.
- Multi-tenant tests (`MultiTenantLookupDaoTest`, `MultiTenantRelationalDaoTest`, `MultiTenantCacheableLookupDaoTest`, `MultiTenantRelationalReadOnlyLockedContextTest`) pass a pre-built map (the field is named `shardManager` even though it holds a map). Add a field and populate it in `@BeforeEach` right after that map is built:

```java
    private Map<String, ShardCalculator<String>> shardCalculators;
    ...
    shardCalculators = ShardCalculators.forTenants(shardManager);
```

then pass `shardCalculators` where `shardManager` used to go.
- `WrapperDaoTest:69` and `WrapperDaoTransactionReuseTest:62` pass a single calculator: `ShardCalculators.calculator(shardManager)`.
- Add `import io.appform.dropwizard.sharding.testutils.ShardCalculators;` (and `io.appform.dropwizard.sharding.utils.ShardCalculator` where a field is declared) to each modified file.

- [ ] **Step 18: Update the extractor test**

In `src/test/java/io/appform/dropwizard/sharding/sharding/impl/ConsistentHashBucketIdExtractorTest.java`, change both tests to:

```java
        ConsistentHashBucketIdExtractor<String> extractor = new ConsistentHashBucketIdExtractor<>(shardManager);
        var shardId = shardManager.shardForBucket(extractor.bucketId("MRT2509051351592369928055")) + 1;
```

and remove the `java.util.Map` import.

- [ ] **Step 19: Add the unknown-tenant tests**

Append to `src/test/java/io/appform/dropwizard/sharding/dao/DaoFactoryTest.java`:

```java
    @Test
    void unknownTenantIsRejectedWithANamedError() {
        final MultiTenantLookupDao<Order> dao = DaoFactory.INSTANCE.createMultiTenantLookupDao(
                Map.of(NS, sessionFactories),
                Order.class,
                io.appform.dropwizard.sharding.testutils.ShardCalculators.forTenant(NS, shardManager),
                Map.of(NS, new ShardingBundleOptions()),
                Map.of(NS, new ShardInfoProvider(NS)),
                new TerminalTransactionObserver());

        final IllegalArgumentException exception = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> dao.get("no-such-tenant", "customer-1"));
        org.junit.jupiter.api.Assertions.assertTrue(exception.getMessage().contains("no-such-tenant"),
                "message should name the tenant, was: " + exception.getMessage());
    }
```

Create `src/test/java/io/appform/dropwizard/sharding/sharding/BucketResolverUnknownTenantTest.java`:

```java
package io.appform.dropwizard.sharding.sharding;

import io.appform.dropwizard.sharding.sharding.impl.ConsistentHashBucketIdExtractor;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BucketResolverUnknownTenantTest {

    @Test
    void unknownTenantIsRejectedWithANamedError() {
        final ShardManager shardManager = new BalancedShardManager(4);
        final BucketResolver<String> resolver = new BucketResolver<>(
                Map.of("tenant1", new ConsistentHashBucketIdExtractor<>(shardManager)),
                Map.of("com.example.Entity",
                        EntityMeta.builder().bucketKeyColumnName("bucket_key").build()));

        final IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> resolver.getBucketInfo("no-such-tenant", "customer-1", getClass()));
        assertTrue(exception.getMessage().contains("no-such-tenant"));
    }
}
```

Check `EntityMeta`'s builder field names first with `grep -n "class EntityMeta" -A 12 src/main/java/io/appform/dropwizard/sharding/sharding/EntityMeta.java` and adjust. Note the metadata map is keyed by class name, and `getBucketInfo` returns `null` for unknown entities *before* the tenant lookup — so the map must contain an entry for the class passed in, or the test will get `null` instead of the exception. Use the resolver's own class name as the key if that is simpler.

- [ ] **Step 20: Run the new tests**

Run: `mvn -q test -Dtest='ShardCalculatorTest,DaoFactoryTest,BucketResolverUnknownTenantTest'`
Expected: PASS.

- [ ] **Step 21: Run the full suite**

Run: `mvn -q test`
Expected: BUILD SUCCESS. Every shard-placement assertion must still pass — if any fail, the bucket arithmetic changed and Step 5 was applied incorrectly.

- [ ] **Step 22: Commit**

```bash
git add -A
git commit -m "refactor!: make ShardCalculator tenant-scoped and bundle-owned"
```

---

### Task 6: Delete the `ShardedDao` interface

**Files:**
- Delete: `src/main/java/io/appform/dropwizard/sharding/dao/ShardedDao.java`
- Modify: `src/main/java/io/appform/dropwizard/sharding/dao/LookupDao.java:53`
- Modify: `src/main/java/io/appform/dropwizard/sharding/dao/RelationalDao.java:52`
- Modify: `src/main/java/io/appform/dropwizard/sharding/dao/MultiTenantLookupDao.java:102`
- Modify: `src/main/java/io/appform/dropwizard/sharding/dao/MultiTenantRelationalDao.java:112`
- Modify: `src/main/java/io/appform/dropwizard/sharding/dao/WrapperDao.java:47`
- Modify: `src/test/java/io/appform/dropwizard/sharding/dao/RelationalDaoTest.java:111`
- Modify: `src/test/java/io/appform/dropwizard/sharding/dao/MultiTenantRelationalDaoTest.java:115`
- Modify: `src/test/java/io/appform/dropwizard/sharding/dao/WrapperDaoTransactionReuseTest.java:87,133,184,238`

- [ ] **Step 1: Point the three tests at their own calculator**

In `RelationalDaoTest.java`, replace line 111:

```java
        this.shardCalculator = relationalDao.getShardCalculator();
```

with:

```java
        this.shardCalculator = ShardCalculators.calculator(shardManager);
```

and change the assertion at line 461 from `shardCalculator.shardId(DBShardingBundleBase.DEFAULT_NAMESPACE, id)` to `shardCalculator.shardId(id)`.

In `MultiTenantRelationalDaoTest.java`, replace line 115 with `this.shardCalculator = ShardCalculators.calculator(shardManager.get(tenantId));` — check the surrounding field names, since `shardManager` there holds a map — and change line 470 from `shardCalculator.shardId(tenantId, id)` to `shardCalculator.shardId(id)`.

In `WrapperDaoTransactionReuseTest.java`, add a field populated in `@BeforeEach`:

```java
    private ShardCalculator<String> shardCalculator;
    ...
    shardCalculator = ShardCalculators.calculator(shardManager);
```

and replace the four `dao.getShardCalculator().shardId(DBShardingBundleBase.DEFAULT_NAMESPACE, parentKey)` expressions (lines 87, 133, 184, 238) with `shardCalculator.shardId(parentKey)`.

Add `import io.appform.dropwizard.sharding.testutils.ShardCalculators;` and `import io.appform.dropwizard.sharding.utils.ShardCalculator;` where needed.

- [ ] **Step 2: Run those three tests**

Run: `mvn -q test -Dtest='RelationalDaoTest,MultiTenantRelationalDaoTest,WrapperDaoTransactionReuseTest'`
Expected: PASS. They now compute the expected shard themselves instead of borrowing the DAO's calculator.

- [ ] **Step 3: Remove the interface and its implementations**

Delete `src/main/java/io/appform/dropwizard/sharding/dao/ShardedDao.java`.

In each of the five DAO classes, drop the `implements ShardedDao<T>` clause and delete the `getShardCalculator()` method (and any `@Getter` still on a `shardCalculator` field). For example, `LookupDao.java:53` becomes:

```java
public class LookupDao<T> {
```

and `WrapperDao.java:47` becomes:

```java
public class WrapperDao<T, DaoType extends AbstractDAO<T>> {
```

Remove the now-unused `ShardedDao` imports.

- [ ] **Step 4: Verify nothing references it**

Run: `grep -rn "ShardedDao\|getShardCalculator" --include=*.java src`
Expected: no output.

- [ ] **Step 5: Run the full suite**

Run: `mvn -q test`
Expected: BUILD SUCCESS.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "refactor!: remove ShardedDao interface and getShardCalculator accessors"
```

---

### Task 7: Close the DAO classes

**Files:**
- Create: `src/test/java/io/appform/dropwizard/sharding/dao/DaoEncapsulationTest.java`
- Modify: all nine DAO classes in `src/main/java/io/appform/dropwizard/sharding/dao/`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/io/appform/dropwizard/sharding/dao/DaoEncapsulationTest.java`:

```java
package io.appform.dropwizard.sharding.dao;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DaoEncapsulationTest {

    @ParameterizedTest
    @ValueSource(classes = {
            MultiTenantLookupDao.class,
            MultiTenantCacheableLookupDao.class,
            MultiTenantRelationalDao.class,
            MultiTenantCacheableRelationalDao.class,
            LookupDao.class,
            CacheableLookupDao.class,
            RelationalDao.class,
            CacheableRelationalDao.class,
            WrapperDao.class
    })
    void noDaoExposesAPublicConstructor(final Class<?> daoClass) {
        final List<String> publicConstructors = Arrays.stream(daoClass.getDeclaredConstructors())
                .filter(constructor -> Modifier.isPublic(constructor.getModifiers()))
                .map(Constructor::toString)
                .collect(Collectors.toList());
        assertTrue(publicConstructors.isEmpty(),
                daoClass.getSimpleName() + " still exposes public constructors: " + publicConstructors);
    }

    @ParameterizedTest
    @ValueSource(classes = {
            MultiTenantCacheableLookupDao.class,
            MultiTenantCacheableRelationalDao.class,
            CacheableLookupDao.class,
            CacheableRelationalDao.class,
            WrapperDao.class
    })
    void leafDaosAreFinal(final Class<?> daoClass) {
        assertTrue(Modifier.isFinal(daoClass.getModifiers()),
                daoClass.getSimpleName() + " must be final");
    }

    @Test
    void baseDaosAreSealedToTheirCacheableVariants() {
        assertSealedTo(MultiTenantLookupDao.class, MultiTenantCacheableLookupDao.class);
        assertSealedTo(MultiTenantRelationalDao.class, MultiTenantCacheableRelationalDao.class);
        assertSealedTo(LookupDao.class, CacheableLookupDao.class);
        assertSealedTo(RelationalDao.class, CacheableRelationalDao.class);
    }

    private void assertSealedTo(final Class<?> base, final Class<?> permitted) {
        assertTrue(base.isSealed(), base.getSimpleName() + " must be sealed");
        assertEquals(Set.of(permitted), Set.of(base.getPermittedSubclasses()),
                base.getSimpleName() + " permits the wrong subclasses");
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `mvn -q test -Dtest=DaoEncapsulationTest`
Expected: FAIL — public constructors are listed, `isSealed()` is false, classes are not final.

- [ ] **Step 3: Seal the base classes and finalise the leaves**

Apply these class declarations (keep the existing generic parameters):

```java
public sealed class MultiTenantLookupDao<T> permits MultiTenantCacheableLookupDao {
public final class MultiTenantCacheableLookupDao<T> extends MultiTenantLookupDao<T> {
public sealed class MultiTenantRelationalDao<T> permits MultiTenantCacheableRelationalDao {
public final class MultiTenantCacheableRelationalDao<T> extends MultiTenantRelationalDao<T> {
public sealed class LookupDao<T> permits CacheableLookupDao {
public final class CacheableLookupDao<T> extends LookupDao<T> {
public sealed class RelationalDao<T> permits CacheableRelationalDao {
public final class CacheableRelationalDao<T> extends RelationalDao<T> {
public final class WrapperDao<T, DaoType extends AbstractDAO<T>> {
```

`permits` clauses name the raw type, without type arguments.

- [ ] **Step 4: Make every DAO constructor package private**

Remove the `public` modifier from the constructors of all nine classes — for example in `MultiTenantLookupDao.java`:

```java
    MultiTenantLookupDao(
            Map<String, List<SessionFactory>> sessionFactories,
```

Do not touch the private inner classes (`LookupDaoPriv`, `RelationalDaoPriv`) — they are already private.

- [ ] **Step 5: Run the encapsulation test**

Run: `mvn -q test -Dtest=DaoEncapsulationTest`
Expected: PASS.

- [ ] **Step 6: Run the full suite**

Run: `mvn -q test`
Expected: BUILD SUCCESS. If a test fails with a Mockito "cannot mock final class" error, that test was mocking a DAO — replace the mock with a `DaoFactory`-built instance rather than reverting the `final`.

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "feat!: seal DAO hierarchies and make constructors package private"
```

---

### Task 8: Seal the jar

Sealing stops a consumer from shipping a class in `io.appform.dropwizard.sharding.dao` from another jar to reach the package-private constructors — the JVM throws `SecurityException` at class definition time.

**Files:**
- Modify: `pom.xml` (add `maven-jar-plugin` to `<build><plugins>`)

- [ ] **Step 1: Add the jar plugin**

Inside `<build><plugins>` in `pom.xml`, next to the existing `maven-source-plugin`, add:

```xml
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-jar-plugin</artifactId>
                <version>3.3.0</version>
                <configuration>
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
                </configuration>
            </plugin>
```

- [ ] **Step 2: Build the jar**

Run: `mvn -q -DskipTests package`
Expected: BUILD SUCCESS.

- [ ] **Step 3: Verify the manifest**

Run: `unzip -p target/*.jar META-INF/MANIFEST.MF | grep -A 1 "^Name:"`
Expected output contains:

```
Name: io/appform/dropwizard/sharding/dao/
Sealed: true
Name: io/appform/dropwizard/sharding/dao/operations/
Sealed: true
```

- [ ] **Step 4: Run the full suite**

Run: `mvn -q test`
Expected: BUILD SUCCESS. The project's own tests load main classes from `target/classes`, not from the jar, so sealing does not affect them.

- [ ] **Step 5: Commit**

```bash
git add pom.xml
git commit -m "build: seal the DAO packages in the published jar"
```

---

### Task 9: Version bump and documentation

**Files:**
- Modify: `pom.xml:9`
- Modify: `README.md`
- Modify: `CHANGELOG.md`

- [ ] **Step 1: Bump the version**

Run: `mvn -q versions:set -DnewVersion=2.1.12-10`
Then verify: `grep -n "2.1.12-10" pom.xml`
Expected: `pom.xml:9` reads `<version>2.1.12-10</version>`.

- [ ] **Step 2: Document how DAOs are created**

Add this section to `README.md`, after the bundle setup instructions:

~~~markdown
## Creating DAOs

DAOs are created by the bundle, not with `new`. Their constructors are package private and the
classes are sealed, so the bundle's factory methods are the only construction path:

```java
LookupDao<Order> orderDao = bundle.createParentObjectDao(Order.class);
RelationalDao<OrderItem> itemDao = bundle.createRelatedObjectDao(OrderItem.class);
WrapperDao<Order, OrderDao> wrapperDao = bundle.createWrapperDao(OrderDao.class);
```

The multi-tenant bundles expose the same methods, returning the `MultiTenant*` variants whose
methods each take a tenant id.

### Shard calculators

Each tenant gets one `ShardCalculator`, built by the bundle and reachable through
`getShardCalculators()`. A calculator is bound to a single tenant, so it takes no tenant id:

```java
int shard = bundle.getShardCalculators().get(tenantId).shardId(parentKey);
```
~~~

- [ ] **Step 3: Record the breaking changes in the changelog**

Add a new entry at the top of `CHANGELOG.md`, directly under the `All notable changes…` line, following the existing style:

~~~markdown
## [2.1.12-10]

### Changed (breaking)
- **Java 17 is now required.** The published bytecode targets Java 17 so the DAO hierarchies can be `sealed`.
- **`ShardCalculator` is single-tenant.** `shardId(tenantId, key)` becomes `shardId(key)` and `isOnValidShard(tenantId, key)` becomes `isOnValidShard(key)`. The bundle owns one calculator per tenant, reachable via `getShardCalculators()`.
- **`BucketIdExtractor` is single-tenant.** `bucketId(tenantId, id)` becomes `bucketId(id)`. Custom implementations must be updated.
- **`ShardedDao` and all `getShardCalculator()` accessors have been removed.**
- **DAO constructors are package private and the classes are sealed or final.** Obtain DAOs from the bundle's `createParentObjectDao` / `createRelatedObjectDao` / `createWrapperDao` methods.
- The published jar seals `io/appform/dropwizard/sharding/dao/` and `io/appform/dropwizard/sharding/dao/operations/`.

  **Reason**: each DAO previously built its own `ShardCalculator` and `ConsistentHashBucketIdExtractor`, duplicating one object per DAO per tenant, and public constructors let clients bypass the bundle entirely.
~~~

- [ ] **Step 4: Repeat the upgrade notes in the README**

Add this section to `README.md`:

~~~markdown
## Upgrading to 2.1.12-10

Breaking changes:

1. **Java 17 is required.** The published bytecode now targets Java 17.
2. **`ShardCalculator` is single-tenant.** `shardId(tenantId, key)` becomes `shardId(key)` and
   `isOnValidShard(tenantId, key)` becomes `isOnValidShard(key)`. Get the tenant's calculator from
   `getShardCalculators()` on the bundle.
3. **`BucketIdExtractor` is single-tenant.** `bucketId(tenantId, id)` becomes `bucketId(id)`.
   Custom implementations must be updated.
4. **`ShardedDao` and all `getShardCalculator()` methods are removed.**
5. **DAO constructors are package private and the classes are sealed or final.** Obtain DAOs from
   the bundle's `createParentObjectDao` / `createRelatedObjectDao` / `createWrapperDao` methods.
~~~

- [ ] **Step 5: Final verification**

Run: `mvn -q clean test && mvn -q -DskipTests package && unzip -p target/*.jar META-INF/MANIFEST.MF | grep Sealed`
Expected: BUILD SUCCESS twice, then two `Sealed: true` lines.

- [ ] **Step 6: Commit**

```bash
git add pom.xml README.md CHANGELOG.md
git commit -m "docs: document DAO creation and 2.1.12-10 breaking changes"
```

---

## Done criteria

- `grep -rn "ShardedDao\|getShardCalculator" --include=*.java src` returns nothing.
- `grep -rn "new .*Dao<>(" --include=*.java src | grep -v DaoFactory.java` returns nothing.
- `ShardCalculator` and `BucketIdExtractor` have no `tenantId` parameters.
- `mvn clean test` passes with the pre-existing tests plus `ShardCalculatorTest`, `DaoFactoryTest`, `BucketResolverUnknownTenantTest` and `DaoEncapsulationTest`.
- The packaged jar's manifest seals both DAO packages.

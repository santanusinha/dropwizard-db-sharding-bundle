# Plan: Extract ScrollExecutor to eliminate SonarCloud duplication

## Goal
Reduce new-code duplication from 31.9% to ≤ 3% on PR #152 by extracting the QuerySpec-based scroll logic into a shared `ScrollExecutor<T>` class. DetachedCriteria scroll methods remain untouched.

## Design

`ScrollExecutor<T>` is an **instantiable class** (following the `LockedContext` pattern) that encapsulates the QuerySpec scroll algorithm. Each MultiTenant DAO constructs one on-demand, injecting shard-specific dependencies.

### New file: `src/main/java/io/appform/dropwizard/sharding/scroll/ScrollExecutor.java`

```java
package io.appform.dropwizard.sharding.scroll;

import com.google.common.collect.ImmutableList;
import io.appform.dropwizard.sharding.dao.operations.Select;
import io.appform.dropwizard.sharding.dao.operations.SelectParam;
import io.appform.dropwizard.sharding.execution.TransactionExecutor;
import io.appform.dropwizard.sharding.query.QuerySpec;
import lombok.SneakyThrows;
import lombok.val;
import org.apache.commons.lang3.reflect.FieldUtils;
import org.hibernate.SessionFactory;

import javax.persistence.criteria.CriteriaBuilder;
import javax.persistence.criteria.Root;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Encapsulates the QuerySpec-based scroll algorithm across shards.
 * Constructed per-tenant by MultiTenantRelationalDao / MultiTenantLookupDao.
 *
 * Follows the LockedContext pattern: captures shard-specific dependencies at
 * construction time; callers invoke scrollDown/scrollUp.
 */
public class ScrollExecutor<T> {

    private final List<SessionFactory> sessionFactories;
    private final List<Function<SelectParam, List<T>>> selectors;
    private final TransactionExecutor transactionExecutor;
    private final Class<T> entityClass;

    public ScrollExecutor(
            List<SessionFactory> sessionFactories,
            List<Function<SelectParam, List<T>>> selectors,
            TransactionExecutor transactionExecutor,
            Class<T> entityClass) {
        this.sessionFactories = sessionFactories;
        this.selectors = selectors;
        this.transactionExecutor = transactionExecutor;
        this.entityClass = entityClass;
    }

    public ScrollResult<T> scrollDown(
            final QuerySpec<T, T> inQuerySpec,
            final ScrollPointer inPointer,
            final int pageSize,
            final String sortFieldName) {
        val pointer = inPointer == null
                ? new ScrollPointer(ScrollPointer.Direction.DOWN)
                : inPointer;
        Preconditions.checkArgument(
                pointer.getDirection().equals(ScrollPointer.Direction.DOWN),
                "A down scroll pointer needs to be passed to this method");
        return scroll(inQuerySpec, pointer, pageSize,
                (root, cb) -> cb.asc(root.get(sortFieldName)),
                new FieldComparator<T>(FieldUtils.getField(entityClass, sortFieldName, true))
                        .thenComparing(ScrollResultItem::getShardIdx),
                "scrollDown");
    }

    @SneakyThrows
    public ScrollResult<T> scrollUp(
            final QuerySpec<T, T> inQuerySpec,
            final ScrollPointer inPointer,
            final int pageSize,
            final String sortFieldName) {
        val pointer = null == inPointer
                ? new ScrollPointer(ScrollPointer.Direction.UP)
                : inPointer;
        Preconditions.checkArgument(
                pointer.getDirection().equals(ScrollPointer.Direction.UP),
                "An up scroll pointer needs to be passed to this method");
        return scroll(inQuerySpec, pointer, pageSize,
                (root, cb) -> cb.desc(root.get(sortFieldName)),
                new FieldComparator<T>(FieldUtils.getField(entityClass, sortFieldName, true))
                        .reversed()
                        .thenComparing(ScrollResultItem::getShardIdx),
                "scrollUp");
    }

    // Differs from hibernate6: composes ordering into a new QuerySpec lambda
    // instead of using criteriaMutator.apply(InternalUtils.cloneObject(inCriteria))
    // since QuerySpec cannot be cloned (not Serializable)
    @SneakyThrows
    private ScrollResult<T> scroll(
            final QuerySpec<T, T> inQuerySpec,
            final ScrollPointer pointer,
            final int pageSize,
            final BiFunction<Root<T>, CriteriaBuilder, javax.persistence.criteria.Order> orderFactory,
            final Comparator<ScrollResultItem<T>> comparator,
            String methodName) {
        val daoIndex = new AtomicInteger();
        val results = IntStream.range(0, sessionFactories.size())
                .boxed()
                .flatMap(idx -> {
                    val currIdx = daoIndex.getAndIncrement();
                    final QuerySpec<T, T> orderedSpec = (root, query, cb) -> {
                        inQuerySpec.apply(root, query, cb);
                        query.orderBy(orderFactory.apply(root, cb));
                    };
                    val opContext = Select.<T, List<T>>builder()
                            .getter(selectors.get(idx))
                            .selectParam(SelectParam.<T>builder()
                                    .querySpec(orderedSpec)
                                    .start(pointer.getCurrOffset(currIdx))
                                    .numRows(pageSize)
                                    .build())
                            .build();
                    return transactionExecutor
                            .execute(sessionFactories.get(idx), true,
                                    methodName, opContext, currIdx)
                            .stream()
                            .map(item -> new ScrollResultItem<>(item, currIdx));
                })
                .sorted(comparator)
                .limit(pageSize)
                .collect(Collectors.toList());

        val outputBuilder = ImmutableList.<T>builder();
        results.forEach(result -> {
            outputBuilder.add(result.getData());
            pointer.advance(result.getShardIdx(), 1);
        });
        return new ScrollResult<>(pointer, outputBuilder.build());
    }
}
```

Note: The exact iteration approach (stream over indices vs stream over daos) will be refined during implementation to match the existing pattern precisely.

---

## Changes per file

### 1. `MultiTenantRelationalDao.java`
- **Delete**: `scrollImplWithQuerySpec` private method (~45 lines)
- **Delete**: `scrollDown(String tenantId, QuerySpec<T,T>, ...)` method body (~20 lines), replace with delegation:
  ```java
  public ScrollResult<T> scrollDown(String tenantId, final QuerySpec<T, T> inQuerySpec,
                                    final ScrollPointer inPointer, final int pageSize,
                                    @NonNull final String sortFieldName) {
      Preconditions.checkArgument(daos.containsKey(tenantId), "Unknown tenant: " + tenantId);
      log.debug("SCROLL POINTER: {}", inPointer);
      return buildScrollExecutor(tenantId).scrollDown(inQuerySpec, inPointer, pageSize, sortFieldName);
  }
  ```
- **Delete**: `scrollUp(String tenantId, QuerySpec<T,T>, ...)` method body (~20 lines), replace similarly
- **Add**: `buildScrollExecutor(String tenantId)` private helper method (~10 lines):
  ```java
  private ScrollExecutor<T> buildScrollExecutor(String tenantId) {
      val daoList = daos.get(tenantId);
      return new ScrollExecutor<>(
              daoList.stream().map(d -> d.sessionFactory).collect(Collectors.toList()),
              daoList.stream().map(d -> (Function<SelectParam, List<T>>) d::select).collect(Collectors.toList()),
              transactionExecutor.get(tenantId),
              entityClass);
  }
  ```
- **Leave untouched**: `scrollImpl`, `scrollDown(tenantId, DetachedCriteria, ...)`, `scrollUp(tenantId, DetachedCriteria, ...)`

### 2. `MultiTenantLookupDao.java`
- Same changes as MultiTenantRelationalDao above:
  - Delete `scrollImplWithQuerySpec`
  - Replace QuerySpec `scrollDown`/`scrollUp` bodies with delegation to `ScrollExecutor`
  - Add `buildScrollExecutor(String tenantId)` helper
  - Leave DetachedCriteria methods untouched

### 3. `RelationalDao.java` — No changes
### 4. `LookupDao.java` — No changes

### 5. Test files — Already fixed (public modifier removed)

---

## Verification

1. `mvn clean test` — all 278 tests must pass
2. Commit: `refactor: extract ScrollExecutor to eliminate cross-file duplication in QuerySpec scroll methods`
3. Push to `my-fork/feat_scroll_api_queryspec` (will update PR #152)
4. SonarCloud re-analysis should show duplication ≤ 3%

---

## Net line changes estimate

| File | Lines removed | Lines added | Net |
|------|--------------|------------|-----|
| `ScrollExecutor.java` (new) | 0 | ~110 | +110 |
| `MultiTenantRelationalDao.java` | ~85 (scrollImplWithQuerySpec + old scrollDown/Up QS bodies) | ~20 (thin delegations + buildScrollExecutor) | -65 |
| `MultiTenantLookupDao.java` | ~85 | ~20 | -65 |
| **Total** | ~170 | ~150 | -20 |

The ~100-line duplication block (scrollImplWithQuerySpec) vanishes from both DAOs. The ~75-line duplication block (public scrollDown/Up QS overloads) shrinks to 3-line delegations. The shared logic lives once in `ScrollExecutor`.

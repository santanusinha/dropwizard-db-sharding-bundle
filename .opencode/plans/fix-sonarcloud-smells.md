# Plan: Fix SonarCloud Code Smells & Address Duplication

## Status: Ready to execute

## Task 1: Fix 4 code smells (remove `public` from JUnit 5 test methods)

### File 1: `src/test/java/io/appform/dropwizard/sharding/ScrollTest.java`
- **Line 202**: Change `public void testScrollDownWithQuerySpec()` → `void testScrollDownWithQuerySpec()`
- **Line 231**: Change `public void testScrollUpWithQuerySpec()` → `void testScrollUpWithQuerySpec()`

### File 2: `src/test/java/io/appform/dropwizard/sharding/dao/MultiTenantRelationalDaoTest.java`
- **Line 330**: Change `public void testScrollingWithQuerySpec()` → `void testScrollingWithQuerySpec()`

### File 3: `src/test/java/io/appform/dropwizard/sharding/dao/RelationalDaoTest.java`
- **Line 325**: Change `public void testScrollingWithQuerySpec()` → `void testScrollingWithQuerySpec()`

## Task 2: Run tests
```bash
mvn clean test
```
Expect: 278 tests pass, 0 failures, 0 errors.

## Task 3: Commit and push
```bash
git add -A
git commit -m "fix: remove public modifier from JUnit 5 test methods (SonarCloud code smells)"
git push my-fork feat_scroll_api_queryspec
```

## Task 4: Post PR comment about duplication

Post a comment on PR #152 suggesting the following refactoring to address the 31.9% duplication:

> ### Suggestion: Extract `ScrollHelper` to reduce cross-file duplication
>
> SonarCloud flags 31.9% duplication on new code (threshold: ≤ 3%), coming from:
> - `scrollImpl` / `scrollImplWithQuerySpec` being nearly identical between `MultiTenantRelationalDao` and `MultiTenantLookupDao`
> - The public `scrollDown`/`scrollUp` methods following the same pattern across both classes
>
> **Proposed fix:** Extract a shared `ScrollHelper` utility class in `io.appform.dropwizard.sharding.scroll` with a single generic static method:
>
> ```java
> public static <T> ScrollResult<T> scroll(
>     List<ShardDaoContext<T>> shardDaos,
>     TransactionExecutor txExecutor,
>     ScrollPointer pointer,
>     int pageSize,
>     Function<Integer, SelectParam<T>> selectParamFactory,
>     Comparator<ScrollResultItem<T>> comparator,
>     String methodName)
> ```
>
> Where `ShardDaoContext<T>` is a simple record holding `SessionFactory` + `Function<SelectParam, List<T>>` (i.e., `dao.sessionFactory` + `dao::select`).
>
> This eliminates all 4 private scroll impl methods from both MultiTenant DAOs, replacing them with calls to `ScrollHelper.scroll()`. The `selectParamFactory` lambda handles the difference between DetachedCriteria (clone + mutate) and QuerySpec (compose ordering lambda) paths.
>
> This should bring duplication well under 3%.

# TransactionHandler Strategy Pattern Refactor — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the three boolean flags (`readOnly`, `skipCommit`, `sessionAcquired`) in `TransactionHandler` and the repeated `if (completeTransaction)` guards in `TransactionExecutor` with a Strategy pattern — three small, flag-free lifecycle implementations selected by a static factory.

**Architecture:** `TransactionHandler` becomes a pure factory with a single static `begin()` method that inspects the current Hibernate session context and returns one of three `TransactionLifecycle` implementations. The fourth theoretical case — a bound session with no active transaction and `skipCommit=false` — is a dead code path (write nested inside an optional-read outer handler) that the factory now fails fast on with `IllegalStateException`. `TransactionExecutor` is split into `executeWithLifecycle` (full lifecycle) and `executeInExistingSession` (no lifecycle, used when a `LockedContext` is managing the session).

**Tech Stack:** Java 17, Hibernate 6, Lombok, JUnit 5, Mockito

---

## File Map

| Action | File | Responsibility |
|--------|------|----------------|
| CREATE | `src/main/java/io/appform/dropwizard/sharding/utils/TransactionLifecycle.java` | Public interface: `getSession()`, `afterEnd()`, `onError()` |
| CREATE | `src/main/java/io/appform/dropwizard/sharding/utils/OwnedTransactionLifecycle.java` | Opened session + began transaction. Commits on success, rolls back on error, closes session always. |
| CREATE | `src/main/java/io/appform/dropwizard/sharding/utils/OptionalTransactionLifecycle.java` | Opened session, no Hibernate-managed transaction. Does `conn.rollback()` (readOnly) to prevent MVCC snapshot leakage; closes session always. |
| CREATE | `src/main/java/io/appform/dropwizard/sharding/utils/PassthroughLifecycle.java` | Joined existing session AND joined existing active transaction. All lifecycle methods are no-ops. |
| MODIFY | `src/main/java/io/appform/dropwizard/sharding/utils/TransactionHandler.java` | Becomes a pure factory: `begin(sf, readOnly)` and `begin(sf, readOnly, transactionOptional)`. Throws `IllegalStateException` for the dead-code case (bound session, no active txn, `skipCommit=false`). Old constructors, fields, and instance methods removed. |
| MODIFY | `src/main/java/io/appform/dropwizard/sharding/execution/TransactionExecutor.java` | Extract `buildExecutionContext()` helper; split `execute(..., completeTransaction)` into `executeWithLifecycle` and `executeInExistingSession` to eliminate three identical `if (completeTransaction)` guards. |
| MODIFY | `src/main/java/io/appform/dropwizard/sharding/dao/LockedContext.java` | Replace `new TransactionHandler(...) + beforeStart()` with `TransactionHandler.begin(...)`. |
| MODIFY | `src/main/java/io/appform/dropwizard/sharding/dao/WrapperDao.java` | Same as above. |
| MODIFY | `src/main/java/io/appform/dropwizard/sharding/dao/MultiTenantRelationalDao.java` | Same as above (inner `ReadContext` class, `executeImpl()`). |
| MODIFY | `src/main/java/io/appform/dropwizard/sharding/dao/MultiTenantLookupDao.java` | Same as above (inner `ReadContext` class, `executeImpl()`). |
| MODIFY | `src/test/java/io/appform/dropwizard/sharding/utils/TransactionHandlerTest.java` | Rewrite to test `begin()` factory + each lifecycle behaviour via Mockito. |

---

## Task 1 — Create the `TransactionLifecycle` interface

**Files:**
- Create: `src/main/java/io/appform/dropwizard/sharding/utils/TransactionLifecycle.java`

- [ ] **Step 1.1 — Write the interface**

```java
package io.appform.dropwizard.sharding.utils;

import org.hibernate.Session;

/**
 * Encapsulates the lifecycle of a single unit-of-work's transaction and session.
 * Implementations are created by {@link TransactionHandler#begin} and vary
 * depending on whether this handler owns the session, owns the transaction, or
 * is simply participating in an already-active outer transaction.
 */
public interface TransactionLifecycle {

    /** Returns the Hibernate session for this unit of work. */
    Session getSession();

    /**
     * Called when the unit of work completed successfully.
     * Commits and/or closes resources according to ownership.
     */
    void afterEnd();

    /**
     * Called when the unit of work threw an exception.
     * Rolls back and/or closes resources according to ownership.
     */
    void onError();
}
```

- [ ] **Step 1.2 — Verify it compiles**

```bash
cd /path/to/dropwizard-db-sharding-bundle
mvn compile -q
```
Expected: BUILD SUCCESS

- [ ] **Step 1.3 — Commit**

```bash
git add src/main/java/io/appform/dropwizard/sharding/utils/TransactionLifecycle.java
git commit -m "refactor: add TransactionLifecycle interface"
```

---

## Task 2 — Create `OwnedTransactionLifecycle`

Handles the most common case: this handler opened the session **and** began the transaction. It is responsible for committing, rolling back, and closing.

**Files:**
- Create: `src/main/java/io/appform/dropwizard/sharding/utils/OwnedTransactionLifecycle.java`

- [ ] **Step 2.1 — Write the class**

```java
package io.appform.dropwizard.sharding.utils;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.Transaction;
import org.hibernate.context.internal.ManagedSessionContext;
import org.hibernate.resource.transaction.spi.TransactionStatus;
import org.jboss.logging.MDC;

/**
 * Owns both the session (opened by this handler) and the transaction (begun by
 * this handler). Commits on {@link #afterEnd()}, rolls back on {@link #onError()},
 * and always closes the session.
 */
@RequiredArgsConstructor
class OwnedTransactionLifecycle implements TransactionLifecycle {

    private final SessionFactory sessionFactory;
    @Getter
    private final Session session;

    @Override
    public void afterEnd() {
        try {
            commitTransaction();
        } catch (Exception e) {
            rollbackTransaction();
            throw e;
        } finally {
            releaseSession();
        }
    }

    @Override
    public void onError() {
        try {
            rollbackTransaction();
        } finally {
            releaseSession();
        }
    }

    private void commitTransaction() {
        Transaction txn = session.getTransaction();
        if (txn != null && txn.getStatus() == TransactionStatus.ACTIVE) {
            txn.commit();
        }
    }

    private void rollbackTransaction() {
        Transaction txn = session.getTransaction();
        if (txn != null && txn.getStatus() == TransactionStatus.ACTIVE) {
            txn.rollback();
        }
    }

    private void releaseSession() {
        session.close();
        ManagedSessionContext.unbind(sessionFactory);
        MDC.remove(TransactionHandler.TENANT_ID);
    }
}
```

- [ ] **Step 2.2 — Verify it compiles**

```bash
mvn compile -q
```
Expected: BUILD SUCCESS

- [ ] **Step 2.3 — Commit**

```bash
git add src/main/java/io/appform/dropwizard/sharding/utils/OwnedTransactionLifecycle.java
git commit -m "refactor: add OwnedTransactionLifecycle"
```

---

## Task 3 — Create `OptionalTransactionLifecycle`

Handles the read-only / transaction-optional case: this handler opened the session but skipped `beginTransaction()`. The JDBC connection still has `autoCommit=false`, so we must roll it back at the end to prevent MVCC snapshot leakage across pool connections.

**Files:**
- Create: `src/main/java/io/appform/dropwizard/sharding/utils/OptionalTransactionLifecycle.java`

- [ ] **Step 3.1 — Write the class**

```java
package io.appform.dropwizard.sharding.utils;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.context.internal.ManagedSessionContext;
import org.jboss.logging.MDC;

/**
 * Owns the session (opened by this handler) but does NOT own a Hibernate-managed
 * transaction (transaction was optional / skipped). When {@code readOnly=true} the
 * JDBC connection still has {@code autoCommit=false}, so {@link #afterEnd()} rolls
 * it back directly to prevent MVCC snapshot leakage.
 */
@RequiredArgsConstructor
class OptionalTransactionLifecycle implements TransactionLifecycle {

    private final SessionFactory sessionFactory;
    @Getter
    private final Session session;
    private final boolean readOnly;

    @Override
    public void afterEnd() {
        try {
            if (readOnly) {
                session.doWork(conn -> conn.rollback());
            }
        } finally {
            releaseSession();
        }
    }

    @Override
    public void onError() {
        releaseSession();
    }

    private void releaseSession() {
        session.close();
        ManagedSessionContext.unbind(sessionFactory);
        MDC.remove(TransactionHandler.TENANT_ID);
    }
}
```

- [ ] **Step 3.2 — Verify it compiles**

```bash
mvn compile -q
```
Expected: BUILD SUCCESS

- [ ] **Step 3.3 — Commit**

```bash
git add src/main/java/io/appform/dropwizard/sharding/utils/OptionalTransactionLifecycle.java
git commit -m "refactor: add OptionalTransactionLifecycle"
```

---

## Task 4 — Create `PassthroughLifecycle`

**Files:**
- Create: `src/main/java/io/appform/dropwizard/sharding/utils/PassthroughLifecycle.java`

> **Note:** `JoinedTransactionLifecycle` (bound session, no active txn, `skipCommit=false`) was
> considered but determined to be a dead code path — it would only occur if a write operation
> were nested inside a `transactionOptional` outer handler, which is a misuse. The factory
> throws `IllegalStateException` for that case instead (fail-fast over silent wrong behaviour).

- [ ] **Step 4.1 — Write `PassthroughLifecycle`**

Joined an existing session **and** an existing active transaction. The outer handler already manages everything — all lifecycle methods are no-ops.

```java
package io.appform.dropwizard.sharding.utils;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.hibernate.Session;

/**
 * Joined an existing session that already had an active transaction.
 * The outer handler owns both; all lifecycle methods are no-ops.
 */
@RequiredArgsConstructor
class PassthroughLifecycle implements TransactionLifecycle {

    @Getter
    private final Session session;

    @Override
    public void afterEnd() {
        // no-op: outer handler manages commit
    }

    @Override
    public void onError() {
        // no-op: outer handler manages rollback
    }
}
```

- [ ] **Step 4.3 — Verify both compile**

```bash
mvn compile -q
```
Expected: BUILD SUCCESS

- [ ] **Step 4.4 — Commit**

```bash
git add src/main/java/io/appform/dropwizard/sharding/utils/JoinedTransactionLifecycle.java \
        src/main/java/io/appform/dropwizard/sharding/utils/PassthroughLifecycle.java
git commit -m "refactor: add JoinedTransactionLifecycle and PassthroughLifecycle"
```

---

## Task 5 — Refactor `TransactionHandler` into a factory

Replace all constructors, instance fields, and instance methods with a static `begin()` factory that selects the correct `TransactionLifecycle` implementation.

**Files:**
- Modify: `src/main/java/io/appform/dropwizard/sharding/utils/TransactionHandler.java`

- [ ] **Step 5.1 — Replace the entire file content**

```java
package io.appform.dropwizard.sharding.utils;

import org.hibernate.CacheMode;
import org.hibernate.FlushMode;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.Transaction;
import org.hibernate.context.internal.ManagedSessionContext;
import org.jboss.logging.MDC;

/**
 * Factory for {@link TransactionLifecycle}.
 *
 * <p>Call {@link #begin} to open (or join) a Hibernate session and return the
 * correct lifecycle implementation for the current context. The returned object's
 * {@link TransactionLifecycle#afterEnd()} / {@link TransactionLifecycle#onError()}
 * must be called in a try/catch/finally after the unit of work.
 *
 * <h3>Selection logic</h3>
 * <pre>
 *  Session already bound?
 *  ├── yes, existing txn active  OR  transactionOptional → PassthroughLifecycle
 *  └── yes, no active txn, !transactionOptional          → JoinedTransactionLifecycle
 *  Session NOT bound:
 *  ├── transactionOptional                               → OptionalTransactionLifecycle
 *  └── !transactionOptional                              → OwnedTransactionLifecycle
 * </pre>
 */
public final class TransactionHandler {

    public static final String TENANT_ID = "tenant.id";

    private TransactionHandler() {}

    /**
     * Convenience overload with {@code transactionOptional = false}.
     */
    public static TransactionLifecycle begin(SessionFactory sessionFactory, boolean readOnly) {
        return begin(sessionFactory, readOnly, false);
    }

    /**
     * Opens or joins a Hibernate session, configures it, and returns the appropriate
     * {@link TransactionLifecycle} for the caller to drive through
     * {@link TransactionLifecycle#afterEnd()} and {@link TransactionLifecycle#onError()}.
     *
     * @param sessionFactory     the factory to open/join a session on
     * @param readOnly           whether the session should be read-only
     * @param transactionOptional when {@code true} this handler will NOT call
     *                            {@code session.beginTransaction()} even for a new session
     */
    public static TransactionLifecycle begin(SessionFactory sessionFactory,
                                             boolean readOnly,
                                             boolean transactionOptional) {
        if (ManagedSessionContext.hasBind(sessionFactory)) {
            return joinExistingSession(sessionFactory, transactionOptional);
        }
        return openNewSession(sessionFactory, readOnly, transactionOptional);
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private static TransactionLifecycle joinExistingSession(SessionFactory sessionFactory,
                                                            boolean transactionOptional) {
        Session session = sessionFactory.getCurrentSession();
        Transaction existing = session.getTransaction();
        boolean hasActiveTxn = existing != null && existing.isActive();
        if (transactionOptional || hasActiveTxn) {
            return new PassthroughLifecycle(session);
        }
        // A session is bound but has no active transaction, and the caller wants a
        // non-optional transaction. This means a write operation was nested inside
        // a transactionOptional outer handler — a misuse of the API. Fail fast.
        throw new IllegalStateException(
                "A Hibernate session is already bound but has no active transaction. "
                + "This typically means a write operation was called within a "
                + "transactionOptional (read-only) outer context. Ensure all write "
                + "operations run within an active transaction.");
    }

    private static TransactionLifecycle openNewSession(SessionFactory sessionFactory,
                                                       boolean readOnly,
                                                       boolean transactionOptional) {
        Session session = sessionFactory.openSession();
        try {
            configureSession(session, readOnly, sessionFactory);
            ManagedSessionContext.bind(session);
        } catch (Throwable th) {
            session.close();
            throw th;
        }
        if (transactionOptional) {
            return new OptionalTransactionLifecycle(sessionFactory, session, readOnly);
        }
        session.beginTransaction();
        return new OwnedTransactionLifecycle(sessionFactory, session);
    }

    private static void configureSession(Session session, boolean readOnly,
                                         SessionFactory sessionFactory) {
        session.setDefaultReadOnly(readOnly);
        session.setCacheMode(CacheMode.NORMAL);
        session.setHibernateFlushMode(FlushMode.AUTO);
        if (sessionFactory.getProperties().containsKey(TENANT_ID)) {
            MDC.put(TENANT_ID, sessionFactory.getProperties().get(TENANT_ID).toString());
        }
    }
}
```

- [ ] **Step 5.2 — Verify it compiles (will fail at call sites — that's expected)**

```bash
mvn compile 2>&1 | grep "error:" | head -30
```

Expected: compile errors in `TransactionExecutor`, `LockedContext`, `WrapperDao`, `MultiTenantRelationalDao`, `MultiTenantLookupDao`, and `TransactionHandlerTest` — all of which reference the old constructors. These are fixed in Tasks 6 and 7.

---

## Task 6 — Rewrite `TransactionHandlerTest`

**Files:**
- Modify: `src/test/java/io/appform/dropwizard/sharding/utils/TransactionHandlerTest.java`

- [ ] **Step 6.1 — Replace the entire test file**

```java
package io.appform.dropwizard.sharding.utils;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.Transaction;
import org.hibernate.context.internal.ManagedSessionContext;
import org.hibernate.resource.transaction.spi.TransactionStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class TransactionHandlerTest {

    private SessionFactory sessionFactory;
    private Session session;
    private Transaction transaction;

    @BeforeEach
    void setUp() {
        sessionFactory = mock(SessionFactory.class);
        session = mock(Session.class);
        transaction = mock(Transaction.class);

        when(sessionFactory.openSession()).thenReturn(session);
        when(sessionFactory.getCurrentSession()).thenReturn(session);
        when(session.getTransaction()).thenReturn(transaction);
        when(transaction.getStatus()).thenReturn(TransactionStatus.ACTIVE);
        when(transaction.isActive()).thenReturn(true);
    }

    @AfterEach
    void tearDown() {
        ManagedSessionContext.unbind(sessionFactory);
    }

    // -------------------------------------------------------------------------
    // Factory selection
    // -------------------------------------------------------------------------

    @Nested
    class FactorySelection {

        @Test
        void noExistingSession_notOptional_returnsOwnedTransactionLifecycle() {
            TransactionLifecycle lc = TransactionHandler.begin(sessionFactory, false);
            assertInstanceOf(OwnedTransactionLifecycle.class, lc);
            assertNotNull(lc.getSession());
        }

        @Test
        void noExistingSession_optional_returnsOptionalTransactionLifecycle() {
            TransactionLifecycle lc = TransactionHandler.begin(sessionFactory, true, true);
            assertInstanceOf(OptionalTransactionLifecycle.class, lc);
            assertNotNull(lc.getSession());
        }

        @Test
        void existingSession_withActiveTxn_returnsPassthroughLifecycle() {
            ManagedSessionContext.bind(session);
            when(transaction.isActive()).thenReturn(true);

            TransactionLifecycle lc = TransactionHandler.begin(sessionFactory, false);

            assertInstanceOf(PassthroughLifecycle.class, lc);
            assertSame(session, lc.getSession());
        }

        @Test
        void existingSession_noActiveTxn_notOptional_throwsIllegalStateException() {
            // Dead code path: a write nested inside a transactionOptional outer handler.
            // The factory must fail fast rather than silently begin a transaction on a
            // session that was opened without one.
            ManagedSessionContext.bind(session);
            when(transaction.isActive()).thenReturn(false);

            assertThrows(IllegalStateException.class,
                    () -> TransactionHandler.begin(sessionFactory, false, false));
        }

        @Test
        void existingSession_noActiveTxn_optional_returnsPassthroughLifecycle() {
            ManagedSessionContext.bind(session);
            when(transaction.isActive()).thenReturn(false);

            TransactionLifecycle lc = TransactionHandler.begin(sessionFactory, false, true);

            assertInstanceOf(PassthroughLifecycle.class, lc);
        }
    }

    // -------------------------------------------------------------------------
    // OwnedTransactionLifecycle
    // -------------------------------------------------------------------------

    @Nested
    class OwnedTransaction {

        private TransactionLifecycle lifecycle;

        @BeforeEach
        void start() {
            lifecycle = TransactionHandler.begin(sessionFactory, false);
        }

        @Test
        void afterEnd_commitsAndClosesSession() {
            lifecycle.afterEnd();
            verify(transaction).commit();
            verify(session).close();
        }

        @Test
        void afterEnd_rollsBackAndClosesWhenCommitFails() {
            doThrow(new RuntimeException("commit failed")).when(transaction).commit();
            assertThrows(RuntimeException.class, lifecycle::afterEnd);
            verify(transaction).rollback();
            verify(session).close();
        }

        @Test
        void onError_rollsBackAndClosesSession() {
            lifecycle.onError();
            verify(transaction).rollback();
            verify(session).close();
        }

        @Test
        void onError_closesSessionEvenIfRollbackThrows() {
            doThrow(new RuntimeException("rollback failed")).when(transaction).rollback();
            assertThrows(RuntimeException.class, lifecycle::onError);
            verify(session).close();
        }
    }

    // -------------------------------------------------------------------------
    // OptionalTransactionLifecycle
    // -------------------------------------------------------------------------

    @Nested
    class OptionalTransaction {

        @Test
        void afterEnd_readOnly_rollsBackConnectionAndClosesSession() throws Exception {
            TransactionLifecycle lc = TransactionHandler.begin(sessionFactory, true, true);
            lc.afterEnd();
            // doWork is called to roll back the implicit JDBC transaction
            verify(session).doWork(any());
            verify(session).close();
        }

        @Test
        void afterEnd_notReadOnly_closesSessionWithoutConnectionRollback() {
            TransactionLifecycle lc = TransactionHandler.begin(sessionFactory, false, true);
            lc.afterEnd();
            verify(session, never()).doWork(any());
            verify(session).close();
        }

        @Test
        void onError_closesSessionWithoutRollback() {
            TransactionLifecycle lc = TransactionHandler.begin(sessionFactory, true, true);
            lc.onError();
            verify(transaction, never()).rollback();
            verify(session).close();
        }
    }

    // -------------------------------------------------------------------------
    // PassthroughLifecycle
    // -------------------------------------------------------------------------

    @Nested
    class Passthrough {

        @Test
        void afterEnd_isNoOp() {
            ManagedSessionContext.bind(session);
            TransactionLifecycle lc = TransactionHandler.begin(sessionFactory, false);
            assertDoesNotThrow(lc::afterEnd);
            verify(session, never()).close();
            verify(transaction, never()).commit();
        }

        @Test
        void onError_isNoOp() {
            ManagedSessionContext.bind(session);
            TransactionLifecycle lc = TransactionHandler.begin(sessionFactory, false);
            assertDoesNotThrow(lc::onError);
            verify(session, never()).close();
            verify(transaction, never()).rollback();
        }
    }

    // -------------------------------------------------------------------------
    // JoinedTransactionLifecycle — REMOVED (dead code path, replaced by IllegalStateException)
    // The factory selection test above covers this case with assertThrows.
    // -------------------------------------------------------------------------
}
```

- [ ] **Step 6.2 — Run the test in isolation (should fail — other callers still broken)**

```bash
mvn test -pl . -Dtest=TransactionHandlerTest 2>&1 | tail -20
```

Expected: FAILURE due to compile errors in other files, not in `TransactionHandlerTest` itself.

---

## Task 7 — Update `TransactionExecutor`

Split the single `execute(... boolean completeTransaction)` overload into two private helpers to eliminate the three repeated `if (completeTransaction)` guards. Add a `buildExecutionContext` helper to remove duplication.

**Files:**
- Modify: `src/main/java/io/appform/dropwizard/sharding/execution/TransactionExecutor.java`

- [ ] **Step 7.1 — Replace the entire file content**

```java
package io.appform.dropwizard.sharding.execution;

import io.appform.dropwizard.sharding.ShardInfoProvider;
import io.appform.dropwizard.sharding.dao.operations.OpContext;
import io.appform.dropwizard.sharding.observers.TransactionObserver;
import io.appform.dropwizard.sharding.utils.TransactionHandler;
import io.appform.dropwizard.sharding.utils.TransactionLifecycle;
import lombok.val;
import org.hibernate.SessionFactory;

/**
 * Utility class for running a single unit of work inside a transaction.
 *
 * <p>Use {@link #execute(SessionFactory, boolean, String, OpContext, int)} when this
 * executor should manage the full session/transaction lifecycle.
 *
 * <p>Use {@link #execute(SessionFactory, boolean, String, OpContext, int, boolean)}
 * with {@code completeTransaction=false} when an outer {@code LockedContext} is
 * already managing the session — the unit of work runs in that session without
 * touching lifecycle.
 */
public class TransactionExecutor {

    private final DaoType daoType;
    private final Class<?> entityClass;
    private final ShardInfoProvider shardInfoProvider;
    private final TransactionObserver observer;

    public TransactionExecutor(final ShardInfoProvider shardInfoProvider,
                               final DaoType daoType,
                               final Class<?> entityClass,
                               final TransactionObserver observer) {
        this.daoType = daoType;
        this.entityClass = entityClass;
        this.shardInfoProvider = shardInfoProvider;
        this.observer = observer;
    }

    /** Manages the full session + transaction lifecycle. */
    public <T> T execute(SessionFactory sessionFactory,
                         boolean readOnly,
                         String commandName,
                         OpContext<T> opContext,
                         int shardId) {
        val context = buildExecutionContext(commandName, opContext, shardId);
        return observer.execute(context, () -> executeWithLifecycle(sessionFactory, readOnly, opContext));
    }

    /**
     * Manages the full lifecycle when {@code completeTransaction=true}.
     * When {@code completeTransaction=false}, runs the op in the caller's already-managed session.
     */
    public <T> T execute(SessionFactory sessionFactory,
                         boolean readOnly,
                         String commandName,
                         OpContext<T> opContext,
                         int shardId,
                         boolean completeTransaction) {
        val context = buildExecutionContext(commandName, opContext, shardId);
        if (completeTransaction) {
            return observer.execute(context, () -> executeWithLifecycle(sessionFactory, readOnly, opContext));
        }
        return observer.execute(context, () -> executeInExistingSession(opContext));
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private <T> T executeWithLifecycle(SessionFactory sessionFactory,
                                       boolean readOnly,
                                       OpContext<T> opContext) {
        TransactionLifecycle lifecycle = TransactionHandler.begin(sessionFactory, readOnly,
                opContext.isTransactionOptional());
        try {
            T result = opContext.apply(lifecycle.getSession());
            lifecycle.afterEnd();
            return result;
        } catch (Exception e) {
            lifecycle.onError();
            throw e;
        }
    }

    private <T> T executeInExistingSession(OpContext<T> opContext) {
        // Session lifecycle is managed by an outer LockedContext.
        // Op contexts that use this path obtain the session from the bound ManagedSessionContext
        // directly through the DAO, so the null session parameter is intentional.
        return opContext.apply(null);
    }

    private TransactionExecutionContext buildExecutionContext(String commandName,
                                                             OpContext<?> opContext,
                                                             int shardId) {
        return TransactionExecutionContext.builder()
                .commandName(commandName)
                .daoType(daoType)
                .entityClass(entityClass)
                .shardName(shardInfoProvider.shardName(shardId))
                .opContext(opContext)
                .build();
    }
}
```

- [ ] **Step 7.2 — Verify `TransactionExecutor` compiles**

```bash
mvn compile 2>&1 | grep "error:" | head -20
```

Expected: errors only in `LockedContext`, `WrapperDao`, `MultiTenantRelationalDao`, `MultiTenantLookupDao`.

---

## Task 8 — Update remaining callers

**Files:**
- Modify: `src/main/java/io/appform/dropwizard/sharding/dao/LockedContext.java`
- Modify: `src/main/java/io/appform/dropwizard/sharding/dao/WrapperDao.java`
- Modify: `src/main/java/io/appform/dropwizard/sharding/dao/MultiTenantRelationalDao.java`
- Modify: `src/main/java/io/appform/dropwizard/sharding/dao/MultiTenantLookupDao.java`

### 8.1 — `LockedContext.java`

- [ ] **Step 8.1.1 — Replace the `execute()` method body (around line 562)**

Find this block:
```java
        return observer.execute(executionContext, () -> {
            TransactionHandler transactionHandler = new TransactionHandler(sessionFactory, false);
            transactionHandler.beforeStart();
            try {
                val opContext = (LockAndExecute<T>) executionContext.getOpContext();
                return opContext.apply(transactionHandler.getSession());
            } catch (Exception e) {
                transactionHandler.onError();
                throw e;
            } finally {
                transactionHandler.afterEnd();
            }
        });
```

Replace with:
```java
        return observer.execute(executionContext, () -> {
            TransactionLifecycle lifecycle = TransactionHandler.begin(sessionFactory, false);
            try {
                val opContext = (LockAndExecute<T>) executionContext.getOpContext();
                return opContext.apply(lifecycle.getSession());
            } catch (Exception e) {
                lifecycle.onError();
                throw e;
            } finally {
                lifecycle.afterEnd();
            }
        });
```

- [ ] **Step 8.1.2 — Add the `TransactionLifecycle` import to `LockedContext.java`**

Add after existing `TransactionHandler` import:
```java
import io.appform.dropwizard.sharding.utils.TransactionLifecycle;
```

### 8.2 — `WrapperDao.java`

- [ ] **Step 8.2.1 — Replace the handler block inside the `MethodInterceptor` lambda (around line 91)**

Find:
```java
                final TransactionHandler transactionHandler = new TransactionHandler(sessionFactory, transaction.readOnly());
                try {
                    transactionHandler.beforeStart();
                    Object result = proxy.invokeSuper(obj, args);
                    transactionHandler.afterEnd();
                    return result;
                } catch (InvocationTargetException e) {
                    transactionHandler.onError();
                    throw e.getCause();
                } catch (Exception e) {
                    transactionHandler.onError();
                    throw e;
                }
```

Replace with:
```java
                final TransactionLifecycle lifecycle = TransactionHandler.begin(sessionFactory, transaction.readOnly());
                try {
                    Object result = proxy.invokeSuper(obj, args);
                    lifecycle.afterEnd();
                    return result;
                } catch (InvocationTargetException e) {
                    lifecycle.onError();
                    throw e.getCause();
                } catch (Exception e) {
                    lifecycle.onError();
                    throw e;
                }
```

- [ ] **Step 8.2.2 — Add `TransactionLifecycle` import to `WrapperDao.java`**

```java
import io.appform.dropwizard.sharding.utils.TransactionLifecycle;
```

### 8.3 — `MultiTenantRelationalDao.java` inner `ReadContext.executeImpl()`

- [ ] **Step 8.3.1 — Replace the handler block (around line 1912)**

Find:
```java
                TransactionHandler transactionHandler = new TransactionHandler(sessionFactory, true,
                        this.skipTransaction);
                transactionHandler.beforeStart();
                try {
                    val opContext = ((ReadOnlyForRelationalDao<T>) executionContext.getOpContext());
                    return opContext.apply(transactionHandler.getSession());
                } catch (Exception e) {
                    transactionHandler.onError();
                    throw e;
                } finally {
                    transactionHandler.afterEnd();
                }
```

Replace with:
```java
                TransactionLifecycle lifecycle = TransactionHandler.begin(sessionFactory, true,
                        this.skipTransaction);
                try {
                    val opContext = ((ReadOnlyForRelationalDao<T>) executionContext.getOpContext());
                    return opContext.apply(lifecycle.getSession());
                } catch (Exception e) {
                    lifecycle.onError();
                    throw e;
                } finally {
                    lifecycle.afterEnd();
                }
```

- [ ] **Step 8.3.2 — Add `TransactionLifecycle` import to `MultiTenantRelationalDao.java`**

```java
import io.appform.dropwizard.sharding.utils.TransactionLifecycle;
```

### 8.4 — `MultiTenantLookupDao.java` inner `ReadContext.executeImpl()`

- [ ] **Step 8.4.1 — Replace the handler block (around line 1465)**

Find:
```java
                TransactionHandler transactionHandler = new TransactionHandler(sessionFactory,
                        true,
                        this.skipTransaction);
                transactionHandler.beforeStart();
                try {
                    val opContext = ((ReadOnlyForLookupDao<T>) executionContext.getOpContext());
                    return opContext.apply(transactionHandler.getSession());
                } catch (Exception e) {
                    transactionHandler.onError();
                    throw e;
                } finally {
                    transactionHandler.afterEnd();
                }
```

Replace with:
```java
                TransactionLifecycle lifecycle = TransactionHandler.begin(sessionFactory, true,
                        this.skipTransaction);
                try {
                    val opContext = ((ReadOnlyForLookupDao<T>) executionContext.getOpContext());
                    return opContext.apply(lifecycle.getSession());
                } catch (Exception e) {
                    lifecycle.onError();
                    throw e;
                } finally {
                    lifecycle.afterEnd();
                }
```

- [ ] **Step 8.4.2 — Add `TransactionLifecycle` import to `MultiTenantLookupDao.java`**

```java
import io.appform.dropwizard.sharding.utils.TransactionLifecycle;
```

- [ ] **Step 8.5 — Verify full compile with no errors**

```bash
mvn compile -q
```
Expected: BUILD SUCCESS with zero errors.

---

## Task 9 — Full test run and commit

- [ ] **Step 9.1 — Run the full test suite**

```bash
mvn test -q 2>&1 | tail -30
```
Expected: BUILD SUCCESS, all tests pass.

If any tests fail, investigate stack traces. Common fixes:
- A mock not set up for `beginTransaction()` — add `when(session.beginTransaction()).thenReturn(transaction)` in test setUp.
- An import not added — check the compile output.

- [ ] **Step 9.2 — Commit everything**

```bash
git add \
  src/main/java/io/appform/dropwizard/sharding/utils/TransactionLifecycle.java \
  src/main/java/io/appform/dropwizard/sharding/utils/OwnedTransactionLifecycle.java \
  src/main/java/io/appform/dropwizard/sharding/utils/OptionalTransactionLifecycle.java \
  src/main/java/io/appform/dropwizard/sharding/utils/JoinedTransactionLifecycle.java \
  src/main/java/io/appform/dropwizard/sharding/utils/PassthroughLifecycle.java \
  src/main/java/io/appform/dropwizard/sharding/utils/TransactionHandler.java \
  src/main/java/io/appform/dropwizard/sharding/execution/TransactionExecutor.java \
  src/main/java/io/appform/dropwizard/sharding/dao/LockedContext.java \
  src/main/java/io/appform/dropwizard/sharding/dao/WrapperDao.java \
  src/main/java/io/appform/dropwizard/sharding/dao/MultiTenantRelationalDao.java \
  src/main/java/io/appform/dropwizard/sharding/dao/MultiTenantLookupDao.java \
  src/test/java/io/appform/dropwizard/sharding/utils/TransactionHandlerTest.java

git commit -m "refactor: replace TransactionHandler boolean flags with strategy pattern

- TransactionHandler becomes a pure factory (static begin() methods)
- Four lifecycle implementations replace sessionAcquired + skipCommit flags:
  OwnedTransactionLifecycle, OptionalTransactionLifecycle,
  JoinedTransactionLifecycle, PassthroughLifecycle
- TransactionExecutor: extract buildExecutionContext() helper; eliminate
  three repeated if(completeTransaction) guards via executeWithLifecycle()
  and executeInExistingSession()
- Update all five call sites: LockedContext, WrapperDao,
  MultiTenantRelationalDao, MultiTenantLookupDao, TransactionExecutor

Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

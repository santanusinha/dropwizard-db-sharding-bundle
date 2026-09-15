package io.appform.dropwizard.sharding.observers;

import io.appform.dropwizard.sharding.dao.operations.SaveWithParent;
import io.appform.dropwizard.sharding.execution.DaoType;
import io.appform.dropwizard.sharding.execution.TransactionExecutionContext;
import io.appform.dropwizard.sharding.sharding.CopyFromParent;
import io.appform.dropwizard.sharding.sharding.ParentEntity;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the copyIfDefaultOnly flag.
 * This flag is used during migration rollout to detect lingering manual setters.
 * When copyIfDefaultOnly=true and a non-default value is found, the operation fails.
 */
class CopyFromParentCopyIfDefaultOnlyTest {

    // Test entities with mixed primitive and reference types
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    static class TestParent {
        private String transactionId;
        private long amount;
        private String customerId;
        private Integer nullableField;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @ParentEntity(TestParent.class)
    static class TestChild {
        @CopyFromParent(field = "transactionId")
        private String txnId;

        @CopyFromParent(field = "amount")
        private long childAmount;

        @CopyFromParent(field = "customerId")
        private String customerId;

        @CopyFromParent(field = "nullableField")
        private Integer nullableField;

        private String ownField;
    }

    @Test
    void testCopyIfDefaultOnly_allDefaults_copiesSuccessfully() {
        CopyFromParentObserver observer = CopyFromParentObserver.builder()
                .copyEnabled(true)
                .copyIfDefaultOnly(true)
                .build();

        TestParent parent = TestParent.builder()
                .transactionId("TXN-123")
                .amount(1000L)
                .customerId("CUST-1")
                .nullableField(42)
                .build();

        TestChild child = new TestChild();  // All default values

        SaveWithParent<TestChild, TestChild, TestParent> opContext =
                SaveWithParent.<TestChild, TestChild, TestParent>builder()
                        .entity(child)
                        .parent(parent)
                        .saver(e -> e)
                        .build();

        TransactionExecutionContext ctx = createContext(opContext);
        TestChild result = observer.execute(ctx, () -> opContext.apply(null));

        assertEquals("TXN-123", result.getTxnId());
        assertEquals(1000L, result.getChildAmount());
        assertEquals("CUST-1", result.getCustomerId());
        assertEquals(42, result.getNullableField());
    }

    @Test
    void testCopyIfDefaultOnly_nonDefaultReferenceType_throwsException() {
        CopyFromParentObserver observer = CopyFromParentObserver.builder()
                .copyEnabled(true)
                .copyIfDefaultOnly(true)
                .build();

        TestParent parent = TestParent.builder()
                .transactionId("PARENT-TXN")
                .amount(1000L)
                .customerId("PARENT-CUST")
                .build();

        TestChild child = TestChild.builder()
                .txnId("MANUAL-SETTER-VALUE")  // Non-default - should fail
                .childAmount(0L)
                .customerId(null)
                .build();

        SaveWithParent<TestChild, TestChild, TestParent> opContext =
                SaveWithParent.<TestChild, TestChild, TestParent>builder()
                        .entity(child)
                        .parent(parent)
                        .saver(e -> e)
                        .build();

        TransactionExecutionContext ctx = createContext(opContext);

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> observer.execute(ctx, () -> opContext.apply(null)));

        assertTrue(exception.getMessage().contains("COPY_IF_DEFAULT_VIOLATION"));
        assertTrue(exception.getMessage().contains("txnId"));
        assertTrue(exception.getMessage().contains("MANUAL-SETTER-VALUE"));
    }

    @Test
    void testCopyIfDefaultOnly_nonDefaultPrimitive_throwsException() {
        CopyFromParentObserver observer = CopyFromParentObserver.builder()
                .copyEnabled(true)
                .copyIfDefaultOnly(true)
                .build();

        TestParent parent = TestParent.builder()
                .transactionId("TXN")
                .amount(1000L)
                .customerId("CUST")
                .build();

        TestChild child = TestChild.builder()
                .txnId(null)
                .childAmount(999L)  // Non-default primitive - should fail
                .customerId(null)
                .build();

        SaveWithParent<TestChild, TestChild, TestParent> opContext =
                SaveWithParent.<TestChild, TestChild, TestParent>builder()
                        .entity(child)
                        .parent(parent)
                        .saver(e -> e)
                        .build();

        TransactionExecutionContext ctx = createContext(opContext);

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> observer.execute(ctx, () -> opContext.apply(null)));

        assertTrue(exception.getMessage().contains("COPY_IF_DEFAULT_VIOLATION"));
        assertTrue(exception.getMessage().contains("childAmount"));
    }

    @Test
    void testCopyIfDefaultOnly_false_copiesEverything() {
        CopyFromParentObserver observer = CopyFromParentObserver.builder()
                .copyEnabled(true)
                .copyIfDefaultOnly(false)  // Normal mode - overwrites everything
                .build();

        TestParent parent = TestParent.builder()
                .transactionId("PARENT-TXN")
                .amount(1000L)
                .customerId("PARENT-CUST")
                .build();

        TestChild child = TestChild.builder()
                .txnId("OLD-VALUE")
                .childAmount(999L)
                .customerId("OLD-CUST")
                .build();

        SaveWithParent<TestChild, TestChild, TestParent> opContext =
                SaveWithParent.<TestChild, TestChild, TestParent>builder()
                        .entity(child)
                        .parent(parent)
                        .saver(e -> e)
                        .build();

        TransactionExecutionContext ctx = createContext(opContext);
        TestChild result = observer.execute(ctx, () -> opContext.apply(null));

        assertEquals("PARENT-TXN", result.getTxnId());
        assertEquals(1000L, result.getChildAmount());
        assertEquals("PARENT-CUST", result.getCustomerId());
    }

    @Test
    void testCopyDisabled_withCopyIfDefaultOnly_noEffect() {
        CopyFromParentObserver observer = CopyFromParentObserver.builder()
                .copyEnabled(false)  // Copy disabled
                .copyIfDefaultOnly(true)  // Should have no effect
                .build();

        TestParent parent = TestParent.builder()
                .transactionId("PARENT-TXN")
                .amount(1000L)
                .build();

        TestChild child = TestChild.builder()
                .txnId("OLD-VALUE")
                .childAmount(999L)
                .build();

        SaveWithParent<TestChild, TestChild, TestParent> opContext =
                SaveWithParent.<TestChild, TestChild, TestParent>builder()
                        .entity(child)
                        .parent(parent)
                        .saver(e -> e)
                        .build();

        TransactionExecutionContext ctx = createContext(opContext);
        TestChild result = observer.execute(ctx, () -> opContext.apply(null));

        // Nothing should be copied (copyEnabled=false)
        assertEquals("OLD-VALUE", result.getTxnId());
        assertEquals(999L, result.getChildAmount());
    }

    @Test
    void testCopyIfDefaultOnly_nullParentValue_copies() {
        CopyFromParentObserver observer = CopyFromParentObserver.builder()
                .copyEnabled(true)
                .copyIfDefaultOnly(true)
                .build();

        TestParent parent = TestParent.builder()
                .transactionId(null)  // Null parent value
                .amount(0L)           // Zero parent value
                .build();

        TestChild child = new TestChild();  // All defaults

        SaveWithParent<TestChild, TestChild, TestParent> opContext =
                SaveWithParent.<TestChild, TestChild, TestParent>builder()
                        .entity(child)
                        .parent(parent)
                        .saver(e -> e)
                        .build();

        TransactionExecutionContext ctx = createContext(opContext);
        TestChild result = observer.execute(ctx, () -> opContext.apply(null));

        assertNull(result.getTxnId());
        assertEquals(0L, result.getChildAmount());
    }

    private <R> TransactionExecutionContext createContext(
            io.appform.dropwizard.sharding.dao.operations.OpContext<R> opContext) {
        return TransactionExecutionContext.builder()
                .opContext(opContext)
                .commandName("testCommand")
                .daoType(DaoType.RELATIONAL)
                .entityClass(TestChild.class)
                .shardName("shard-0")
                .build();
    }
}

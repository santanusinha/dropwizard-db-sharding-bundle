package io.appform.dropwizard.sharding.observers;

import io.appform.dropwizard.sharding.dao.operations.Save;
import io.appform.dropwizard.sharding.dao.operations.SaveWithParent;
import io.appform.dropwizard.sharding.execution.DaoType;
import io.appform.dropwizard.sharding.execution.TransactionExecutionContext;
import io.appform.dropwizard.sharding.sharding.CopyFromParent;
import io.appform.dropwizard.sharding.sharding.ParentEntity;
import io.appform.dropwizard.sharding.utils.CopyFromParentUtils;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CopyFromParentObserverTest {

    // Test entities
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    static class TestParent {
        private String transactionId;
        private long amount;
        private String customerId;
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

        private String ownField;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @ParentEntity(TestParent.class)
    static class MismatchChild {
        @CopyFromParent(field = "transactionId")
        private String txnId;

        @CopyFromParent(field = "amount")
        private long childAmount;
    }

    // Test: copyEnabled=true, mismatchDetectionEnabled=false (default behavior)

    @Test
    void testDefaultConfig_copiesFields_noMismatchDetection() {
        CopyFromParentObserver observer = CopyFromParentObserver.builder()
                .copyEnabled(true)
                .mismatchDetectionEnabled(false)
                .build();

        TestParent parent = TestParent.builder()
                .transactionId("TXN-123")
                .amount(500)
                .build();
        TestChild child = TestChild.builder()
                .ownField("mine")
                .build();

        SaveWithParent<TestChild, TestChild, TestParent> opContext =
                SaveWithParent.<TestChild, TestChild, TestParent>saveWithParentBuilder()
                        .entity(child)
                        .parent(parent)
                        .saver(e -> e)
                        .build();

        TransactionExecutionContext ctx = createContext(opContext);

        AtomicBoolean supplierCalled = new AtomicBoolean(false);
        TestChild result = observer.execute(ctx, () -> {
            supplierCalled.set(true);
            return opContext.apply(null);
        });

        assertTrue(supplierCalled.get());
        assertEquals("TXN-123", result.getTxnId(), "Field should be copied from parent");
        assertEquals(500, result.getChildAmount(), "Field should be copied from parent");
        assertEquals("mine", result.getOwnField(), "Non-annotated field should not be touched");
    }

    @Test
    void testDefaultConfig_overwritesExistingValues() {
        CopyFromParentObserver observer = CopyFromParentObserver.builder()
                .copyEnabled(true)
                .mismatchDetectionEnabled(false)
                .build();

        TestParent parent = TestParent.builder()
                .transactionId("PARENT-TXN")
                .amount(999)
                .build();
        TestChild child = TestChild.builder()
                .txnId("OLD-TXN")
                .childAmount(1)
                .build();

        SaveWithParent<TestChild, TestChild, TestParent> opContext =
                SaveWithParent.<TestChild, TestChild, TestParent>saveWithParentBuilder()
                        .entity(child)
                        .parent(parent)
                        .saver(e -> e)
                        .build();

        TransactionExecutionContext ctx = createContext(opContext);
        TestChild result = observer.execute(ctx, () -> opContext.apply(null));

        assertEquals("PARENT-TXN", result.getTxnId(), "Should overwrite existing value");
        assertEquals(999, result.getChildAmount(), "Should overwrite existing value");
    }

    @Test
    void testCopyAndDetect_copiesFields_detectsMismatches() {
        CopyFromParentMismatchListener mockListener = mock(CopyFromParentMismatchListener.class);

        CopyFromParentObserver observer = CopyFromParentObserver.builder()
                .copyEnabled(true)
                .mismatchDetectionEnabled(true)
                .mismatchListener(() -> mockListener)
                .build();

        TestParent parent = TestParent.builder()
                .transactionId("PARENT-TXN")
                .amount(500)
                .build();
        MismatchChild child = MismatchChild.builder()
                .txnId("CHILD-TXN")  // Mismatch
                .childAmount(100)     // Mismatch
                .build();

        SaveWithParent<MismatchChild, MismatchChild, TestParent> opContext =
                SaveWithParent.<MismatchChild, MismatchChild, TestParent>saveWithParentBuilder()
                        .entity(child)
                        .parent(parent)
                        .saver(e -> e)
                        .build();

        TransactionExecutionContext ctx = createContext(opContext);
        MismatchChild result = observer.execute(ctx, () -> opContext.apply(null));

        // Verify mismatch detection was called
        ArgumentCaptor<List<CopyFromParentUtils.FieldMismatch>> mismatchCaptor =
                ArgumentCaptor.forClass(List.class);
        verify(mockListener).onMismatches(eq(parent), eq(child), mismatchCaptor.capture());

        List<CopyFromParentUtils.FieldMismatch> mismatches = mismatchCaptor.getValue();
        assertEquals(2, mismatches.size(), "Should detect 2 mismatches");

        // Verify fields were still copied (mismatch detection doesn't prevent copy)
        assertEquals("PARENT-TXN", result.getTxnId());
        assertEquals(500, result.getChildAmount());
    }

    @Test
    void testCopyAndDetect_noMismatchesDetected_listenerNotCalled() {
        CopyFromParentMismatchListener mockListener = mock(CopyFromParentMismatchListener.class);

        CopyFromParentObserver observer = CopyFromParentObserver.builder()
                .copyEnabled(true)
                .mismatchDetectionEnabled(true)
                .mismatchListener(() -> mockListener)
                .build();

        TestParent parent = TestParent.builder()
                .transactionId("SAME-TXN")
                .amount(100)
                .build();
        MismatchChild child = MismatchChild.builder()
                .txnId("SAME-TXN")  // Matches parent
                .childAmount(100)    // Matches parent
                .build();

        SaveWithParent<MismatchChild, MismatchChild, TestParent> opContext =
                SaveWithParent.<MismatchChild, MismatchChild, TestParent>saveWithParentBuilder()
                        .entity(child)
                        .parent(parent)
                        .saver(e -> e)
                        .build();

        TransactionExecutionContext ctx = createContext(opContext);
        observer.execute(ctx, () -> opContext.apply(null));

        // Listener should not be called when there are no mismatches
        verify(mockListener, never()).onMismatches(any(), any(), any());
    }

    @Test
    void testCopyAndDetect_listenerExceptionDoesNotBreakCopy() {
        CopyFromParentMismatchListener mockListener = mock(CopyFromParentMismatchListener.class);
        doThrow(new RuntimeException("Listener explosion"))
                .when(mockListener).onMismatches(any(), any(), any());

        CopyFromParentObserver observer = CopyFromParentObserver.builder()
                .copyEnabled(true)
                .mismatchDetectionEnabled(true)
                .mismatchListener(() -> mockListener)
                .build();

        TestParent parent = TestParent.builder()
                .transactionId("PARENT-TXN")
                .amount(500)
                .build();
        MismatchChild child = MismatchChild.builder()
                .txnId("CHILD-TXN")
                .childAmount(100)
                .build();

        SaveWithParent<MismatchChild, MismatchChild, TestParent> opContext =
                SaveWithParent.<MismatchChild, MismatchChild, TestParent>saveWithParentBuilder()
                        .entity(child)
                        .parent(parent)
                        .saver(e -> e)
                        .build();

        TransactionExecutionContext ctx = createContext(opContext);

        // Should not throw - listener exception should be caught
        MismatchChild result = observer.execute(ctx, () -> opContext.apply(null));

        // Fields should still be copied despite listener failure
        assertEquals("PARENT-TXN", result.getTxnId());
        assertEquals(500, result.getChildAmount());
    }

    @Test
    void testNoCopyNoDetect_doesNotCopyFields() {
        CopyFromParentObserver observer = CopyFromParentObserver.builder()
                .copyEnabled(false)
                .mismatchDetectionEnabled(false)
                .build();

        TestParent parent = TestParent.builder()
                .transactionId("PARENT-TXN")
                .amount(500)
                .build();
        TestChild child = TestChild.builder()
                .txnId("ORIGINAL-TXN")
                .childAmount(100)
                .build();

        SaveWithParent<TestChild, TestChild, TestParent> opContext =
                SaveWithParent.<TestChild, TestChild, TestParent>saveWithParentBuilder()
                        .entity(child)
                        .parent(parent)
                        .saver(e -> e)
                        .build();

        TransactionExecutionContext ctx = createContext(opContext);
        TestChild result = observer.execute(ctx, () -> opContext.apply(null));

        // Fields should NOT be copied
        assertEquals("ORIGINAL-TXN", result.getTxnId(), "Field should not be copied");
        assertEquals(100, result.getChildAmount(), "Field should not be copied");
    }

    @Test
    void testValidationMode_detectsButDoesNotCopy() {
        CopyFromParentMismatchListener mockListener = mock(CopyFromParentMismatchListener.class);

        CopyFromParentObserver observer = CopyFromParentObserver.builder()
                .copyEnabled(false)
                .mismatchDetectionEnabled(true)
                .mismatchListener(() -> mockListener)
                .build();

        TestParent parent = TestParent.builder()
                .transactionId("PARENT-TXN")
                .amount(500)
                .build();
        MismatchChild child = MismatchChild.builder()
                .txnId("CHILD-TXN")
                .childAmount(100)
                .build();

        SaveWithParent<MismatchChild, MismatchChild, TestParent> opContext =
                SaveWithParent.<MismatchChild, MismatchChild, TestParent>saveWithParentBuilder()
                        .entity(child)
                        .parent(parent)
                        .saver(e -> e)
                        .build();

        TransactionExecutionContext ctx = createContext(opContext);
        MismatchChild result = observer.execute(ctx, () -> opContext.apply(null));

        // Verify mismatch detection was called
        verify(mockListener).onMismatches(eq(parent), eq(child), any());

        // But fields should NOT be copied
        assertEquals("CHILD-TXN", result.getTxnId(), "Field should not be copied in validation mode");
        assertEquals(100, result.getChildAmount(), "Field should not be copied in validation mode");
    }

    @Test
    void testLazyListenerInit_supplierCalledOnlyOnce() {
        AtomicInteger supplierCallCount = new AtomicInteger(0);
        CopyFromParentMismatchListener mockListener = mock(CopyFromParentMismatchListener.class);

        CopyFromParentObserver observer = CopyFromParentObserver.builder()
                .copyEnabled(true)
                .mismatchDetectionEnabled(true)
                .mismatchListener(() -> {
                    supplierCallCount.incrementAndGet();
                    return mockListener;
                })
                .build();

        TestParent parent = TestParent.builder().transactionId("TXN").amount(100).build();
        MismatchChild child = MismatchChild.builder().txnId("DIFF").childAmount(50).build();

        SaveWithParent<MismatchChild, MismatchChild, TestParent> opContext =
                SaveWithParent.<MismatchChild, MismatchChild, TestParent>saveWithParentBuilder()
                        .entity(child)
                        .parent(parent)
                        .saver(e -> e)
                        .build();

        TransactionExecutionContext ctx = createContext(opContext);

        // Execute multiple times
        observer.execute(ctx, () -> opContext.apply(null));
        observer.execute(ctx, () -> opContext.apply(null));
        observer.execute(ctx, () -> opContext.apply(null));

        // Supplier should be called only once (lazy init + caching)
        assertEquals(1, supplierCallCount.get(), "Supplier should be called only once");
    }

    @Test
    void testLazyListenerInit_supplierFailure_failsFast_notCached_retriedNextTransaction() {
        AtomicInteger attempts = new AtomicInteger(0);
        CopyFromParentMismatchListener mockListener = mock(CopyFromParentMismatchListener.class);
        RuntimeException cause = new RuntimeException("Guice not initialized");
        CopyFromParentObserver observer = CopyFromParentObserver.builder()
                .copyEnabled(true)
                .mismatchDetectionEnabled(true)
                .mismatchListener(() -> {
                    if (attempts.incrementAndGet() == 1) {
                        throw cause;
                    }
                    return mockListener;
                })
                .build();

        TestParent parent = TestParent.builder().transactionId("TXN").amount(100).build();
        TestChild child = TestChild.builder().build();
        SaveWithParent<TestChild, TestChild, TestParent> opContext =
                SaveWithParent.<TestChild, TestChild, TestParent>saveWithParentBuilder()
                        .entity(child)
                        .parent(parent)
                        .saver(e -> e)
                        .build();
        TransactionExecutionContext ctx = createContext(opContext);
        AtomicBoolean saveExecuted = new AtomicBoolean(false);

        // Detection is enabled but cannot run -> config error -> transaction fails before the save
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> observer.execute(ctx, () -> {
                    saveExecuted.set(true);
                    return opContext.apply(null);
                }));
        assertSame(cause, ex.getCause());
        assertFalse(saveExecuted.get(), "Save must not run when the listener cannot be resolved");
        assertNull(child.getTxnId(), "Entity must not be touched when the listener cannot be resolved");

        // Failure is not cached: the next transaction retries and succeeds
        TestChild result = observer.execute(ctx, () -> opContext.apply(null));
        assertEquals("TXN", result.getTxnId());
        assertEquals(2, attempts.get());

        // Success is cached
        observer.execute(ctx, () -> opContext.apply(null));
        assertEquals(2, attempts.get());
    }

    @Test
    void testLazyListenerInit_supplierReturnsNull_failsFast() {
        CopyFromParentObserver observer = CopyFromParentObserver.builder()
                .mismatchDetectionEnabled(true)
                .mismatchListener(() -> null)
                .build();

        SaveWithParent<TestChild, TestChild, TestParent> opContext =
                SaveWithParent.<TestChild, TestChild, TestParent>saveWithParentBuilder()
                        .entity(TestChild.builder().build())
                        .parent(TestParent.builder().transactionId("TXN").build())
                        .saver(e -> e)
                        .build();
        TransactionExecutionContext ctx = createContext(opContext);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> observer.execute(ctx, () -> opContext.apply(null)));
        assertTrue(ex.getMessage().contains("returned null"));
    }

    @Test
    void testNonSaveWithParent_proceedsWithoutError_andDoesNotResolveConfigOrListener() {
        AtomicInteger configCalls = new AtomicInteger(0);
        AtomicInteger listenerCalls = new AtomicInteger(0);
        CopyFromParentObserver observer = CopyFromParentObserver.builder()
                .copyEnabled(true)
                .mismatchDetectionEnabled(true)
                .mismatchListener(() -> {
                    listenerCalls.incrementAndGet();
                    return null; // would fail fast if it were ever resolved
                })
                .configSupplier(() -> {
                    configCalls.incrementAndGet();
                    return null;
                })
                .build();

        Save<String, String> saveOp = Save.<String, String>builder()
                .entity("entity")
                .saver(e -> e)
                .build();

        TransactionExecutionContext ctx = createContext(saveOp);

        String result = observer.execute(ctx, () -> "done");

        assertEquals("done", result);
        assertEquals(0, configCalls.get(), "Config must not be resolved for non SaveWithParent operations");
        assertEquals(0, listenerCalls.get(), "Listener must not be resolved for non SaveWithParent operations");
    }

    // Test: Builder validation
    @Test
    void testBuilder_mismatchDetectionEnabledWithoutListener_throwsException() {
        var builder = CopyFromParentObserver.builder()
                .copyEnabled(true)
                .mismatchDetectionEnabled(true);
        assertThrows(IllegalArgumentException.class, builder::build,
                "Should throw when mismatchDetectionEnabled=true but listener is null");
    }

    @Test
    void testBuilder_mismatchDetectionDisabled_listenerNotRequired() {
        assertDoesNotThrow(() ->
                CopyFromParentObserver.builder()
                        .copyEnabled(true)
                        .mismatchDetectionEnabled(false)
                        .build(),
                "Should not require listener when mismatchDetectionEnabled=false");
    }

    @Test
    void testBuilder_defaultValues() {
        // Default: copyEnabled=true, mismatchDetectionEnabled=false
        CopyFromParentObserver observer = CopyFromParentObserver.builder().build();

        TestParent parent = TestParent.builder().transactionId("TXN").amount(100).build();
        TestChild child = TestChild.builder().build();

        SaveWithParent<TestChild, TestChild, TestParent> opContext =
                SaveWithParent.<TestChild, TestChild, TestParent>saveWithParentBuilder()
                        .entity(child)
                        .parent(parent)
                        .saver(e -> e)
                        .build();

        TransactionExecutionContext ctx = createContext(opContext);
        TestChild result = observer.execute(ctx, () -> opContext.apply(null));

        // Should copy by default
        assertEquals("TXN", result.getTxnId());
        assertEquals(100, result.getChildAmount());
    }

    @Test
    void testMultipleFieldsCopied() {
        CopyFromParentObserver observer = CopyFromParentObserver.builder()
                .copyEnabled(true)
                .build();

        TestParent parent = TestParent.builder()
                .transactionId("TXN-999")
                .amount(12345)
                .customerId("CUST-ABC")
                .build();
        TestChild child = TestChild.builder()
                .ownField("preserved")
                .build();

        SaveWithParent<TestChild, TestChild, TestParent> opContext =
                SaveWithParent.<TestChild, TestChild, TestParent>saveWithParentBuilder()
                        .entity(child)
                        .parent(parent)
                        .saver(e -> e)
                        .build();

        TransactionExecutionContext ctx = createContext(opContext);
        TestChild result = observer.execute(ctx, () -> opContext.apply(null));

        assertEquals("TXN-999", result.getTxnId());
        assertEquals(12345, result.getChildAmount());
        assertEquals("preserved", result.getOwnField());
    }

    @Test
    void testNullParentValues_copiedCorrectly() {
        CopyFromParentObserver observer = CopyFromParentObserver.builder()
                .copyEnabled(true)
                .build();

        TestParent parent = TestParent.builder()
                .transactionId(null)  // Null value
                .amount(0)            // Zero value
                .build();
        TestChild child = TestChild.builder()
                .txnId("EXISTING")
                .childAmount(999)
                .build();

        SaveWithParent<TestChild, TestChild, TestParent> opContext =
                SaveWithParent.<TestChild, TestChild, TestParent>saveWithParentBuilder()
                        .entity(child)
                        .parent(parent)
                        .saver(e -> e)
                        .build();

        TransactionExecutionContext ctx = createContext(opContext);
        TestChild result = observer.execute(ctx, () -> opContext.apply(null));

        assertNull(result.getTxnId(), "Null value should be copied");
        assertEquals(0, result.getChildAmount(), "Zero value should be copied");
    }

    @Test
    void testAfterSaveFunction_executedAfterCopy() {
        CopyFromParentObserver observer = CopyFromParentObserver.builder()
                .copyEnabled(true)
                .build();

        TestParent parent = TestParent.builder()
                .transactionId("TXN")
                .amount(100)
                .build();
        TestChild child = TestChild.builder().build();

        AtomicBoolean afterSaveCalled = new AtomicBoolean(false);

        SaveWithParent<TestChild, String, TestParent> opContext =
                SaveWithParent.<TestChild, String, TestParent>saveWithParentBuilder()
                        .entity(child)
                        .parent(parent)
                        .saver(e -> e)
                        .afterSave(e -> {
                            afterSaveCalled.set(true);
                            // Verify fields are copied before afterSave is called
                            assertEquals("TXN", e.getTxnId());
                            assertEquals(100, e.getChildAmount());
                            return "transformed";
                        })
                        .build();

        TransactionExecutionContext ctx = createContext(opContext);
        String result = observer.execute(ctx, () -> opContext.apply(null));

        assertTrue(afterSaveCalled.get());
        assertEquals("transformed", result);
    }

    private static <R> TransactionExecutionContext createContext(io.appform.dropwizard.sharding.dao.operations.OpContext<R> opContext) {
        return TransactionExecutionContext.builder()
                .opContext(opContext)
                .commandName("testCommand")
                .daoType(DaoType.RELATIONAL)
                .entityClass(TestChild.class)
                .shardName("shard-0")
                .build();
    }

    /**
     * Simple config implementation for testing
     */
    @Data
    @Builder
    static class TestConfig implements CopyFromParentObserver.CopyFromParentObserverConfig {
        private Boolean copyEnabled;
        private Boolean mismatchDetectionEnabled;
        private Boolean copyIfDefaultOnly;
    }

    @Test
    void testConfigSupplier_copiesFields() {
        TestConfig config = TestConfig.builder()
                .copyEnabled(true)
                .mismatchDetectionEnabled(false)
                .copyIfDefaultOnly(false)
                .build();

        CopyFromParentObserver observer = CopyFromParentObserver.builder()
                .configSupplier(() -> config)
                .build();

        TestParent parent = TestParent.builder()
                .transactionId("TXN-123")
                .amount(500)
                .build();
        TestChild child = TestChild.builder()
                .ownField("mine")
                .build();

        SaveWithParent<TestChild, TestChild, TestParent> opContext =
                SaveWithParent.<TestChild, TestChild, TestParent>saveWithParentBuilder()
                        .entity(child)
                        .parent(parent)
                        .saver(e -> e)
                        .build();

        TransactionExecutionContext ctx = createContext(opContext);
        TestChild result = observer.execute(ctx, () -> opContext.apply(null));

        assertEquals("TXN-123", result.getTxnId(), "Field should be copied from parent via config supplier");
        assertEquals(500, result.getChildAmount(), "Field should be copied from parent via config supplier");
        assertEquals("mine", result.getOwnField(), "Non-annotated field should not be touched");
    }

    @Test
    void testConfigSupplier_disablesCopying() {
        TestConfig config = TestConfig.builder()
                .copyEnabled(false)
                .mismatchDetectionEnabled(false)
                .copyIfDefaultOnly(false)
                .build();

        CopyFromParentObserver observer = CopyFromParentObserver.builder()
                .configSupplier(() -> config)
                .build();

        TestParent parent = TestParent.builder()
                .transactionId("PARENT-TXN")
                .amount(500)
                .build();
        TestChild child = TestChild.builder()
                .txnId("ORIGINAL-TXN")
                .childAmount(100)
                .build();

        SaveWithParent<TestChild, TestChild, TestParent> opContext =
                SaveWithParent.<TestChild, TestChild, TestParent>saveWithParentBuilder()
                        .entity(child)
                        .parent(parent)
                        .saver(e -> e)
                        .build();

        TransactionExecutionContext ctx = createContext(opContext);
        TestChild result = observer.execute(ctx, () -> opContext.apply(null));

        assertEquals("ORIGINAL-TXN", result.getTxnId(), "Field should not be copied when config disables it");
        assertEquals(100, result.getChildAmount(), "Field should not be copied when config disables it");
    }

    @Test
    void testConfigSupplier_enablesMismatchDetection() {
        CopyFromParentMismatchListener mockListener = mock(CopyFromParentMismatchListener.class);

        TestConfig config = TestConfig.builder()
                .copyEnabled(true)
                .mismatchDetectionEnabled(true)
                .copyIfDefaultOnly(false)
                .build();

        CopyFromParentObserver observer = CopyFromParentObserver.builder()
                .configSupplier(() -> config)
                .mismatchListener(() -> mockListener)
                .build();

        TestParent parent = TestParent.builder()
                .transactionId("PARENT-TXN")
                .amount(500)
                .build();
        MismatchChild child = MismatchChild.builder()
                .txnId("CHILD-TXN")  // Mismatch
                .childAmount(100)     // Mismatch
                .build();

        SaveWithParent<MismatchChild, MismatchChild, TestParent> opContext =
                SaveWithParent.<MismatchChild, MismatchChild, TestParent>saveWithParentBuilder()
                        .entity(child)
                        .parent(parent)
                        .saver(e -> e)
                        .build();

        TransactionExecutionContext ctx = createContext(opContext);
        MismatchChild result = observer.execute(ctx, () -> opContext.apply(null));

        // Verify mismatch detection was called
        ArgumentCaptor<List<CopyFromParentUtils.FieldMismatch>> mismatchCaptor =
                ArgumentCaptor.forClass(List.class);
        verify(mockListener).onMismatches(eq(parent), eq(child), mismatchCaptor.capture());

        List<CopyFromParentUtils.FieldMismatch> mismatches = mismatchCaptor.getValue();
        assertEquals(2, mismatches.size(), "Should detect 2 mismatches via config supplier");

        assertEquals("PARENT-TXN", result.getTxnId());
        assertEquals(500, result.getChildAmount());
    }

    @Test
    void testConfigSupplier_precedenceOverStaticValues() {
        // Config supplier returns false, static value is true
        // Config supplier should win
        TestConfig config = TestConfig.builder()
                .copyEnabled(false)
                .mismatchDetectionEnabled(false)
                .copyIfDefaultOnly(false)
                .build();

        CopyFromParentObserver observer = CopyFromParentObserver.builder()
                .copyEnabled(true)  // Static value - should be overridden
                .mismatchDetectionEnabled(true)  // Static value - should be overridden
                .configSupplier(() -> config)  // Dynamic config takes precedence
                .mismatchListener(() -> mock(CopyFromParentMismatchListener.class))
                .build();

        TestParent parent = TestParent.builder()
                .transactionId("PARENT-TXN")
                .amount(500)
                .build();
        TestChild child = TestChild.builder()
                .txnId("ORIGINAL-TXN")
                .childAmount(100)
                .build();

        SaveWithParent<TestChild, TestChild, TestParent> opContext =
                SaveWithParent.<TestChild, TestChild, TestParent>saveWithParentBuilder()
                        .entity(child)
                        .parent(parent)
                        .saver(e -> e)
                        .build();

        TransactionExecutionContext ctx = createContext(opContext);
        TestChild result = observer.execute(ctx, () -> opContext.apply(null));

        // Config supplier (false) should override static value (true)
        assertEquals("ORIGINAL-TXN", result.getTxnId(), "Config supplier should override static value");
        assertEquals(100, result.getChildAmount(), "Config supplier should override static value");
    }

    @Test
    void testConfigSupplier_nullValues_fallbackToStatic() {
        // Config supplier returns null for some values
        // Should fall back to static values
        TestConfig config = TestConfig.builder()
                .copyEnabled(null)  // Null - should fall back to static
                .mismatchDetectionEnabled(null)  // Null - should fall back to static
                .copyIfDefaultOnly(null)  // Null - should fall back to static
                .build();

        CopyFromParentObserver observer = CopyFromParentObserver.builder()
                .copyEnabled(true)  // Static fallback
                .mismatchDetectionEnabled(false)  // Static fallback
                .configSupplier(() -> config)
                .build();

        TestParent parent = TestParent.builder()
                .transactionId("TXN-123")
                .amount(500)
                .build();
        TestChild child = TestChild.builder().build();

        SaveWithParent<TestChild, TestChild, TestParent> opContext =
                SaveWithParent.<TestChild, TestChild, TestParent>saveWithParentBuilder()
                        .entity(child)
                        .parent(parent)
                        .saver(e -> e)
                        .build();

        TransactionExecutionContext ctx = createContext(opContext);
        TestChild result = observer.execute(ctx, () -> opContext.apply(null));

        // Should use static value (true) since config returns null
        assertEquals("TXN-123", result.getTxnId(), "Should fall back to static value when config returns null");
        assertEquals(500, result.getChildAmount(), "Should fall back to static value when config returns null");
    }

    @Test
    void testConfigSupplier_nullValues_fallbackToDefaults() {
        // Config supplier returns null, no static values
        // Should fall back to defaults (copyEnabled=true, others=false)
        TestConfig config = TestConfig.builder()
                .copyEnabled(null)
                .mismatchDetectionEnabled(null)
                .copyIfDefaultOnly(null)
                .build();

        CopyFromParentObserver observer = CopyFromParentObserver.builder()
                .configSupplier(() -> config)
                .build();

        TestParent parent = TestParent.builder()
                .transactionId("TXN-123")
                .amount(500)
                .build();
        TestChild child = TestChild.builder().build();

        SaveWithParent<TestChild, TestChild, TestParent> opContext =
                SaveWithParent.<TestChild, TestChild, TestParent>saveWithParentBuilder()
                        .entity(child)
                        .parent(parent)
                        .saver(e -> e)
                        .build();

        TransactionExecutionContext ctx = createContext(opContext);
        TestChild result = observer.execute(ctx, () -> opContext.apply(null));

        // Should use default value (true) for copyEnabled
        assertEquals("TXN-123", result.getTxnId(), "Should fall back to default value (true) when config and static are null");
        assertEquals(500, result.getChildAmount(), "Should fall back to default value (true) when config and static are null");
    }

    @Test
    void testConfigSupplier_exception_fallbackToStatic() {
        AtomicInteger callCount = new AtomicInteger(0);

        CopyFromParentObserver observer = CopyFromParentObserver.builder()
                .copyEnabled(true)  // Static fallback
                .configSupplier(() -> {
                    callCount.incrementAndGet();
                    throw new RuntimeException("Config service unavailable");
                })
                .build();

        TestParent parent = TestParent.builder()
                .transactionId("TXN-123")
                .amount(500)
                .build();
        TestChild child = TestChild.builder().build();

        SaveWithParent<TestChild, TestChild, TestParent> opContext =
                SaveWithParent.<TestChild, TestChild, TestParent>saveWithParentBuilder()
                        .entity(child)
                        .parent(parent)
                        .saver(e -> e)
                        .build();

        TransactionExecutionContext ctx = createContext(opContext);
        TestChild result = observer.execute(ctx, () -> opContext.apply(null));

        // Should fall back to static value when config supplier throws
        assertEquals("TXN-123", result.getTxnId(), "Should fall back to static value when config supplier throws");
        assertEquals(500, result.getChildAmount(), "Should fall back to static value when config supplier throws");
        
        // Config supplier is called once for this single transaction
        assertEquals(1, callCount.get(), "Config supplier should be called once per transaction");
    }

    @Test
    void testConfigSupplier_exceptionAfterSuccessfulResolution_keepsLastResolvedConfig() {
        // A transient failure of the config source must not flip behaviour back to static values.
        AtomicBoolean fail = new AtomicBoolean(false);
        TestConfig config = TestConfig.builder().copyEnabled(false).build();
        CopyFromParentObserver observer = CopyFromParentObserver.builder()
                .copyEnabled(true) // static would enable copy
                .configSupplier(() -> {
                    if (fail.get()) {
                        throw new RuntimeException("Config service unavailable");
                    }
                    return config;
                })
                .build();
        TestParent parent = TestParent.builder().transactionId("TXN-123").amount(500).build();

        TestChild first = TestChild.builder().build();
        runSaveWithParent(observer, first, parent);
        assertNull(first.getTxnId(), "dynamic copyEnabled=false must apply");
        CopyFromParentPersistor before = observer.getPersistor();

        fail.set(true);
        TestChild second = TestChild.builder().build();
        runSaveWithParent(observer, second, parent);
        assertNull(second.getTxnId(), "last resolved config (copyEnabled=false) must be kept, not static (true)");
        assertSame(before, observer.getPersistor(), "persistor must not be re-created on config fetch failure");

        // Recovery: new value is picked up again
        fail.set(false);
        config.setCopyEnabled(true);
        TestChild third = TestChild.builder().build();
        runSaveWithParent(observer, third, parent);
        assertEquals("TXN-123", third.getTxnId());
    }

    @Test
    void testConfigSupplier_resolvedOnEveryTransaction_persistorReusedWhileUnchanged() {
        AtomicInteger supplierCallCount = new AtomicInteger(0);
        TestConfig config = TestConfig.builder()
                .copyEnabled(true)
                .mismatchDetectionEnabled(false)
                .copyIfDefaultOnly(false)
                .build();
        CopyFromParentObserver observer = CopyFromParentObserver.builder()
                .configSupplier(() -> {
                    supplierCallCount.incrementAndGet();
                    return config;
                })
                .build();
        TestParent parent = TestParent.builder().transactionId("TXN").amount(100).build();

        runSaveWithParent(observer, TestChild.builder().build(), parent);
        CopyFromParentPersistor first = observer.getPersistor(); // 2nd supplier call
        runSaveWithParent(observer, TestChild.builder().build(), parent);
        runSaveWithParent(observer, TestChild.builder().build(), parent);

        assertEquals(4, supplierCallCount.get(), "Config supplier must be called on every resolution");
        assertSame(first, observer.getPersistor(), "Persistor must be reused while config is unchanged");
    }

    @Test
    void testConfigSupplier_eachFlagChange_recreatesPersistor_andTakesEffect() {
        CopyFromParentMismatchListener listener = mock(CopyFromParentMismatchListener.class);
        AtomicInteger listenerSupplierCalls = new AtomicInteger(0);
        TestConfig config = TestConfig.builder()
                .copyEnabled(false)
                .mismatchDetectionEnabled(false)
                .copyIfDefaultOnly(false)
                .build();
        CopyFromParentObserver observer = CopyFromParentObserver.builder()
                .configSupplier(() -> config)
                .mismatchListener(() -> {
                    listenerSupplierCalls.incrementAndGet();
                    return listener;
                })
                .build();
        TestParent parent = TestParent.builder().transactionId("PARENT-TXN").amount(500).build();

        // copyEnabled=false
        TestChild c1 = TestChild.builder().build();
        runSaveWithParent(observer, c1, parent);
        assertNull(c1.getTxnId());
        CopyFromParentPersistor p1 = observer.getPersistor();
        assertPersistorFlags(p1, false, false, false);
        assertNull(p1.getMismatchListener());
        assertEquals(0, listenerSupplierCalls.get(), "listener must not be resolved while detection is off");

        // flip copyEnabled -> true
        config.setCopyEnabled(true);
        TestChild c2 = TestChild.builder().build();
        runSaveWithParent(observer, c2, parent);
        assertEquals("PARENT-TXN", c2.getTxnId());
        CopyFromParentPersistor p2 = observer.getPersistor();
        assertNotSame(p1, p2);
        assertPersistorFlags(p2, true, false, false);

        // flip copyIfDefaultOnly -> true: non-default child value must now fail
        config.setCopyIfDefaultOnly(true);
        TestChild c3 = TestChild.builder().txnId("MANUAL").build();
        assertThrows(IllegalStateException.class, () -> runSaveWithParent(observer, c3, parent));
        CopyFromParentPersistor p3 = observer.getPersistor();
        assertNotSame(p2, p3);
        assertPersistorFlags(p3, true, false, true);

        // flip mismatchDetectionEnabled -> true: listener resolved now, and used
        config.setCopyIfDefaultOnly(false);
        config.setMismatchDetectionEnabled(true);
        TestChild c4 = TestChild.builder().txnId("CHILD-TXN").build();
        runSaveWithParent(observer, c4, parent);
        CopyFromParentPersistor p4 = observer.getPersistor();
        assertPersistorFlags(p4, true, true, false);
        assertSame(listener, p4.getMismatchListener());
        verify(listener, times(1)).onMismatches(eq(parent), eq(c4), any());
        assertEquals(1, listenerSupplierCalls.get());

        // detection off and on again: the listener is cached, not resolved again
        config.setMismatchDetectionEnabled(false);
        runSaveWithParent(observer, TestChild.builder().build(), parent);
        assertNull(observer.getPersistor().getMismatchListener());
        config.setMismatchDetectionEnabled(true);
        runSaveWithParent(observer, TestChild.builder().build(), parent);
        assertSame(listener, observer.getPersistor().getMismatchListener());
        assertEquals(1, listenerSupplierCalls.get(), "listener must be resolved only once");
    }

    @Test
    void testConfigSupplier_nullValueFallsBackToStatic_changeBetweenNullAndExplicitIsDetected() {
        TestConfig config = TestConfig.builder().build(); // all null
        CopyFromParentObserver observer = CopyFromParentObserver.builder()
                .copyEnabled(false) // static
                .configSupplier(() -> config)
                .build();
        TestParent parent = TestParent.builder().transactionId("PARENT-TXN").build();

        TestChild c1 = TestChild.builder().build();
        runSaveWithParent(observer, c1, parent);
        assertNull(c1.getTxnId(), "null dynamic value -> static (false)");
        CopyFromParentPersistor p1 = observer.getPersistor();

        // Explicit value equal to the effective one: resolved flags unchanged -> same persistor
        config.setCopyEnabled(false);
        runSaveWithParent(observer, TestChild.builder().build(), parent);
        assertSame(p1, observer.getPersistor());

        config.setCopyEnabled(true);
        TestChild c2 = TestChild.builder().build();
        runSaveWithParent(observer, c2, parent);
        assertEquals("PARENT-TXN", c2.getTxnId());
        assertNotSame(p1, observer.getPersistor());
    }

    @Test
    void testConfigSupplier_runtimeEnablesDetectionWithoutListener_failsFast_thenRecoversWhenDisabled() {
        TestConfig config = TestConfig.builder().mismatchDetectionEnabled(false).build();
        CopyFromParentObserver observer = CopyFromParentObserver.builder()
                .configSupplier(() -> config)
                .build(); // no listener
        TestParent parent = TestParent.builder().transactionId("PARENT-TXN").build();

        runSaveWithParent(observer, TestChild.builder().build(), parent);
        CopyFromParentPersistor p1 = observer.getPersistor();

        config.setMismatchDetectionEnabled(true);
        TestChild child = TestChild.builder().build();
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> runSaveWithParent(observer, child, parent));
        assertEquals(CopyFromParentObserver.LISTENER_REQUIRED_MESSAGE, ex.getMessage());
        assertNull(child.getTxnId(), "nothing must be applied when the transaction fails on config");

        config.setMismatchDetectionEnabled(false);
        assertSame(p1, observer.getPersistor(), "failed creation must not replace the current persistor");
        TestChild c3 = TestChild.builder().build();
        runSaveWithParent(observer, c3, parent);
        assertEquals("PARENT-TXN", c3.getTxnId());
    }

    @Test
    void testConcurrentTransactions_singlePersistorCreated_listenerResolvedOnce() throws Exception {
        AtomicInteger listenerSupplierCalls = new AtomicInteger(0);
        TestConfig config = TestConfig.builder().copyEnabled(true).mismatchDetectionEnabled(true).build();
        CopyFromParentObserver observer = CopyFromParentObserver.builder()
                .configSupplier(() -> config)
                .mismatchListener(() -> {
                    listenerSupplierCalls.incrementAndGet();
                    // widen the race window: without the lock + re-check, other threads would create their own
                    sleepUninterruptibly(50);
                    return mock(CopyFromParentMismatchListener.class);
                })
                .build();
        TestParent parent = TestParent.builder().transactionId("PARENT-TXN").amount(5).build();

        final int threads = 16;
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(threads);
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        // Identity set: CopyFromParentPersistor is @Data, so distinct instances with equal flags are equals()
        java.util.Set<CopyFromParentPersistor> seen =
                java.util.Collections.synchronizedSet(java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>()));
        java.util.List<java.util.concurrent.Future<TestChild>> futures = new java.util.ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    seen.add(observer.getPersistor());
                    TestChild child = TestChild.builder().build();
                    runSaveWithParent(observer, child, parent);
                    return child;
                }));
            }
            start.countDown();
            for (java.util.concurrent.Future<TestChild> f : futures) {
                assertEquals("PARENT-TXN", f.get(30, java.util.concurrent.TimeUnit.SECONDS).getTxnId());
            }
        } finally {
            pool.shutdownNow();
        }

        assertEquals(1, seen.size(), "Exactly one persistor must be created for an unchanged config");
        assertEquals(1, listenerSupplierCalls.get(), "Listener must be resolved exactly once");
    }

    private static void sleepUninterruptibly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void runSaveWithParent(CopyFromParentObserver observer, TestChild child, TestParent parent) {
        SaveWithParent<TestChild, TestChild, TestParent> op =
                SaveWithParent.<TestChild, TestChild, TestParent>saveWithParentBuilder()
                        .entity(child).parent(parent).saver(e -> e).build();
        observer.execute(createContext(op), () -> op.apply(null));
    }

    private static void assertPersistorFlags(CopyFromParentPersistor persistor,
                                             boolean copyEnabled,
                                             boolean mismatchDetectionEnabled,
                                             boolean copyIfDefaultOnly) {
        assertEquals(copyEnabled, persistor.isCopyEnabled());
        assertEquals(mismatchDetectionEnabled, persistor.isMismatchDetectionEnabled());
        assertEquals(copyIfDefaultOnly, persistor.isCopyIfDefaultOnly());
    }

    @Test
    void testConfigSupplier_partialNullValues_mixedFallback() {
        // Config returns value for copyEnabled, null for others
        TestConfig config = TestConfig.builder()
                .copyEnabled(false)  // Explicit value
                .mismatchDetectionEnabled(null)  // Null - should use static or default
                .copyIfDefaultOnly(null)  // Null - should use static or default
                .build();

        CopyFromParentObserver observer = CopyFromParentObserver.builder()
                .copyEnabled(true)  // Static - should be overridden by config
                .mismatchDetectionEnabled(true)  // Static - should be used (config is null)
                .configSupplier(() -> config)
                .mismatchListener(() -> mock(CopyFromParentMismatchListener.class))
                .build();

        TestParent parent = TestParent.builder()
                .transactionId("PARENT-TXN")
                .amount(500)
                .build();
        TestChild child = TestChild.builder()
                .txnId("ORIGINAL-TXN")
                .childAmount(100)
                .build();

        SaveWithParent<TestChild, TestChild, TestParent> opContext =
                SaveWithParent.<TestChild, TestChild, TestParent>saveWithParentBuilder()
                        .entity(child)
                        .parent(parent)
                        .saver(e -> e)
                        .build();

        TransactionExecutionContext ctx = createContext(opContext);
        TestChild result = observer.execute(ctx, () -> opContext.apply(null));

        // copyEnabled: config (false) overrides static (true)
        // mismatchDetectionEnabled: static (true) used since config is null
        // Result: no copy (copyEnabled=false), but if it were true, mismatch would be detected
        assertEquals("ORIGINAL-TXN", result.getTxnId(), "Config supplier (false) should override static");
        assertEquals(100, result.getChildAmount(), "Config supplier (false) should override static");
    }

    @Test
    void testConfigSupplier_calledOncePerSaveWithParentTransaction() {
        AtomicInteger supplierCallCount = new AtomicInteger(0);

        TestConfig config = TestConfig.builder()
                .copyEnabled(true)
                .mismatchDetectionEnabled(false)
                .copyIfDefaultOnly(false)
                .build();

        CopyFromParentObserver observer = CopyFromParentObserver.builder()
                .configSupplier(() -> {
                    supplierCallCount.incrementAndGet();
                    return config;
                })
                .build();

        TestParent parent = TestParent.builder().transactionId("TXN").amount(100).build();
        TestChild child = TestChild.builder().build();

        SaveWithParent<TestChild, TestChild, TestParent> opContext =
                SaveWithParent.<TestChild, TestChild, TestParent>saveWithParentBuilder()
                        .entity(child)
                        .parent(parent)
                        .saver(e -> e)
                        .build();

        TransactionExecutionContext ctx = createContext(opContext);

        // Execute multiple times
        observer.execute(ctx, () -> opContext.apply(null));
        observer.execute(ctx, () -> opContext.apply(null));
        observer.execute(ctx, () -> opContext.apply(null));

        // Dynamic config: read on every transaction, never snapshotted
        assertEquals(3, supplierCallCount.get(), "Config supplier should be called once per transaction");
    }

    @Test
    void testConfigSupplier_nullConfig_fallbackToDefaults() {
        CopyFromParentObserver observer = CopyFromParentObserver.builder()
                .configSupplier(() -> null)  // Supplier returns null
                .build();

        TestParent parent = TestParent.builder()
                .transactionId("TXN-123")
                .amount(500)
                .build();
        TestChild child = TestChild.builder().build();

        SaveWithParent<TestChild, TestChild, TestParent> opContext =
                SaveWithParent.<TestChild, TestChild, TestParent>saveWithParentBuilder()
                        .entity(child)
                        .parent(parent)
                        .saver(e -> e)
                        .build();

        TransactionExecutionContext ctx = createContext(opContext);
        TestChild result = observer.execute(ctx, () -> opContext.apply(null));

        // Should use default values (copyEnabled=true)
        assertEquals("TXN-123", result.getTxnId(), "Should fall back to default when supplier returns null");
        assertEquals(500, result.getChildAmount(), "Should fall back to default when supplier returns null");
    }

    @Test
    void testConfigSupplier_mismatchDetectionWithoutListener_throws() {
        // Config enables mismatch detection but no listener provided.
        // Must fail fast exactly like the static path does at build(), never silently skip detection.
        TestConfig config = TestConfig.builder()
                .copyEnabled(true)
                .mismatchDetectionEnabled(true)  // Enabled but no listener
                .copyIfDefaultOnly(false)
                .build();

        CopyFromParentObserver observer = CopyFromParentObserver.builder()
                .configSupplier(() -> config)
                // No mismatchListener provided
                .build();

        TestParent parent = TestParent.builder()
                .transactionId("PARENT-TXN")
                .amount(500)
                .build();
        MismatchChild child = MismatchChild.builder()
                .txnId("CHILD-TXN")  // Would be a mismatch
                .childAmount(100)
                .build();

        SaveWithParent<MismatchChild, MismatchChild, TestParent> opContext =
                SaveWithParent.<MismatchChild, MismatchChild, TestParent>saveWithParentBuilder()
                        .entity(child)
                        .parent(parent)
                        .saver(e -> e)
                        .build();

        TransactionExecutionContext ctx = createContext(opContext);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> observer.execute(ctx, () -> opContext.apply(null)),
                "Should throw when mismatchDetectionEnabled=true but listener is null");
        // Same error as the static path (Builder#build)
        IllegalArgumentException staticEx = assertThrows(IllegalArgumentException.class,
                () -> CopyFromParentObserver.builder().mismatchDetectionEnabled(true).build());
        assertEquals(staticEx.getMessage(), ex.getMessage());
        assertEquals("CHILD-TXN", child.getTxnId(), "Entity must not be touched");
    }

    @Test
    void testConfigSupplier_copyIfDefaultOnly() {
        TestConfig config = TestConfig.builder()
                .copyEnabled(true)
                .mismatchDetectionEnabled(false)
                .copyIfDefaultOnly(true)  // Enable strict mode
                .build();

        CopyFromParentObserver observer = CopyFromParentObserver.builder()
                .configSupplier(() -> config)
                .build();

        TestParent parent = TestParent.builder()
                .transactionId("PARENT-TXN")
                .amount(500)
                .build();
        
        // Child with default values - should be copied
        TestChild childWithDefaults = TestChild.builder().build();
        
        SaveWithParent<TestChild, TestChild, TestParent> opContextDefaults =
                SaveWithParent.<TestChild, TestChild, TestParent>saveWithParentBuilder()
                        .entity(childWithDefaults)
                        .parent(parent)
                        .saver(e -> e)
                        .build();

        TransactionExecutionContext ctx1 = createContext(opContextDefaults);
        TestChild result1 = observer.execute(ctx1, () -> opContextDefaults.apply(null));

        // Should copy because child has default values
        assertEquals("PARENT-TXN", result1.getTxnId());
        assertEquals(500, result1.getChildAmount());
    }

    @Test
    void testBackwardCompatibility_staticValuesStillWork() {
        // Code without config supplier should also to work
        CopyFromParentObserver observer = CopyFromParentObserver.builder()
                .copyEnabled(true)
                .mismatchDetectionEnabled(false)
                .build();

        TestParent parent = TestParent.builder()
                .transactionId("TXN-123")
                .amount(500)
                .build();
        TestChild child = TestChild.builder().build();

        SaveWithParent<TestChild, TestChild, TestParent> opContext =
                SaveWithParent.<TestChild, TestChild, TestParent>saveWithParentBuilder()
                        .entity(child)
                        .parent(parent)
                        .saver(e -> e)
                        .build();

        TransactionExecutionContext ctx = createContext(opContext);
        TestChild result = observer.execute(ctx, () -> opContext.apply(null));

        assertEquals("TXN-123", result.getTxnId(), "Static values should still work");
        assertEquals(500, result.getChildAmount(), "Static values should still work");
    }
}

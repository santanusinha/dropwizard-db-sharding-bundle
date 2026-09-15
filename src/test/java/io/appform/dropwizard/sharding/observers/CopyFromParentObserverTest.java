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
                SaveWithParent.<TestChild, TestChild, TestParent>builder()
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
                SaveWithParent.<TestChild, TestChild, TestParent>builder()
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
                SaveWithParent.<MismatchChild, MismatchChild, TestParent>builder()
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
                SaveWithParent.<MismatchChild, MismatchChild, TestParent>builder()
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
                SaveWithParent.<MismatchChild, MismatchChild, TestParent>builder()
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
                SaveWithParent.<TestChild, TestChild, TestParent>builder()
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
                SaveWithParent.<MismatchChild, MismatchChild, TestParent>builder()
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
                SaveWithParent.<MismatchChild, MismatchChild, TestParent>builder()
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
    void testLazyListenerInit_supplierFailure_copyContinues() {
        CopyFromParentObserver observer = CopyFromParentObserver.builder()
                .copyEnabled(true)
                .mismatchDetectionEnabled(true)
                .mismatchListener(() -> {
                    throw new RuntimeException("Guice not initialized");
                })
                .build();

        TestParent parent = TestParent.builder().transactionId("TXN").amount(100).build();
        TestChild child = TestChild.builder().build();

        SaveWithParent<TestChild, TestChild, TestParent> opContext =
                SaveWithParent.<TestChild, TestChild, TestParent>builder()
                        .entity(child)
                        .parent(parent)
                        .saver(e -> e)
                        .build();

        TransactionExecutionContext ctx = createContext(opContext);

        // Should not throw - supplier failure should be caught and logged
        TestChild result = observer.execute(ctx, () -> opContext.apply(null));

        // Copy should still work
        assertEquals("TXN", result.getTxnId());
        assertEquals(100, result.getChildAmount());
    }

    @Test
    void testNonSaveWithParent_proceedsWithoutError() {
        CopyFromParentObserver observer = CopyFromParentObserver.builder()
                .copyEnabled(true)
                .mismatchDetectionEnabled(true)
                .mismatchListener(() -> mock(CopyFromParentMismatchListener.class))
                .build();

        Save<String, String> saveOp = Save.<String, String>builder()
                .entity("entity")
                .saver(e -> e)
                .build();

        TransactionExecutionContext ctx = createContext(saveOp);

        String result = observer.execute(ctx, () -> "done");

        assertEquals("done", result);
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
                SaveWithParent.<TestChild, TestChild, TestParent>builder()
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
                SaveWithParent.<TestChild, TestChild, TestParent>builder()
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
                SaveWithParent.<TestChild, TestChild, TestParent>builder()
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
                SaveWithParent.<TestChild, String, TestParent>builder()
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

    private <R> TransactionExecutionContext createContext(io.appform.dropwizard.sharding.dao.operations.OpContext<R> opContext) {
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
                SaveWithParent.<TestChild, TestChild, TestParent>builder()
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
                SaveWithParent.<TestChild, TestChild, TestParent>builder()
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
                SaveWithParent.<MismatchChild, MismatchChild, TestParent>builder()
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
                SaveWithParent.<TestChild, TestChild, TestParent>builder()
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
                SaveWithParent.<TestChild, TestChild, TestParent>builder()
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
                SaveWithParent.<TestChild, TestChild, TestParent>builder()
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
                SaveWithParent.<TestChild, TestChild, TestParent>builder()
                        .entity(child)
                        .parent(parent)
                        .saver(e -> e)
                        .build();

        TransactionExecutionContext ctx = createContext(opContext);
        TestChild result = observer.execute(ctx, () -> opContext.apply(null));

        // Should fall back to static value when config supplier throws
        assertEquals("TXN-123", result.getTxnId(), "Should fall back to static value when config supplier throws");
        assertEquals(500, result.getChildAmount(), "Should fall back to static value when config supplier throws");
        
        // Config supplier is called once during getPersistor() initialization
        assertEquals(1, callCount.get(), "Config supplier should be called once during initialization");
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
                SaveWithParent.<TestChild, TestChild, TestParent>builder()
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
    void testConfigSupplier_lazyInit_calledOnce() {
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
                SaveWithParent.<TestChild, TestChild, TestParent>builder()
                        .entity(child)
                        .parent(parent)
                        .saver(e -> e)
                        .build();

        TransactionExecutionContext ctx = createContext(opContext);

        // Execute multiple times
        observer.execute(ctx, () -> opContext.apply(null));
        observer.execute(ctx, () -> opContext.apply(null));
        observer.execute(ctx, () -> opContext.apply(null));

        // Config supplier is called once on first execution,
        // but not called again on subsequent executions (persistor is cached)
        assertEquals(1, supplierCallCount.get(), "Config supplier should be called once on first execution only");
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
                SaveWithParent.<TestChild, TestChild, TestParent>builder()
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
        // Config enables mismatch detection but no listener provided
        // Should log warning but continue with copy
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
                SaveWithParent.<MismatchChild, MismatchChild, TestParent>builder()
                        .entity(child)
                        .parent(parent)
                        .saver(e -> e)
                        .build();

        TransactionExecutionContext ctx = createContext(opContext);

        assertThrows(IllegalArgumentException.class, () -> observer.execute(ctx, () -> opContext.apply(null)),
                "Should throw when mismatchDetectionEnabled=true but listener is null");
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
                SaveWithParent.<TestChild, TestChild, TestParent>builder()
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
                SaveWithParent.<TestChild, TestChild, TestParent>builder()
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

package io.appform.dropwizard.sharding.observers;

import io.appform.dropwizard.sharding.dao.operations.*;
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
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CopyFromParentPersistorTest {

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
    static class NoOverrideChild {
        @CopyFromParent(field = "transactionId", override = false)
        private String txnId;

        @CopyFromParent(field = "amount", override = false)
        private long childAmount;
    }

    // Test: copyEnabled=true, mismatchDetectionEnabled=false

    @Test
    void testDefaultConfig_wrapsAndCopies() {
        CopyFromParentPersistor persistor = new CopyFromParentPersistor(
                true, false, false, null);

        TestParent parent = TestParent.builder()
                .transactionId("TXN-123")
                .amount(500)
                .build();
        TestChild child = TestChild.builder()
                .ownField("mine")
                .build();

        AtomicReference<TestChild> savedEntity = new AtomicReference<>();
        SaveWithParent<TestChild, TestChild, TestParent> op =
                SaveWithParent.<TestChild, TestChild, TestParent>builder()
                        .entity(child)
                        .parent(parent)
                        .saver(e -> {
                            savedEntity.set(e);
                            return e;
                        })
                        .build();

        persistor.visit(op);
        TestChild result = op.apply(null);

        assertEquals("TXN-123", result.getTxnId());
        assertEquals(500, result.getChildAmount());
        assertEquals("mine", result.getOwnField());
        assertSame(savedEntity.get(), result);
    }

    @Test
    void testCopyEnabled_overwritesExistingValues() {
        CopyFromParentPersistor persistor = new CopyFromParentPersistor(
                true, false, false, null);

        TestParent parent = TestParent.builder()
                .transactionId("NEW-TXN")
                .amount(999)
                .build();
        TestChild child = TestChild.builder()
                .txnId("OLD-TXN")
                .childAmount(1)
                .build();

        SaveWithParent<TestChild, TestChild, TestParent> op =
                SaveWithParent.<TestChild, TestChild, TestParent>builder()
                        .entity(child)
                        .parent(parent)
                        .saver(e -> e)
                        .build();

        persistor.visit(op);
        TestChild result = op.apply(null);

        assertEquals("NEW-TXN", result.getTxnId());
        assertEquals(999, result.getChildAmount());
    }

    @Test
    void testCopyEnabled_preservesOriginalSaverBehavior() {
        CopyFromParentPersistor persistor = new CopyFromParentPersistor(
                true, false, false, null);

        TestParent parent = TestParent.builder().transactionId("T").amount(0).build();
        TestChild child = TestChild.builder().build();

        AtomicReference<String> sideEffect = new AtomicReference<>("not-called");
        SaveWithParent<TestChild, TestChild, TestParent> op =
                SaveWithParent.<TestChild, TestChild, TestParent>builder()
                        .entity(child)
                        .parent(parent)
                        .saver(e -> {
                            sideEffect.set("called");
                            return e;
                        })
                        .build();

        persistor.visit(op);
        op.apply(null);

        assertEquals("called", sideEffect.get());
    }

    // Test: copyEnabled=false

    @Test
    void testCopyDisabled_doesNotCopyFields() {
        CopyFromParentPersistor persistor = new CopyFromParentPersistor(
                false, false, false, null);

        TestParent parent = TestParent.builder()
                .transactionId("PARENT-TXN")
                .amount(500)
                .build();
        TestChild child = TestChild.builder()
                .txnId("ORIGINAL-TXN")
                .childAmount(100)
                .build();

        SaveWithParent<TestChild, TestChild, TestParent> op =
                SaveWithParent.<TestChild, TestChild, TestParent>builder()
                        .entity(child)
                        .parent(parent)
                        .saver(e -> e)
                        .build();

        persistor.visit(op);
        TestChild result = op.apply(null);

        // Fields should NOT be copied
        assertEquals("ORIGINAL-TXN", result.getTxnId());
        assertEquals(100, result.getChildAmount());
    }

    @Test
    void testCopyDisabled_saverStillExecuted() {
        CopyFromParentPersistor persistor = new CopyFromParentPersistor(
                false, false, false, null);

        TestParent parent = TestParent.builder().transactionId("T").build();
        TestChild child = TestChild.builder().build();

        AtomicBoolean saverCalled = new AtomicBoolean(false);
        SaveWithParent<TestChild, TestChild, TestParent> op =
                SaveWithParent.<TestChild, TestChild, TestParent>builder()
                        .entity(child)
                        .parent(parent)
                        .saver(e -> {
                            saverCalled.set(true);
                            return e;
                        })
                        .build();

        persistor.visit(op);
        op.apply(null);

        assertTrue(saverCalled.get(), "Saver should still be executed even when copy is disabled");
    }

    // Test: mismatchDetectionEnabled=true with listener

    @Test
    void testMismatchDetection_callsListenerWhenMismatchesFound() {
        CopyFromParentMismatchListener mockListener = mock(CopyFromParentMismatchListener.class);
        CopyFromParentPersistor persistor = new CopyFromParentPersistor(
                true, true, false, mockListener);

        TestParent parent = TestParent.builder()
                .transactionId("PARENT-TXN")
                .amount(500)
                .build();
        TestChild child = TestChild.builder()
                .txnId("CHILD-TXN")  // Mismatch
                .childAmount(100)     // Mismatch
                .build();

        SaveWithParent<TestChild, TestChild, TestParent> op =
                SaveWithParent.<TestChild, TestChild, TestParent>builder()
                        .entity(child)
                        .parent(parent)
                        .saver(e -> e)
                        .build();

        persistor.visit(op);
        op.apply(null);

        ArgumentCaptor<List<CopyFromParentUtils.FieldMismatch>> mismatchCaptor =
                ArgumentCaptor.forClass(List.class);
        verify(mockListener).onMismatches(eq(parent), eq(child), mismatchCaptor.capture());

        List<CopyFromParentUtils.FieldMismatch> mismatches = mismatchCaptor.getValue();
        assertEquals(2, mismatches.size());
    }

    @Test
    void testMismatchDetection_listenerNotCalledWhenNoMismatches() {
        CopyFromParentMismatchListener mockListener = mock(CopyFromParentMismatchListener.class);
        CopyFromParentPersistor persistor = new CopyFromParentPersistor(
                true, true, false, mockListener);

        TestParent parent = TestParent.builder()
                .transactionId("SAME")
                .amount(100)
                .build();
        TestChild child = TestChild.builder()
                .txnId("SAME")
                .childAmount(100)
                .build();

        SaveWithParent<TestChild, TestChild, TestParent> op =
                SaveWithParent.<TestChild, TestChild, TestParent>builder()
                        .entity(child)
                        .parent(parent)
                        .saver(e -> e)
                        .build();

        persistor.visit(op);
        op.apply(null);

        verify(mockListener, never()).onMismatches(any(), any(), any());
    }

    @Test
    void testMismatchDetection_listenerNull_doesNotFail() {
        CopyFromParentPersistor persistor = new CopyFromParentPersistor(
                true, true, false, null);

        TestParent parent = TestParent.builder().transactionId("TXN").amount(100).build();
        TestChild child = TestChild.builder().txnId("DIFF").childAmount(50).build();

        SaveWithParent<TestChild, TestChild, TestParent> op =
                SaveWithParent.<TestChild, TestChild, TestParent>builder()
                        .entity(child)
                        .parent(parent)
                        .saver(e -> e)
                        .build();

        persistor.visit(op);
        
        // Should not throw even with null listener
        assertDoesNotThrow(() -> op.apply(null));
    }

    @Test
    void testMismatchDetection_listenerExceptionDoesNotBreakCopy() {
        CopyFromParentMismatchListener mockListener = mock(CopyFromParentMismatchListener.class);
        doThrow(new RuntimeException("Listener explosion"))
                .when(mockListener).onMismatches(any(), any(), any());

        CopyFromParentPersistor persistor = new CopyFromParentPersistor(
                true, true, false, mockListener);

        TestParent parent = TestParent.builder()
                .transactionId("PARENT-TXN")
                .amount(500)
                .build();
        TestChild child = TestChild.builder()
                .txnId("CHILD-TXN")
                .childAmount(100)
                .build();

        SaveWithParent<TestChild, TestChild, TestParent> op =
                SaveWithParent.<TestChild, TestChild, TestParent>builder()
                        .entity(child)
                        .parent(parent)
                        .saver(e -> e)
                        .build();

        persistor.visit(op);
        TestChild result = op.apply(null);

        // Copy should still work despite listener failure
        assertEquals("PARENT-TXN", result.getTxnId());
        assertEquals(500, result.getChildAmount());
    }

    // Test: copyEnabled=false, mismatchDetectionEnabled=true (validation mode)

    @Test
    void testValidationMode_detectsButDoesNotCopy() {
        CopyFromParentMismatchListener mockListener = mock(CopyFromParentMismatchListener.class);
        CopyFromParentPersistor persistor = new CopyFromParentPersistor(
                false, true, false, mockListener);

        TestParent parent = TestParent.builder()
                .transactionId("PARENT-TXN")
                .amount(500)
                .build();
        TestChild child = TestChild.builder()
                .txnId("CHILD-TXN")
                .childAmount(100)
                .build();

        SaveWithParent<TestChild, TestChild, TestParent> op =
                SaveWithParent.<TestChild, TestChild, TestParent>builder()
                        .entity(child)
                        .parent(parent)
                        .saver(e -> e)
                        .build();

        persistor.visit(op);
        TestChild result = op.apply(null);

        // Verify mismatch detection was called
        verify(mockListener).onMismatches(eq(parent), eq(child), any());

        // But fields should NOT be copied
        assertEquals("CHILD-TXN", result.getTxnId());
        assertEquals(100, result.getChildAmount());
    }

    // Test: Visitor pattern - other OpContext types should be no-op

    @Test
    void testVisit_saveOpContext_isNoop() {
        CopyFromParentPersistor persistor = new CopyFromParentPersistor(
                true, false, false, null);

        Save<String, String> saveOp = Save.<String, String>builder()
                .entity("entity")
                .saver(e -> e)
                .build();

        assertDoesNotThrow(() -> persistor.visit(saveOp));
    }

    @Test
    void testVisit_getOpContext_isNoop() {
        CopyFromParentPersistor persistor = new CopyFromParentPersistor(
                true, false, false, null);

        Get<String, String> getOp = mock(Get.class);
        assertNull(persistor.visit(getOp));
    }

    // Test: Complex scenarios

    @Test
    void testMultipleInvocations_eachCopiesCorrectly() {
        CopyFromParentPersistor persistor = new CopyFromParentPersistor(
                true, false, false, null);

        // First invocation
        TestParent parent1 = TestParent.builder().transactionId("TXN-1").amount(100).build();
        TestChild child1 = TestChild.builder().build();

        SaveWithParent<TestChild, TestChild, TestParent> op1 =
                SaveWithParent.<TestChild, TestChild, TestParent>builder()
                        .entity(child1)
                        .parent(parent1)
                        .saver(e -> e)
                        .build();

        persistor.visit(op1);
        TestChild result1 = op1.apply(null);

        assertEquals("TXN-1", result1.getTxnId());
        assertEquals(100, result1.getChildAmount());

        // Second invocation with different data
        TestParent parent2 = TestParent.builder().transactionId("TXN-2").amount(200).build();
        TestChild child2 = TestChild.builder().build();

        SaveWithParent<TestChild, TestChild, TestParent> op2 =
                SaveWithParent.<TestChild, TestChild, TestParent>builder()
                        .entity(child2)
                        .parent(parent2)
                        .saver(e -> e)
                        .build();

        persistor.visit(op2);
        TestChild result2 = op2.apply(null);

        assertEquals("TXN-2", result2.getTxnId());
        assertEquals(200, result2.getChildAmount());
    }

    @Test
    void testSaverWrapping_preservesExecutionOrder() {
        CopyFromParentPersistor persistor = new CopyFromParentPersistor(
                true, false, false, null);

        TestParent parent = TestParent.builder().transactionId("TXN").amount(100).build();
        TestChild child = TestChild.builder().build();

        AtomicInteger executionOrder = new AtomicInteger(0);
        AtomicInteger copyOrder = new AtomicInteger(-1);
        AtomicInteger saverOrder = new AtomicInteger(-1);

        SaveWithParent<TestChild, TestChild, TestParent> op =
                SaveWithParent.<TestChild, TestChild, TestParent>builder()
                        .entity(child)
                        .parent(parent)
                        .saver(e -> {
                            saverOrder.set(executionOrder.incrementAndGet());
                            // At this point, fields should already be copied
                            assertEquals("TXN", e.getTxnId(), "Fields should be copied before saver executes");
                            copyOrder.set(0); // Mark that copy happened before saver
                            return e;
                        })
                        .build();

        persistor.visit(op);
        op.apply(null);

        assertEquals(0, copyOrder.get(), "Copy should have happened");
        assertEquals(1, saverOrder.get(), "Saver should execute after copy");
    }

    @Test
    void testAfterSaveFunction_executesAfterCopyAndSave() {
        CopyFromParentPersistor persistor = new CopyFromParentPersistor(
                true, false, false, null);

        TestParent parent = TestParent.builder().transactionId("TXN").amount(100).build();
        TestChild child = TestChild.builder().build();

        AtomicBoolean afterSaveCalled = new AtomicBoolean(false);

        SaveWithParent<TestChild, String, TestParent> op =
                SaveWithParent.<TestChild, String, TestParent>builder()
                        .entity(child)
                        .parent(parent)
                        .saver(e -> e)
                        .afterSave(e -> {
                            afterSaveCalled.set(true);
                            // Verify fields are copied
                            assertEquals("TXN", e.getTxnId());
                            return "transformed";
                        })
                        .build();

        persistor.visit(op);
        String result = op.apply(null);

        assertTrue(afterSaveCalled.get());
        assertEquals("transformed", result);
    }

    @Test
    void testNullValues_handledCorrectly() {
        CopyFromParentPersistor persistor = new CopyFromParentPersistor(
                true, false, false, null);

        TestParent parent = TestParent.builder()
                .transactionId(null)
                .amount(0)
                .build();
        TestChild child = TestChild.builder()
                .txnId("EXISTING")
                .childAmount(999)
                .build();

        SaveWithParent<TestChild, TestChild, TestParent> op =
                SaveWithParent.<TestChild, TestChild, TestParent>builder()
                        .entity(child)
                        .parent(parent)
                        .saver(e -> e)
                        .build();

        persistor.visit(op);
        TestChild result = op.apply(null);

        assertNull(result.getTxnId(), "Null value should be copied");
        assertEquals(0, result.getChildAmount(), "Zero value should be copied");
    }

    // Test: Mismatch detection timing (before vs after copy)

    @Test
    void testMismatchDetection_runsBeforeCopy() {
        CopyFromParentMismatchListener mockListener = mock(CopyFromParentMismatchListener.class);
        CopyFromParentPersistor persistor = new CopyFromParentPersistor(
                true, true, false, mockListener);

        TestParent parent = TestParent.builder().transactionId("PARENT").amount(500).build();
        TestChild child = TestChild.builder().txnId("CHILD").childAmount(100).build();

        doAnswer(invocation -> {
            TestChild capturedChild = invocation.getArgument(1);
            // At this point, mismatch detection runs BEFORE copy
            assertEquals("CHILD", capturedChild.getTxnId(),
                    "Mismatch detection should run before copy");
            assertEquals(100, capturedChild.getChildAmount(),
                    "Mismatch detection should run before copy");
            return null;
        }).when(mockListener).onMismatches(any(), any(), any());

        SaveWithParent<TestChild, TestChild, TestParent> op =
                SaveWithParent.<TestChild, TestChild, TestParent>builder()
                        .entity(child)
                        .parent(parent)
                        .saver(e -> e)
                        .build();

        persistor.visit(op);
        TestChild result = op.apply(null);

        // After execution, fields should be copied
        assertEquals("PARENT", result.getTxnId());
        assertEquals(500, result.getChildAmount());
    }

    @Test
    void testMismatchDetectionDisabled_listenerNotCalled() {
        CopyFromParentMismatchListener mockListener = mock(CopyFromParentMismatchListener.class);
        CopyFromParentPersistor persistor = new CopyFromParentPersistor(
                true, false, false, mockListener);  // mismatchDetectionEnabled = false

        TestParent parent = TestParent.builder().transactionId("PARENT").amount(500).build();
        TestChild child = TestChild.builder().txnId("CHILD").childAmount(100).build();

        SaveWithParent<TestChild, TestChild, TestParent> op =
                SaveWithParent.<TestChild, TestChild, TestParent>builder()
                        .entity(child)
                        .parent(parent)
                        .saver(e -> e)
                        .build();

        persistor.visit(op);
        op.apply(null);

        // Listener should never be called
        verify(mockListener, never()).onMismatches(any(), any(), any());
    }
}

package io.appform.dropwizard.sharding.utils;

import io.appform.dropwizard.sharding.sharding.CopyFromParent;
import io.appform.dropwizard.sharding.sharding.ParentEntity;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive tests for CopyFromParentUtils.detectMismatches() functionality.
 * Tests all permutations of mismatch scenarios.
 */
class CopyFromParentUtilsDetectMismatchesTest {

    // Test entities
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    static class TestParent {
        private String transactionId;
        private long amount;
        private String customerId;
        private Integer nullableInt;
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

        private String ownField;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @ParentEntity(TestParent.class)
    static class ChildWithOverrideFlags {
        @CopyFromParent(field = "transactionId", override = true)
        private String txnId;

        @CopyFromParent(field = "amount", override = false)
        private long childAmount;

        @CopyFromParent(field = "customerId", override = true)
        private String customerId;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    static class PlainEntity {
        private String name;
    }

    // Test: Basic mismatch detection

    @Test
    void testDetectMismatches_noMismatches_returnsEmptyList() {
        TestParent parent = TestParent.builder()
                .transactionId("SAME")
                .amount(100)
                .customerId("CUST-1")
                .build();
        TestChild child = TestChild.builder()
                .txnId("SAME")
                .childAmount(100)
                .customerId("CUST-1")
                .build();

        List<CopyFromParentUtils.FieldMismatch> mismatches =
                CopyFromParentUtils.detectMismatches(parent, child);

        assertTrue(mismatches.isEmpty(), "Should return empty list when all fields match");
    }

    @Test
    void testDetectMismatches_allFieldsMismatch_returnsAllMismatches() {
        TestParent parent = TestParent.builder()
                .transactionId("PARENT-TXN")
                .amount(500)
                .customerId("PARENT-CUST")
                .build();
        TestChild child = TestChild.builder()
                .txnId("CHILD-TXN")
                .childAmount(100)
                .customerId("CHILD-CUST")
                .build();

        List<CopyFromParentUtils.FieldMismatch> mismatches =
                CopyFromParentUtils.detectMismatches(parent, child);

        assertEquals(3, mismatches.size(), "Should detect all 3 mismatched fields");

        CopyFromParentUtils.FieldMismatch mismatch1 = mismatches.get(0);
        assertEquals("transactionId", mismatch1.getParentFieldName());
        assertEquals("txnId", mismatch1.getChildFieldName());
        assertEquals("PARENT-TXN", mismatch1.getParentValue());
        assertEquals("CHILD-TXN", mismatch1.getChildValue());
    }

    @Test
    void testDetectMismatches_partialMismatch_returnsOnlyMismatchedFields() {
        TestParent parent = TestParent.builder()
                .transactionId("SAME-TXN")
                .amount(500)
                .customerId("DIFFERENT-PARENT")
                .build();
        TestChild child = TestChild.builder()
                .txnId("SAME-TXN")  // Matches
                .childAmount(100)    // Mismatch
                .customerId("DIFFERENT-CHILD")  // Mismatch
                .build();

        List<CopyFromParentUtils.FieldMismatch> mismatches =
                CopyFromParentUtils.detectMismatches(parent, child);

        assertEquals(2, mismatches.size(), "Should detect only the 2 mismatched fields");

        boolean hasAmountMismatch = mismatches.stream()
                .anyMatch(m -> m.getParentFieldName().equals("amount")
                        && m.getParentValue().equals(500L)
                        && m.getChildValue().equals(100L));
        assertTrue(hasAmountMismatch, "Should detect amount mismatch");

        boolean hasCustomerIdMismatch = mismatches.stream()
                .anyMatch(m -> m.getParentFieldName().equals("customerId")
                        && m.getParentValue().equals("DIFFERENT-PARENT")
                        && m.getChildValue().equals("DIFFERENT-CHILD"));
        assertTrue(hasCustomerIdMismatch, "Should detect customerId mismatch");
    }

    // Test: Null value handling

    @Test
    void testDetectMismatches_childHasNull_parentHasValue() {
        TestParent parent = TestParent.builder()
                .transactionId("PARENT-TXN")
                .amount(500)
                .customerId("PARENT-CUST")
                .build();
        TestChild child = TestChild.builder()
                .txnId(null)  // Null vs "PARENT-TXN"
                .childAmount(500)
                .customerId(null)  // Null vs "PARENT-CUST"
                .build();

        List<CopyFromParentUtils.FieldMismatch> mismatches =
                CopyFromParentUtils.detectMismatches(parent, child);

        assertEquals(2, mismatches.size());

        boolean hasTxnIdMismatch = mismatches.stream()
                .anyMatch(m -> m.getParentFieldName().equals("transactionId")
                        && m.getParentValue().equals("PARENT-TXN")
                        && m.getChildValue() == null);
        assertTrue(hasTxnIdMismatch);
    }

    @Test
    void testDetectMismatches_parentHasNull_childHasValue() {
        TestParent parent = TestParent.builder()
                .transactionId(null)
                .amount(0)
                .customerId(null)
                .build();
        TestChild child = TestChild.builder()
                .txnId("CHILD-TXN")
                .childAmount(100)
                .customerId("CHILD-CUST")
                .build();

        List<CopyFromParentUtils.FieldMismatch> mismatches =
                CopyFromParentUtils.detectMismatches(parent, child);

        assertEquals(3, mismatches.size());
    }

    @Test
    void testDetectMismatches_bothNull_noMismatch() {
        TestParent parent = TestParent.builder()
                .transactionId(null)
                .amount(0)
                .customerId(null)
                .build();
        TestChild child = TestChild.builder()
                .txnId(null)
                .childAmount(0)
                .customerId(null)
                .build();

        List<CopyFromParentUtils.FieldMismatch> mismatches =
                CopyFromParentUtils.detectMismatches(parent, child);

        assertTrue(mismatches.isEmpty(), "null == null is not be a mismatch");
    }

    // Test: Default values (primitives)

    @Test
    void testDetectMismatches_childHasDefaults_parentHasValues() {
        TestParent parent = TestParent.builder()
                .transactionId("PARENT-TXN")
                .amount(500)
                .customerId("PARENT-CUST")
                .build();
        TestChild child = new TestChild();  // All defaults: null, 0, null

        List<CopyFromParentUtils.FieldMismatch> mismatches =
                CopyFromParentUtils.detectMismatches(parent, child);

        assertEquals(3, mismatches.size(),
                "Default values should be detected as mismatches when parent has non-default values");
    }

    @Test
    void testDetectMismatches_parentHasDefaults_childHasValues() {
        TestParent parent = TestParent.builder()
                .transactionId(null)
                .amount(0)
                .customerId(null)
                .build();
        TestChild child = TestChild.builder()
                .txnId("CHILD-TXN")
                .childAmount(100)
                .customerId("CHILD-CUST")
                .build();

        List<CopyFromParentUtils.FieldMismatch> mismatches =
                CopyFromParentUtils.detectMismatches(parent, child);

        assertEquals(3, mismatches.size(),
                "Should detect mismatches even when parent has default values");
    }

    @Test
    void testDetectMismatches_bothHaveDefaults_noMismatch() {
        TestParent parent = TestParent.builder().build();  // All defaults
        TestChild child = new TestChild();  // All defaults

        List<CopyFromParentUtils.FieldMismatch> mismatches =
                CopyFromParentUtils.detectMismatches(parent, child);

        assertTrue(mismatches.isEmpty(),
                "Default values on both sides should not be mismatches");
    }

    // Test: Override flag (should NOT affect mismatch detection)

    @Test
    void testDetectMismatches_ignoresOverrideFlag() {
        TestParent parent = TestParent.builder()
                .transactionId("PARENT-TXN")
                .amount(500)
                .customerId("PARENT-CUST")
                .build();
        ChildWithOverrideFlags child = ChildWithOverrideFlags.builder()
                .txnId("CHILD-TXN")      // override=true, mismatches
                .childAmount(100)         // override=false, mismatches
                .customerId("CHILD-CUST") // override=true, mismatches
                .build();

        List<CopyFromParentUtils.FieldMismatch> mismatches =
                CopyFromParentUtils.detectMismatches(parent, child);

        assertEquals(3, mismatches.size(),
                "detectMismatches should check ALL fields regardless of override flag");
    }

    // Test: Edge cases

    @Test
    void testDetectMismatches_nullParent_returnsEmpty() {
        TestChild child = TestChild.builder().txnId("TXN").build();

        List<CopyFromParentUtils.FieldMismatch> mismatches =
                CopyFromParentUtils.detectMismatches(null, child);

        assertTrue(mismatches.isEmpty());
    }

    @Test
    void testDetectMismatches_nullChild_returnsEmpty() {
        TestParent parent = TestParent.builder().transactionId("TXN").build();

        List<CopyFromParentUtils.FieldMismatch> mismatches =
                CopyFromParentUtils.detectMismatches(parent, null);

        assertTrue(mismatches.isEmpty());
    }

    @Test
    void testDetectMismatches_bothNull_returnsEmpty() {
        List<CopyFromParentUtils.FieldMismatch> mismatches =
                CopyFromParentUtils.detectMismatches(null, null);

        assertTrue(mismatches.isEmpty());
    }

    @Test
    void testDetectMismatches_noParentEntityAnnotation_returnsEmpty() {
        TestParent parent = TestParent.builder().transactionId("TXN").build();
        PlainEntity plain = PlainEntity.builder().name("value").build();

        List<CopyFromParentUtils.FieldMismatch> mismatches =
                CopyFromParentUtils.detectMismatches(parent, plain);

        assertTrue(mismatches.isEmpty(),
                "Should return empty for entities without @ParentEntity");
    }

    // Test: FieldMismatch object structure

    @Test
    void testFieldMismatch_hasCorrectFieldNames() {
        TestParent parent = TestParent.builder().transactionId("PARENT").build();
        TestChild child = TestChild.builder().txnId("CHILD").build();

        List<CopyFromParentUtils.FieldMismatch> mismatches =
                CopyFromParentUtils.detectMismatches(parent, child);

        assertEquals(1, mismatches.size());

        CopyFromParentUtils.FieldMismatch mismatch = mismatches.get(0);
        assertEquals("transactionId", mismatch.getParentFieldName(),
                "Parent field name should match the field on parent class");
        assertEquals("txnId", mismatch.getChildFieldName(),
                "Child field name should match the field on child class");
    }

    @Test
    void testFieldMismatch_hasCorrectValues() {
        TestParent parent = TestParent.builder()
                .transactionId("PARENT-TXN")
                .amount(500)
                .build();
        TestChild child = TestChild.builder()
                .txnId("CHILD-TXN")
                .childAmount(100)
                .build();

        List<CopyFromParentUtils.FieldMismatch> mismatches =
                CopyFromParentUtils.detectMismatches(parent, child);

        CopyFromParentUtils.FieldMismatch txnMismatch = mismatches.stream()
                .filter(m -> m.getParentFieldName().equals("transactionId"))
                .findFirst()
                .orElseThrow();

        assertEquals("PARENT-TXN", txnMismatch.getParentValue());
        assertEquals("CHILD-TXN", txnMismatch.getChildValue());

        CopyFromParentUtils.FieldMismatch amountMismatch = mismatches.stream()
                .filter(m -> m.getParentFieldName().equals("amount"))
                .findFirst()
                .orElseThrow();

        assertEquals(500L, amountMismatch.getParentValue());
        assertEquals(100L, amountMismatch.getChildValue());
    }

    // Test: Multiple invocations (caching)

    @Test
    void testDetectMismatches_differentInstances_sameValues_noMismatch() {
        TestParent parent = TestParent.builder().transactionId("TXN").amount(100).build();
        TestChild child = TestChild.builder().txnId("TXN").childAmount(100).build();

        List<CopyFromParentUtils.FieldMismatch> mismatches =
                CopyFromParentUtils.detectMismatches(parent, child);

        assertTrue(mismatches.isEmpty(),
                "Different object instances with same values should not be mismatches");
    }

    // Test: Integration with copyFields (verify independence)

    @Test
    void testDetectMismatchesBeforeCopy_thenCopyFields_showsExpectedBehavior() {
        TestParent parent = TestParent.builder()
                .transactionId("PARENT-TXN")
                .amount(500)
                .build();
        TestChild child = TestChild.builder()
                .txnId("CHILD-TXN")
                .childAmount(100)
                .build();

        // Detect mismatches BEFORE copy
        List<CopyFromParentUtils.FieldMismatch> mismatchesBefore =
                CopyFromParentUtils.detectMismatches(parent, child);
        assertEquals(2, mismatchesBefore.size(), "Should detect mismatches before copy");

        // Copy fields
        CopyFromParentUtils.copyFields(parent, child, false);

        // Detect mismatches AFTER copy
        List<CopyFromParentUtils.FieldMismatch> mismatchesAfter =
                CopyFromParentUtils.detectMismatches(parent, child);
        assertEquals(0, mismatchesAfter.size(), "Should have no mismatches after copy");
    }
}

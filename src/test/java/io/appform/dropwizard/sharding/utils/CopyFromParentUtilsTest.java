package io.appform.dropwizard.sharding.utils;

import io.appform.dropwizard.sharding.sharding.CopyFromParent;
import io.appform.dropwizard.sharding.sharding.ParentEntity;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class CopyFromParentUtilsTest {

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
    static class PlainEntity {
        private String name;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @ParentEntity(TestParent.class)
    static class EmptyAnnotatedChild {
        // Has @ParentEntity but no @CopyFromParent fields
        private String someField;
    }

    static class ParentBase {
        private String baseField;
        public String getBaseField() { return baseField; }
        public void setBaseField(String v) { this.baseField = v; }
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    static class InheritedParent extends ParentBase {
        private String ownField;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @ParentEntity(InheritedParent.class)
    static class ChildOfInherited {
        @CopyFromParent(field = "baseField")
        private String copied;
    }

    // Tests

    @Test
    public void testCopyFields_copiesAnnotatedFields() {
        TestParent parent = TestParent.builder()
                .transactionId("TXN-123")
                .amount(500)
                .customerId("CUST-1")
                .build();
        TestChild child = TestChild.builder()
                .ownField("mine")
                .build();

        CopyFromParentUtils.copyFields(parent, child);

        assertEquals("TXN-123", child.getTxnId());
        assertEquals(500, child.getChildAmount());
        assertEquals("mine", child.getOwnField(), "non-annotated field should not be touched");
    }

    @Test
    public void testCopyFields_noopForPlainEntity() {
        TestParent parent = TestParent.builder().transactionId("T1").build();
        PlainEntity plain = PlainEntity.builder().name("original").build();

        CopyFromParentUtils.copyFields(parent, plain);
        assertEquals("original", plain.getName());
    }

    @Test
    public void testCopyFields_noopForEmptyAnnotatedChild() {
        TestParent parent = TestParent.builder().transactionId("T1").build();
        EmptyAnnotatedChild child = EmptyAnnotatedChild.builder().someField("value").build();

        CopyFromParentUtils.copyFields(parent, child);
        assertEquals("value", child.getSomeField());
    }

    @Test
    public void testCopyFields_nullParentIsNoop() {
        TestChild child = TestChild.builder().ownField("mine").build();
        assertDoesNotThrow(() -> CopyFromParentUtils.copyFields(null, child));
    }

    @Test
    public void testCopyFields_nullChildIsNoop() {
        TestParent parent = TestParent.builder().transactionId("T1").build();
        assertDoesNotThrow(() -> CopyFromParentUtils.copyFields(parent, null));
    }

    @Test
    public void testCopyFields_parentTypeMismatchThrows() {
        PlainEntity wrongParent = PlainEntity.builder().name("wrong").build();
        TestChild child = TestChild.builder().build();

        assertThrows(IllegalArgumentException.class,
                () -> CopyFromParentUtils.copyFields(wrongParent, child));
    }

    @Test
    public void testCopyFields_inheritedParentField() {
        InheritedParent parent = new InheritedParent("own");
        parent.setBaseField("from-base");
        ChildOfInherited child = new ChildOfInherited();

        CopyFromParentUtils.copyFields(parent, child);

        assertEquals("from-base", child.getCopied());
    }

    @Test
    public void testCopyFields_overwritesExistingValue() {
        TestParent parent = TestParent.builder()
                .transactionId("NEW-TXN")
                .amount(999)
                .build();
        TestChild child = TestChild.builder()
                .txnId("OLD-TXN")
                .childAmount(1)
                .build();

        CopyFromParentUtils.copyFields(parent, child);

        assertEquals("NEW-TXN", child.getTxnId());
        assertEquals(999, child.getChildAmount());
    }

    @Test
    public void testCopyFields_idempotent() {
        TestParent parent = TestParent.builder()
                .transactionId("TXN")
                .amount(100)
                .build();
        TestChild child = TestChild.builder().build();

        CopyFromParentUtils.copyFields(parent, child);
        CopyFromParentUtils.copyFields(parent, child);

        assertEquals("TXN", child.getTxnId());
        assertEquals(100, child.getChildAmount());
    }

    @Test
    public void testCopyFields_copiesNullValues() {
        TestParent parent = TestParent.builder()
                .transactionId(null)
                .amount(0)
                .build();
        TestChild child = TestChild.builder()
                .txnId("existing")
                .childAmount(42)
                .build();

        CopyFromParentUtils.copyFields(parent, child);

        assertNull(child.getTxnId());
        assertEquals(0, child.getChildAmount());
    }
}

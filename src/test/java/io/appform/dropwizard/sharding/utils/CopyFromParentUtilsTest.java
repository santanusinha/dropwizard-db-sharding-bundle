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

class CopyFromParentUtilsTest {

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
        void setBaseField(String v) { this.baseField = v; }
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

        @CopyFromParent(field = "customerId", override = false)
        private String customerId;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @ParentEntity(TestParent.class)
    static class MixedOverrideChild {
        @CopyFromParent(field = "transactionId", override = true)
        private String txnId;

        @CopyFromParent(field = "amount", override = false)
        private long childAmount;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    static class PrimitiveParent {
        private int intVal;
        private boolean boolVal;
        private char charVal;
        private double doubleVal;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @ParentEntity(PrimitiveParent.class)
    static class NoOverridePrimitiveChild {
        @CopyFromParent(field = "intVal", override = false)
        private int intVal;

        @CopyFromParent(field = "boolVal", override = false)
        private boolean boolVal;

        @CopyFromParent(field = "charVal", override = false)
        private char charVal;

        @CopyFromParent(field = "doubleVal", override = false)
        private double doubleVal;
    }

    // Tests

    @Test
    void testCopyFields_copiesAnnotatedFields() {
        TestParent parent = TestParent.builder()
                .transactionId("TXN-123")
                .amount(500)
                .customerId("CUST-1")
                .build();
        TestChild child = TestChild.builder()
                .ownField("mine")
                .build();

        CopyFromParentUtils.copyFields(parent, child, false);

        assertEquals("TXN-123", child.getTxnId());
        assertEquals(500, child.getChildAmount());
        assertEquals("mine", child.getOwnField(), "non-annotated field should not be touched");
    }

    @Test
    void testCopyFields_noopForPlainEntity() {
        TestParent parent = TestParent.builder().transactionId("T1").build();
        PlainEntity plain = PlainEntity.builder().name("original").build();

        CopyFromParentUtils.copyFields(parent, plain, false);
        assertEquals("original", plain.getName());
    }

    @Test
    void testCopyFields_noopForEmptyAnnotatedChild() {
        TestParent parent = TestParent.builder().transactionId("T1").build();
        EmptyAnnotatedChild child = EmptyAnnotatedChild.builder().someField("value").build();

        CopyFromParentUtils.copyFields(parent, child, false);
        assertEquals("value", child.getSomeField());
    }

    @Test
    void testCopyFields_nullParentIsNoop() {
        TestChild child = TestChild.builder().ownField("mine").build();
        assertDoesNotThrow(() -> CopyFromParentUtils.copyFields(null, child, false));
    }

    @Test
    void testCopyFields_nullChildIsNoop() {
        TestParent parent = TestParent.builder().transactionId("T1").build();
        assertDoesNotThrow(() -> CopyFromParentUtils.copyFields(parent, null, false));
    }

    @Test
    void testCopyFields_parentTypeMismatchThrows() {
        PlainEntity wrongParent = PlainEntity.builder().name("wrong").build();
        TestChild child = TestChild.builder().build();

        assertThrows(IllegalArgumentException.class,
                () -> CopyFromParentUtils.copyFields(wrongParent, child, false));
    }

    @Test
    void testCopyFields_inheritedParentField() {
        InheritedParent parent = new InheritedParent("own");
        parent.setBaseField("from-base");
        ChildOfInherited child = new ChildOfInherited();

        CopyFromParentUtils.copyFields(parent, child, false);

        assertEquals("from-base", child.getCopied());
    }

    @Test
    void testCopyFields_overwritesExistingValue() {
        TestParent parent = TestParent.builder()
                .transactionId("NEW-TXN")
                .amount(999)
                .build();
        TestChild child = TestChild.builder()
                .txnId("OLD-TXN")
                .childAmount(1)
                .build();

        CopyFromParentUtils.copyFields(parent, child, false);

        assertEquals("NEW-TXN", child.getTxnId());
        assertEquals(999, child.getChildAmount());
    }

    @Test
    void testCopyFields_idempotent() {
        TestParent parent = TestParent.builder()
                .transactionId("TXN")
                .amount(100)
                .build();
        TestChild child = TestChild.builder().build();

        CopyFromParentUtils.copyFields(parent, child, false);
        CopyFromParentUtils.copyFields(parent, child, false);

        assertEquals("TXN", child.getTxnId());
        assertEquals(100, child.getChildAmount());
    }

    @Test
    void testCopyFields_copiesNullValues() {
        TestParent parent = TestParent.builder()
                .transactionId(null)
                .amount(0)
                .build();
        TestChild child = TestChild.builder()
                .txnId("existing")
                .childAmount(42)
                .build();

        CopyFromParentUtils.copyFields(parent, child, false);

        assertNull(child.getTxnId());
        assertEquals(0, child.getChildAmount());
    }

    @Test
    void testNoOverride_skipsWhenChildFieldAlreadySet() {
        TestParent parent = TestParent.builder()
                .transactionId("PARENT-TXN")
                .amount(500)
                .customerId("PARENT-CUST")
                .build();
        NoOverrideChild child = NoOverrideChild.builder()
                .txnId("CHILD-TXN")
                .childAmount(100)
                .customerId("CHILD-CUST")
                .build();

        CopyFromParentUtils.copyFields(parent, child, false);

        assertEquals("CHILD-TXN", child.getTxnId(), "should not override existing String");
        assertEquals(100, child.getChildAmount(), "should not override existing primitive");
        assertEquals("CHILD-CUST", child.getCustomerId(), "should not override existing String");
    }

    @Test
    void testNoOverride_doesNotCopyEvenWhenChildFieldIsDefault() {
        TestParent parent = TestParent.builder()
                .transactionId("PARENT-TXN")
                .amount(500)
                .customerId("PARENT-CUST")
                .build();
        NoOverrideChild child = new NoOverrideChild(); // all defaults: null, 0, null

        CopyFromParentUtils.copyFields(parent, child, false);

        assertNull(child.getTxnId(), "override=false should never copy");
        assertEquals(0, child.getChildAmount(), "override=false should never copy");
        assertNull(child.getCustomerId(), "override=false should never copy");
    }

    @Test
    void testNoOverride_partiallySet() {
        TestParent parent = TestParent.builder()
                .transactionId("PARENT-TXN")
                .amount(500)
                .customerId("PARENT-CUST")
                .build();
        NoOverrideChild child = NoOverrideChild.builder()
                .txnId("EXISTING")
                .build(); // childAmount = 0, customerId = null

        CopyFromParentUtils.copyFields(parent, child, false);

        assertEquals("EXISTING", child.getTxnId(), "override=false should never copy");
        assertEquals(0, child.getChildAmount(), "override=false should never copy");
        assertNull(child.getCustomerId(), "override=false should never copy");
    }

    @Test
    void testMixedOverride_respectsPerFieldSetting() {
        TestParent parent = TestParent.builder()
                .transactionId("PARENT-TXN")
                .amount(500)
                .build();
        MixedOverrideChild child = MixedOverrideChild.builder()
                .txnId("EXISTING-TXN")
                .childAmount(100)
                .build();

        CopyFromParentUtils.copyFields(parent, child, false);

        assertEquals("PARENT-TXN", child.getTxnId(), "override=true should always copy");
        assertEquals(100, child.getChildAmount(), "override=false should not override existing value");
    }

    @Test
    void testNoOverride_allPrimitiveTypes_defaultFieldsAreNotOverridden() {
        PrimitiveParent parent = PrimitiveParent.builder()
                .intVal(42)
                .boolVal(true)
                .charVal('X')
                .doubleVal(3.14)
                .build();
        NoOverridePrimitiveChild child = new NoOverridePrimitiveChild(); // all primitive defaults

        CopyFromParentUtils.copyFields(parent, child, false);

        assertEquals(0, child.getIntVal(), "override=false should never copy");
        assertEquals(false, child.isBoolVal(), "override=false should never copy");
        assertEquals('\0', child.getCharVal(), "override=false should never copy");
        assertEquals(0.0, child.getDoubleVal(), "override=false should never copy");
    }

    @Test
    void testOverrideTrue_isDefaultBehavior() {
        // TestChild uses override=true (default), so existing values should be overwritten
        TestParent parent = TestParent.builder()
                .transactionId("NEW")
                .amount(999)
                .build();
        TestChild child = TestChild.builder()
                .txnId("OLD")
                .childAmount(1)
                .build();

        CopyFromParentUtils.copyFields(parent, child, false);

        assertEquals("NEW", child.getTxnId(), "override=true (default) should overwrite");
        assertEquals(999, child.getChildAmount(), "override=true (default) should overwrite");
    }

    // Inherited @ParentEntity tests

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @ParentEntity(TestParent.class)
    static class AnnotatedBase {
        @CopyFromParent(field = "transactionId")
        private String txnId;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    static class InheritedChild extends AnnotatedBase {
        private String ownField;

        @Builder
        public InheritedChild(String txnId, String ownField) {
            super(txnId);
            this.ownField = ownField;
        }
    }

    @Test
    void testCopyFields_inheritsParentEntityFromSuperclass() {
        TestParent parent = TestParent.builder().transactionId("INHERITED-TXN").build();
        InheritedChild child = InheritedChild.builder().ownField("mine").build();

        CopyFromParentUtils.copyFields(parent, child, false);

        assertEquals("INHERITED-TXN", child.getTxnId());
        assertEquals("mine", child.getOwnField());
    }

    @Test
    void testCopyFields_inheritedChild_overwritesExistingValue() {
        TestParent parent = TestParent.builder().transactionId("NEW").build();
        InheritedChild child = InheritedChild.builder().txnId("OLD").ownField("mine").build();

        CopyFromParentUtils.copyFields(parent, child, false);

        assertEquals("NEW", child.getTxnId());
    }

    // Tests for private fields declared in superclasses

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    static class SuperclassParentBase {
        private long partitionId;
        private String baseField;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    static class ConcreteParent extends SuperclassParentBase {
        private String concreteField;

        @Builder
        public ConcreteParent(long partitionId, String baseField, String concreteField) {
            super(partitionId, baseField);
            this.concreteField = concreteField;
        }
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @ParentEntity(ConcreteParent.class)
    static class ChildBase {
        @CopyFromParent(field = "partitionId")
        private long partitionId;

        @CopyFromParent(field = "baseField")
        private String baseField;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    static class ConcreteChild extends ChildBase {
        private String childOwnField;

        @Builder
        public ConcreteChild(long partitionId, String baseField, String childOwnField) {
            super(partitionId, baseField);
            this.childOwnField = childOwnField;
        }
    }

    @Test
    void testCopyFields_privateFieldInParentSuperclass() {
        ConcreteParent parent = ConcreteParent.builder()
                .partitionId(42L).baseField("base").concreteField("concrete").build();
        ConcreteChild child = ConcreteChild.builder().childOwnField("mine").build();

        CopyFromParentUtils.copyFields(parent, child, false);

        assertEquals(42L, child.getPartitionId());
        assertEquals("base", child.getBaseField());
        assertEquals("mine", child.getChildOwnField());
    }

    @Test
    void testCopyFields_privateFieldInChildSuperclass() {
        ConcreteParent parent = ConcreteParent.builder()
                .partitionId(99L).baseField("fromParent").build();
        ConcreteChild child = ConcreteChild.builder()
                .partitionId(1L).baseField("existing").childOwnField("mine").build();

        CopyFromParentUtils.copyFields(parent, child, false);

        assertEquals(99L, child.getPartitionId());
        assertEquals("fromParent", child.getBaseField());
    }

    @Test
    void testCopyFields_bothFieldsInSuperclasses() {
        ConcreteParent parent = ConcreteParent.builder().partitionId(777L).build();
        ConcreteChild child = ConcreteChild.builder().build();

        CopyFromParentUtils.copyFields(parent, child, false);

        assertEquals(777L, child.getPartitionId());
    }
}

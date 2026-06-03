package io.appform.dropwizard.sharding.dao.operations;

import lombok.val;
import org.hibernate.Session;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.Mockito;

import java.util.function.Function;

public class CopyFromParentAndSaveTest {

    @Mock
    Session session;

    @Test
    public void testApply_saverInvokedAndAfterSaveApplied() {
        Function<String, String> spiedSaver = LambdaTestUtils.spiedFunction(e -> e + "_saved");

        val op = CopyFromParentAndSave.<String, Integer, Integer>builder()
                .entity("hello")
                .parent(42)
                .saver(spiedSaver::apply)
                .afterSave(String::length)
                .build();

        Assertions.assertEquals(11, op.apply(session));  // "hello_saved".length() == 11
        Assertions.assertEquals(42, op.getParent());
        Assertions.assertEquals("hello", op.getEntity());
        Assertions.assertEquals(OpType.COPY_FROM_PARENT_AND_SAVE, op.getOpType());
        Mockito.verify(spiedSaver, Mockito.times(1)).apply(Mockito.any());
    }

    @Test
    public void testSaverCanBeReplaced() {
        // Simulates what CopyFromParentPersistor does: wrap the saver
        Function<String, String> originalSaver = LambdaTestUtils.spiedFunction(e -> e + "_original");

        val op = CopyFromParentAndSave.<String, String, String>builder()
                .entity("child")
                .parent("parent")
                .saver(originalSaver::apply)
                .build();

        // Wrap saver (as an observer would)
        op.setSaver(e -> originalSaver.apply("from_parent_" + e));

        Assertions.assertEquals("from_parent_child_original", op.apply(session));
        Mockito.verify(originalSaver, Mockito.times(1)).apply(Mockito.any());
    }

    @Test
    public void testNullConstraints() {
        Assertions.assertThrows(NullPointerException.class, () ->
                CopyFromParentAndSave.builder().entity(null).parent("p").saver(e -> e).build());
        Assertions.assertThrows(NullPointerException.class, () ->
                CopyFromParentAndSave.builder().entity("e").parent(null).saver(e -> e).build());
        Assertions.assertThrows(NullPointerException.class, () ->
                CopyFromParentAndSave.builder().entity("e").parent("p").saver(null).build());
    }
}

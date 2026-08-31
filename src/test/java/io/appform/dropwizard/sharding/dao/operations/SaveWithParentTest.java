package io.appform.dropwizard.sharding.dao.operations;

import lombok.val;
import org.hibernate.Session;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

import java.util.function.Function;

import static org.mockito.Mockito.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class SaveWithParentTest {

    @Mock
    Session session;

    @Test
    void testApply_saverInvokedAndAfterSaveApplied() {
        Function<String, String> spiedSaver = LambdaTestUtils.spiedFunction(e -> e + "_saved");

        val op = SaveWithParent.<String, Integer, Integer>builder()
                .entity("hello")
                .parent(42)
                .saver(spiedSaver::apply)
                .afterSave(String::length)
                .build();

        Assertions.assertEquals(11, op.apply(session));  // "hello_saved".length() == 11
        Assertions.assertEquals(42, op.getParent());
        Assertions.assertEquals("hello", op.getEntity());
        Assertions.assertEquals(OpType.SAVE_WITH_PARENT, op.getOpType());
        verify(spiedSaver, times(1)).apply(any());
    }

    @Test
    void testSaverCanBeReplaced() {
        // Simulates what CopyFromParentPersistor does: wrap the saver
        Function<String, String> originalSaver = LambdaTestUtils.spiedFunction(e -> e + "_original");

        val op = SaveWithParent.<String, String, String>builder()
                .entity("child")
                .parent("parent")
                .saver(originalSaver::apply)
                .build();

        // Wrap saver (as an observer would)
        op.setSaver(e -> originalSaver.apply("from_parent_" + e));

        Assertions.assertEquals("from_parent_child_original", op.apply(session));
        verify(originalSaver, times(1)).apply(any());
    }

    @Test
    void testNullEntity_throwsNPE() {
        Assertions.assertThrows(NullPointerException.class, () ->
                SaveWithParent.builder().entity(null).build());
    }

    @Test
    void testNullParent_throwsNPE() {
        Assertions.assertThrows(NullPointerException.class, () ->
                SaveWithParent.builder().entity("e").parent(null).build());
    }

    @Test
    void testNullSaver_throwsNPE() {
        Assertions.assertThrows(NullPointerException.class, () ->
                SaveWithParent.builder().entity("e").parent("p").saver(null).build());
    }
}

package io.appform.dropwizard.sharding.dao.operations;

import io.appform.dropwizard.sharding.dao.operations.lockedcontext.LockAndExecute;
import io.appform.dropwizard.sharding.dao.operations.lookupdao.CreateOrUpdateByLookupKey;
import io.appform.dropwizard.sharding.dao.operations.lookupdao.DeleteByLookupKey;
import io.appform.dropwizard.sharding.dao.operations.lookupdao.GetAndUpdateByLookupKey;
import io.appform.dropwizard.sharding.dao.operations.lookupdao.GetByLookupKey;
import io.appform.dropwizard.sharding.dao.operations.lookupdao.readonlycontext.ReadOnlyForLookupDao;
import io.appform.dropwizard.sharding.dao.operations.relationaldao.CreateOrUpdate;
import io.appform.dropwizard.sharding.dao.operations.relationaldao.CreateOrUpdateInLockedContext;
import io.appform.dropwizard.sharding.dao.operations.relationaldao.readonlycontext.ReadOnlyForRelationalDao;
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
        val builder = SaveWithParent.builder();
        Assertions.assertThrows(NullPointerException.class, () -> builder.entity(null));
    }

    @Test
    void testNullParent_throwsNPE() {
        val builder = SaveWithParent.builder().entity("e");
        Assertions.assertThrows(NullPointerException.class, () -> builder.parent(null));
    }

    @Test
    void testNullSaver_throwsNPE() {
        val builder = SaveWithParent.builder().entity("e").parent("p");
        Assertions.assertThrows(NullPointerException.class, () -> builder.saver(null));
    }

    /**
     * A pre-existing (legacy) visitor implementation that predates {@link SaveWithParent} and only
     * overrides the mandatory {@link OpContext.OpContextVisitor#visit(Save)} method, to test
     * backward compatibility.
     * This doesnt override visit(SaveWithParent), so if SaveWithParent is visited, it will call the default
     */
    private static class LegacySaverWrappingVisitor implements OpContext.OpContextVisitor<Void> {
        @Override
        public <U, V> Void visit(Save<U, V> opContext) {
            final var oldSaver = opContext.getSaver();
            opContext.setSaver(e -> {
                @SuppressWarnings("unchecked")
                U wrapped = (U) (((String) e) + "_wrapped");
                return oldSaver.apply(wrapped);
            });
            return null;
        }

        @Override
        public Void visit(Count opContext) { return null; }
        @Override
        public Void visit(CountByQuerySpec opContext) { return null; }
        @Override
        public <T, R> Void visit(Get<T, R> opContext) { return null; }
        @Override
        public <T> Void visit(GetAndUpdate<T> opContext) { return null; }
        @Override
        public <T, R> Void visit(GetByLookupKey<T, R> opContext) { return null; }
        @Override
        public <T> Void visit(GetAndUpdateByLookupKey<T> opContext) { return null; }
        @Override
        public <T> Void visit(ReadOnlyForLookupDao<T> opContext) { return null; }
        @Override
        public <T> Void visit(ReadOnlyForRelationalDao<T> opContext) { return null; }
        @Override
        public <T> Void visit(LockAndExecute<T> opContext) { return null; }
        @Override
        public Void visit(UpdateByQuery opContext) { return null; }
        @Override
        public <T> Void visit(UpdateWithScroll<T> opContext) { return null; }
        @Override
        public <T> Void visit(UpdateAll<T> opContext) { return null; }
        @Override
        public <T> Void visit(SelectAndUpdate<T> opContext) { return null; }
        @Override
        public <T> Void visit(RunInSession<T> opContext) { return null; }
        @Override
        public <T> Void visit(RunWithCriteria<T> opContext) { return null; }
        @Override
        public Void visit(DeleteByLookupKey opContext) { return null; }
        @Override
        public <T> Void visit(SaveAll<T> opContext) { return null; }
        @Override
        public <T> Void visit(CreateOrUpdateByLookupKey<T> opContext) { return null; }
        @Override
        public <T> Void visit(CreateOrUpdate<T> opContext) { return null; }
        @Override
        public <T, U> Void visit(CreateOrUpdateInLockedContext<T, U> opContext) { return null; }
        @Override
        public <T, R> Void visit(Select<T, R> opContext) { return null; }
    }

    @Test
    void testLegacyVisitor_doesNotThrow_andPreservesSaveBehavior() {
        // Compatibility test: a visitor without SaveWithParent implementation, which only overrides
        // visit(Save), must keep working when it visits a SaveWithParent OpContext,
        // and any saver wrapping it performs on visit(Save) must still apply.
        Function<String, String> rawSaver = LambdaTestUtils.spiedFunction(e -> e + "_saved");

        val op = SaveWithParent.<String, String, String>builder()
                .entity("child")
                .parent("parent")
                .saver(rawSaver::apply)
                .build();

        Assertions.assertDoesNotThrow(() -> op.visit(new LegacySaverWrappingVisitor()));

        // The legacy visitor's saver wrapping (applied via visit(Save)) must be reflected here.
        Assertions.assertEquals("child_wrapped_saved", op.apply(session));
    }
}

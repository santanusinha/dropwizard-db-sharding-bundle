package io.appform.dropwizard.sharding.dao.operations;

import lombok.val;
import org.hibernate.Session;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.UnaryOperator;

import static org.mockito.Mockito.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class SaveWithParentTest {

    @Mock
    Session session;

    // ---------------------------------------------------------------------------------------------------------------
    // Construction / behaviour
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    void testApply_saverInvokedAndAfterSaveApplied() {
        Function<String, String> spiedSaver = LambdaTestUtils.spiedFunction(e -> e + "_saved");

        val op = SaveWithParent.<String, Integer, Integer>saveWithParentBuilder()
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
    void testIsASave() {
        val op = SaveWithParent.<String, String, String>saveWithParentBuilder()
                .entity("child").parent("parent").saver(e -> e).build();

        Assertions.assertTrue(op instanceof Save, "SaveWithParent must be usable wherever a Save is expected");
        final Save<String, String> asSave = op;
        Assertions.assertEquals("child", asSave.getEntity());
    }

    @Test
    void testAfterSaveNotProvided_returnsSavedEntity() {
        val op = SaveWithParent.<String, String, String>saveWithParentBuilder()
                .entity("child").parent("parent").saver(e -> e + "_saved").build();

        Assertions.assertNotNull(op.getAfterSave());
        Assertions.assertEquals("child_saved", op.apply(session));
    }

    @Test
    void testAfterSaveExplicitlyNull_returnsSavedEntity() {
        val op = SaveWithParent.<String, String, String>saveWithParentBuilder()
                .entity("child").parent("parent").saver(e -> e + "_saved").afterSave(null).build();

        Assertions.assertNotNull(op.getAfterSave());
        Assertions.assertEquals("child_saved", op.apply(session));
    }

    @Test
    void testSaverCanBeReplaced() {
        Function<String, String> originalSaver = LambdaTestUtils.spiedFunction(e -> e + "_original");

        val op = SaveWithParent.<String, String, String>saveWithParentBuilder()
                .entity("child")
                .parent("parent")
                .saver(originalSaver::apply)
                .build();

        op.setSaver(e -> originalSaver.apply("from_parent_" + e));

        Assertions.assertEquals("from_parent_child_original", op.apply(session));
        verify(originalSaver, times(1)).apply(any());
    }

    @Test
    void testNullEntity_throwsNPE() {
        val builder = SaveWithParent.<String, String, String>saveWithParentBuilder()
                .parent("p").saver(e -> e);
        Assertions.assertThrows(NullPointerException.class, () -> builder.entity(null).build());
    }

    @Test
    void testNullParent_throwsNPE() {
        val builder = SaveWithParent.<String, String, String>saveWithParentBuilder()
                .entity("e").saver(e -> e);
        Assertions.assertThrows(NullPointerException.class, () -> builder.parent(null).build());
    }

    @Test
    void testNullSaver_throwsNPE() {
        val builder = SaveWithParent.<String, String, String>saveWithParentBuilder()
                .entity("e").parent("p");
        Assertions.assertThrows(NullPointerException.class, () -> builder.saver(null).build());
    }

    @Test
    void testEqualsHashCodeToString_includeParentAndSaveFields() {
        final UnaryOperator<String> saver = e -> e;
        final Function<String, String> afterSave = e -> e;
        val a = SaveWithParent.<String, String, String>saveWithParentBuilder()
                .entity("child").parent("parent").saver(saver).afterSave(afterSave).build();
        val b = SaveWithParent.<String, String, String>saveWithParentBuilder()
                .entity("child").parent("parent").saver(saver).afterSave(afterSave).build();
        val otherParent = SaveWithParent.<String, String, String>saveWithParentBuilder()
                .entity("child").parent("other").saver(saver).afterSave(afterSave).build();
        val otherEntity = SaveWithParent.<String, String, String>saveWithParentBuilder()
                .entity("other").parent("parent").saver(saver).afterSave(afterSave).build();
        val plainSave = Save.<String, String>builder().entity("child").saver(saver).afterSave(afterSave).build();

        Assertions.assertEquals(a, b);
        Assertions.assertEquals(a.hashCode(), b.hashCode());
        Assertions.assertNotEquals(a, otherParent);
        Assertions.assertNotEquals(a, otherEntity);
        // Symmetric: a plain Save is never equal to a SaveWithParent, in either direction
        Assertions.assertNotEquals(a, plainSave);
        Assertions.assertNotEquals(plainSave, a);
        Assertions.assertTrue(a.toString().contains("parent=parent"));
        Assertions.assertTrue(a.toString().contains("entity=child"));
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Visitor dispatch
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * A visitor written before {@link SaveWithParent} existed: it implements every abstract method, i.e. only
     * {@code visit(Save)} for saves, and does NOT override {@code visit(SaveWithParent)}.
     */
    private abstract static class LegacyVisitor<P> extends LegacyOpContextVisitor<P> {
        final List<Save<?, ?>> savesVisited = new ArrayList<>();

        @Override
        public <U, V> P visit(Save<U, V> opContext) {
            savesVisited.add(opContext);
            onSave(opContext);
            return result();
        }

        abstract <U, V> void onSave(Save<U, V> opContext);

        P result() {
            return null;
        }
    }

    private static SaveWithParent<String, String, String> newOp() {
        return SaveWithParent.<String, String, String>saveWithParentBuilder()
                .entity("child")
                .parent("parent")
                .saver(e -> e + "_saved")
                .build();
    }

    @Test
    void testLegacyVisitor_receivesTheSameInstanceThroughVisitSave() {
        val op = newOp();
        val visitor = new LegacyVisitor<Void>() {
            @Override
            <U, V> void onSave(Save<U, V> opContext) {
                // no-op
            }
        };

        Assertions.assertDoesNotThrow(() -> op.visit(visitor));

        Assertions.assertEquals(1, visitor.savesVisited.size(), "visit(Save) must be invoked exactly once");
        Assertions.assertSame(op, visitor.savesVisited.get(0), "visit(Save) must receive the same instance");
    }

    @Test
    void testLegacyVisitor_returnValueOfVisitSaveIsPropagated() {
        val op = newOp();
        val visitor = new LegacyVisitor<String>() {
            @Override
            <U, V> void onSave(Save<U, V> opContext) {
                // no-op
            }

            @Override
            String result() {
                return "visited";
            }
        };

        Assertions.assertEquals("visited", op.visit(visitor));
    }

    @Test
    void testLegacyVisitor_saverWrapping_isReflected() {
        val op = newOp();
        op.visit(new LegacyVisitor<Void>() {
            @Override
            @SuppressWarnings("unchecked")
            <U, V> void onSave(Save<U, V> opContext) {
                final UnaryOperator<U> oldSaver = opContext.getSaver();
                opContext.setSaver(e -> oldSaver.apply((U) (e + "_wrapped")));
            }
        });

        Assertions.assertEquals("child_wrapped_saved", op.apply(session));
    }

    @Test
    void testLegacyVisitor_afterSaveReplacedWithNonComposableFunction_isReflected() {
        val op = SaveWithParent.<String, String, String>saveWithParentBuilder()
                .entity("child")
                .parent("parent")
                .saver(e -> e + "_saved")
                .afterSave(e -> "original:" + e)
                .build();

        op.visit(new LegacyVisitor<Void>() {
            @Override
            @SuppressWarnings("unchecked")
            <U, V> void onSave(Save<U, V> opContext) {
                opContext.setAfterSave(e -> (V) ("replaced:" + e));
            }
        });

        Assertions.assertEquals("replaced:child_saved", op.apply(session));
    }

    @Test
    void testLegacyVisitor_entityReplaced_isReflected() {
        val op = newOp();
        op.visit(new LegacyVisitor<Void>() {
            @Override
            @SuppressWarnings("unchecked")
            <U, V> void onSave(Save<U, V> opContext) {
                opContext.setEntity((U) "other");
            }
        });

        Assertions.assertEquals("other", op.getEntity());
        Assertions.assertEquals("other_saved", op.apply(session));
    }

    @Test
    void testLegacyVisitor_mutationAfterVisitReturns_isReflected() {
        val op = newOp();
        val visitor = new LegacyVisitor<Void>() {
            @Override
            <U, V> void onSave(Save<U, V> opContext) {
                // keeps the reference (in savesVisited) and mutates it later
            }
        };
        op.visit(visitor);

        @SuppressWarnings("unchecked")
        val captured = (Save<String, String>) visitor.savesVisited.get(0);
        captured.setSaver(e -> e + "_late");

        Assertions.assertEquals("child_late", op.apply(session));
    }

    @Test
    void testLegacyVisitor_canReachParentViaInstanceof() {
        val op = newOp();
        val parents = new ArrayList<Object>();
        op.visit(new LegacyVisitor<Void>() {
            @Override
            <U, V> void onSave(Save<U, V> opContext) {
                if (opContext instanceof SaveWithParent) {
                    parents.add(((SaveWithParent<?, ?, ?>) opContext).getParent());
                }
            }
        });

        Assertions.assertEquals(List.of("parent"), parents);
    }

    @Test
    void testVisitorOverridingVisitSaveWithParent_getsIt_andVisitSaveIsNotCalled() {
        val op = newOp();
        val parents = new ArrayList<Object>();
        val visitor = new LegacyVisitor<String>() {
            @Override
            <U, V> void onSave(Save<U, V> opContext) {
                // no-op
            }

            @Override
            public <T, R, U> String visit(SaveWithParent<T, R, U> opContext) {
                parents.add(opContext.getParent());
                return "withParent";
            }
        };

        Assertions.assertEquals("withParent", op.visit(visitor));
        Assertions.assertEquals(List.of("parent"), parents);
        Assertions.assertTrue(visitor.savesVisited.isEmpty(), "visit(Save) must not be invoked");
    }

    @Test
    void testPlainSave_goesToVisitSave_notVisitSaveWithParent() {
        val save = Save.<String, String>builder().entity("e").saver(e -> e).build();
        val parents = new ArrayList<Object>();
        val visitor = new LegacyVisitor<Void>() {
            @Override
            <U, V> void onSave(Save<U, V> opContext) {
                // no-op
            }

            @Override
            public <T, R, U> Void visit(SaveWithParent<T, R, U> opContext) {
                parents.add(opContext.getParent());
                return null;
            }
        };

        save.visit(visitor);

        Assertions.assertEquals(1, visitor.savesVisited.size());
        Assertions.assertSame(save, visitor.savesVisited.get(0));
        Assertions.assertTrue(parents.isEmpty());
    }
}

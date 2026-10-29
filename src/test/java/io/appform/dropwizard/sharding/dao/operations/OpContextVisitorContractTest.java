package io.appform.dropwizard.sharding.dao.operations;

import org.junit.jupiter.api.Test;
import org.reflections.Reflections;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the contract of {@link OpContext.OpContextVisitor}:
 * <ul>
 *     <li>{@code visit(SaveWithParent)} is the only default method. Any new op must be added as an abstract method
 *     so that every visitor is forced (at compile time) to handle it instead of silently skipping it.</li>
 *     <li>Every concrete {@link OpContext} has a dedicated {@code visit} overload and dispatches to it by overriding
 *     {@link OpContext#visit(OpContext.OpContextVisitor)} itself. Otherwise a subclass of an existing op (e.g. a
 *     subclass of {@link Save}) would silently be dispatched as its parent type.</li>
 * </ul>
 */
class OpContextVisitorContractTest {

    private static final Class<?> VISITOR = OpContext.OpContextVisitor.class;

    @Test
    void onlyVisitSaveWithParentIsDefault() {
        final List<Method> defaults = Arrays.stream(VISITOR.getDeclaredMethods())
                .filter(Method::isDefault)
                .collect(Collectors.toList());

        assertEquals(1, defaults.size(),
                "OpContextVisitor must have exactly one default method (visit(SaveWithParent)). New ops must be "
                        + "added as abstract methods. Found: " + defaults);
        assertEquals("visit", defaults.get(0).getName());
        assertEquals(List.of(SaveWithParent.class), List.of(defaults.get(0).getParameterTypes()));
    }

    @Test
    void everyConcreteOpContextHasItsOwnVisitOverloadAndDispatchesToIt() throws Exception {
        final Set<Class<?>> concreteOps = new Reflections(OpContext.class.getPackageName())
                .getSubTypesOf(OpContext.class)
                .stream()
                .filter(c -> !Modifier.isAbstract(c.getModifiers()))
                .filter(c -> !c.isAnonymousClass() && !c.isLocalClass())
                .collect(Collectors.toSet());

        assertTrue(concreteOps.contains(Save.class));
        assertTrue(concreteOps.contains(SaveWithParent.class));

        final Set<Class<?>> visitorParamTypes = Arrays.stream(VISITOR.getDeclaredMethods())
                .filter(m -> m.getName().equals("visit") && m.getParameterCount() == 1)
                .map(m -> m.getParameterTypes()[0])
                .collect(Collectors.toSet());

        for (Class<?> op : concreteOps) {
            assertTrue(visitorParamTypes.contains(op),
                    "OpContextVisitor has no visit(" + op.getSimpleName() + ") overload");
            final Method dispatch = op.getDeclaredMethod("visit", VISITOR);
            assertFalse(Modifier.isAbstract(dispatch.getModifiers()),
                    op.getSimpleName() + " must override visit(OpContextVisitor) itself");
        }
    }
}

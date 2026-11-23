package io.appform.dropwizard.sharding;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EncapsulationTest {

    static void assertNoAccessibleConstructors(Class<?> type) {
        for (Constructor<?> ctor : type.getDeclaredConstructors()) {
            final int mod = ctor.getModifiers();
            assertFalse(Modifier.isPublic(mod) || Modifier.isProtected(mod), "Accessible constructor: " + ctor);
        }
    }

    @Test
    void shardCalculatorIsSealedWithHiddenFinalImplementation() throws Exception {
        assertTrue(ShardCalculator.class.isSealed());
        final Class<?>[] permitted = ShardCalculator.class.getPermittedSubclasses();
        assertEquals(1, permitted.length);
        final Class<?> impl = permitted[0];
        assertTrue(Modifier.isFinal(impl.getModifiers()));
        assertFalse(Modifier.isPublic(impl.getModifiers()));
        assertNoAccessibleConstructors(impl);

        final Class<?> factory = Class.forName("io.appform.dropwizard.sharding.ShardCalculators");
        assertFalse(Modifier.isPublic(factory.getModifiers()));
        assertTrue(Modifier.isFinal(factory.getModifiers()));
        for (Constructor<?> ctor : factory.getDeclaredConstructors()) {
            assertTrue(Modifier.isPrivate(ctor.getModifiers()));
        }
    }

    @Test
    void registryCannotBeInstantiatedSubclassedOrMutatedFromOutside() throws Exception {
        assertTrue(Modifier.isFinal(ShardCalculatorRegistry.class.getModifiers()));
        for (Constructor<?> ctor : ShardCalculatorRegistry.class.getDeclaredConstructors()) {
            assertTrue(Modifier.isPrivate(ctor.getModifiers()), ctor.toString());
        }
        for (Method m : ShardCalculatorRegistry.class.getDeclaredMethods()) {
            if (Modifier.isPublic(m.getModifiers()) || Modifier.isProtected(m.getModifiers())) {
                assertTrue(Set.of("get", "contains", "all").contains(m.getName()), "Unexpected accessible method: " + m);
            }
        }
        final Method register = ShardCalculatorRegistry.class.getDeclaredMethod("register", ShardCalculator.class);
        assertFalse(Modifier.isPublic(register.getModifiers()) || Modifier.isProtected(register.getModifiers()));
        final Method clear = ShardCalculatorRegistry.class.getDeclaredMethod("clear");
        assertFalse(Modifier.isPublic(clear.getModifiers()) || Modifier.isProtected(clear.getModifiers()));
    }
}

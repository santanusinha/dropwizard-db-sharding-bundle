package io.appform.dropwizard.sharding.dao.operations;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.reflections.Reflections;

import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Set;

public class OpContextTest {

    /**
     * Fields in the opcontext implementations should be mutable so that they are available for mutation by observers.
     */
    @Test
    void testFieldsAreMutable() {
        Reflections reflections = new Reflections("io.appform.dropwizard.sharding.dao.operations");
        Set<Class<? extends OpContext>> classes = reflections.getSubTypesOf(OpContext.class);
        classes.stream().forEach(c -> {
            if (Arrays.stream(c.getDeclaredFields()).anyMatch(field -> Modifier.isFinal(field.getModifiers()))) {
                Assertions.fail("Immutable field in class " + c.getSimpleName());
            }
        });

    }
}

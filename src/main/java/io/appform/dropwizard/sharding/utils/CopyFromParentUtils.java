package io.appform.dropwizard.sharding.utils;

import io.appform.dropwizard.sharding.sharding.CopyFromParent;
import io.appform.dropwizard.sharding.sharding.ParentEntity;
import lombok.AllArgsConstructor;

import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class CopyFromParentUtils {

    /**
     * Cache keyed by child class → pre-computed field mappings.
     * Populated once per entity type per JVM lifetime.
     */
    private static final Map<Class<?>, List<FieldMapping>> CACHE = new ConcurrentHashMap<>();

    /**
     * Sentinel value for classes without @ParentEntity — avoids repeated reflection.
     */
    private static final List<FieldMapping> NO_MAPPINGS = Collections.emptyList();

    /**
     * Copies annotated fields from parent to child.
     * No-op if child class has no @ParentEntity or no @CopyFromParent fields.
     *
     * @param parent  The parent entity (source of field values)
     * @param child   The child entity (target)
     * @throws IllegalArgumentException if parent type doesn't match @ParentEntity declaration
     */
    public static <T, U> void copyFields(T parent, U child) {
        if (parent == null || child == null) {
            return;
        }

        List<FieldMapping> mappings = CACHE.computeIfAbsent(child.getClass(), cls -> {
            ParentEntity parentAnn = cls.getAnnotation(ParentEntity.class);
            if (parentAnn == null) {
                return NO_MAPPINGS;
            }

            List<FieldMapping> result = new ArrayList<>();
            for (Field childField : getAllFields(cls)) {
                CopyFromParent copyAnn = childField.getAnnotation(CopyFromParent.class);
                if (copyAnn == null) {
                    continue;
                }

                Field parentField = findField(parentAnn.value(), copyAnn.field());
                if (parentField == null) {
                    // This should never happen if compile-time processor ran.
                    // Runtime safety net.
                    throw new IllegalStateException(
                            String.format("@CopyFromParent(field=\"%s\") on %s.%s: "
                                            + "field not found on parent %s",
                                    copyAnn.field(), cls.getSimpleName(),
                                    childField.getName(), parentAnn.value().getSimpleName()));
                }

                childField.setAccessible(true);
                parentField.setAccessible(true);
                result.add(new FieldMapping(parentField, childField));
            }
            return result.isEmpty() ? NO_MAPPINGS : Collections.unmodifiableList(result);
        });

        if (mappings == NO_MAPPINGS) {
            return;
        }

        // Runtime type check: verify the actual parent matches @ParentEntity declaration
        ParentEntity parentAnn = child.getClass().getAnnotation(ParentEntity.class);
        if (!parentAnn.value().isAssignableFrom(parent.getClass())) {
            throw new IllegalArgumentException(
                    String.format("Parent type mismatch for %s: expected %s, got %s",
                            child.getClass().getSimpleName(),
                            parentAnn.value().getSimpleName(),
                            parent.getClass().getSimpleName()));
        }

        for (FieldMapping m : mappings) {
            try {
                m.childField.set(child, m.parentField.get(parent));
            } catch (IllegalAccessException e) {
                throw new RuntimeException(
                        String.format("Failed to copy field %s -> %s",
                                m.parentField.getName(), m.childField.getName()), e);
            }
        }
    }

    private static List<Field> getAllFields(Class<?> cls) {
        List<Field> fields = new ArrayList<>();
        Class<?> current = cls;
        while (current != null && current != Object.class) {
            fields.addAll(Arrays.asList(current.getDeclaredFields()));
            current = current.getSuperclass();
        }
        return fields;
    }

    private static Field findField(Class<?> cls, String name) {
        Class<?> current = cls;
        while (current != null && current != Object.class) {
            try {
                return current.getDeclaredField(name);
            } catch (NoSuchFieldException e) {
                current = current.getSuperclass();
            }
        }
        return null;
    }

    @AllArgsConstructor
    private static class FieldMapping {
        final Field parentField;
        final Field childField;
    }
}
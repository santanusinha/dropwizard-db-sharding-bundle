package io.appform.dropwizard.sharding.utils;

import io.appform.dropwizard.sharding.sharding.CopyFromParent;
import io.appform.dropwizard.sharding.sharding.ParentEntity;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Copies fields annotated with {@link CopyFromParent} from a parent entity to a child entity
 * using cached {@link MethodHandle}s for near-direct-access performance.
 * <p>
 * On first invocation for a given child class, this utility:
 * <ol>
 *   <li>Scans the child class for {@link ParentEntity} and {@link CopyFromParent} annotations</li>
 *   <li>Resolves the corresponding fields on the parent class</li>
 *   <li>Builds {@link MethodHandle} pairs (getter for parent, setter for child)</li>
 *   <li>Caches the result for all subsequent invocations (once per class per JVM lifetime)</li>
 * </ol>
 * <p>
 * This is a no-op if the child class has no {@link ParentEntity} annotation or no
 * {@link CopyFromParent} fields.
 */
@Slf4j
public final class CopyFromParentUtils {

    private static final Map<Class<?>, List<FieldHandleMapping>> CACHE = new ConcurrentHashMap<>();
    private static final List<FieldHandleMapping> NO_MAPPINGS = Collections.emptyList();

    private static final Map<Class<?>, Object> PRIMITIVE_DEFAULTS = Map.of(
            boolean.class, false,
            char.class, '\0',
            byte.class, (byte) 0,
            short.class, (short) 0,
            int.class, 0,
            long.class, 0L,
            float.class, 0.0f,
            double.class, 0.0d
    );

    private CopyFromParentUtils() {
        // utility class
    }

    /**
     * Copies annotated fields from parent to child.
     * Only copies fields where {@code @CopyFromParent(override = true)}.
     * No-op if child class has no {@code @ParentEntity} or no {@code @CopyFromParent} fields.
     *
     * @param parent the parent entity (source of field values)
     * @param child  the child entity (target)
     * @param copyIfDefaultOnly if true, only copy if child field has default value, otherwise log error
     * @throws IllegalArgumentException if parent type doesn't match {@code @ParentEntity} declaration
     * @throws RuntimeException         if field access fails
     */
    public static <T, U> void copyFields(T parent, U child, boolean copyIfDefaultOnly) {
        if (parent == null || child == null) {
            return;
        }

        List<FieldHandleMapping> mappings = CACHE.computeIfAbsent(
                child.getClass(), CopyFromParentUtils::buildMappings);

        if (mappings == NO_MAPPINGS) {
            return;
        }

        validateParentType(parent, child);
        applyMappings(parent, child, mappings, copyIfDefaultOnly);
    }

    /**
     * Detects all fields where the parent value differs from the child value,
     * regardless of {@code override} flag or whether the child value is a default.
     * <p>
     * This is used for mismatch detection during rollout — to verify that existing
     * manual setter logic produces the same values that the parent holds.
     *
     * @param parent the parent entity
     * @param child  the child entity
     * @return list of field mismatches (never null)
     */
    public static <T, U> List<FieldMismatch> detectMismatches(T parent, U child) {
        if (parent == null || child == null) {
            return Collections.emptyList();
        }

        List<FieldHandleMapping> mappings = CACHE.computeIfAbsent(
                child.getClass(), CopyFromParentUtils::buildMappings);

        if (mappings == NO_MAPPINGS) {
            return Collections.emptyList();
        }

        validateParentType(parent, child);
        return findMismatches(parent, child, mappings);
    }

    private static List<FieldHandleMapping> buildMappings(Class<?> childClass) {
        ParentEntity parentAnn = findParentEntityAnnotation(childClass);
        if (parentAnn == null) {
            return NO_MAPPINGS;
        }

        Class<?> parentClass = parentAnn.value();
        List<FieldHandleMapping> result = new ArrayList<>();

        for (Field childField : getAllFields(childClass)) {
            CopyFromParent copyAnn = childField.getAnnotation(CopyFromParent.class);
            if (copyAnn == null) {
                continue;
            }

            String parentFieldName = copyAnn.field();
            Field parentField = findField(parentClass, parentFieldName);
            if (parentField == null) {
                // This should never happen if compile-time processor ran.
                // Runtime safety net.
                throw new IllegalStateException(
                        String.format("@CopyFromParent(field=\"%s\") on %s.%s: "
                                        + "field not found on parent %s",
                                parentFieldName, childClass.getSimpleName(),
                                childField.getName(), parentClass.getSimpleName()));
            }

            try {
                MethodHandles.Lookup parentLookup = MethodHandles.privateLookupIn(
                        parentField.getDeclaringClass(), MethodHandles.lookup());
                MethodHandles.Lookup childLookup = MethodHandles.privateLookupIn(
                        childField.getDeclaringClass(), MethodHandles.lookup());

                MethodHandle parentGetter = parentLookup.unreflectGetter(parentField);
                MethodHandle childSetter = childLookup.unreflectSetter(childField);
                MethodHandle childGetter = childLookup.unreflectGetter(childField);

                result.add(new FieldHandleMapping(
                        parentGetter, childSetter, childGetter,
                        parentFieldName, childField.getName(),
                        childField.getType(),
                        copyAnn.override()));
            } catch (IllegalAccessException e) {
                throw new IllegalStateException(
                        String.format("Cannot create MethodHandle for field mapping %s.%s -> %s.%s",
                                parentClass.getSimpleName(), parentFieldName,
                                childClass.getSimpleName(), childField.getName()), e);
            }
        }

        return result.isEmpty() ? NO_MAPPINGS : Collections.unmodifiableList(result);
    }

    private static <T, U> void validateParentType(T parent, U child) {
        ParentEntity parentAnn = findParentEntityAnnotation(child.getClass());
        if (!parentAnn.value().isAssignableFrom(parent.getClass())) {
            throw new IllegalArgumentException(
                    String.format("Parent type mismatch for %s: expected %s, got %s",
                            child.getClass().getSimpleName(),
                            parentAnn.value().getSimpleName(),
                            parent.getClass().getSimpleName()));
        }
    }

    private static <T, U> void applyMappings(T parent, U child, List<FieldHandleMapping> mappings, 
                                              boolean copyIfDefaultOnly) {
        for (FieldHandleMapping m : mappings) {
            if (!m.override) {
                continue;
            }
            try {
                Object parentValue = m.parentGetter.invoke(parent);
                
                if (copyIfDefaultOnly) {
                    // Check if child has default value
                    Object childValue = m.childGetter.invoke(child);
                    
                    if (!isDefaultValue(childValue, m.childFieldType)) {
                        // Child has non-default value - manual setter detected, fail the operation
                        throw new IllegalStateException(
                                String.format("COPY_IF_DEFAULT_VIOLATION: Field %s.%s has non-default value [%s]. "
                                        + "Expected default value. Manual setter may still be present. "
                                        + "Parent value: [%s].",
                                        child.getClass().getSimpleName(), m.childFieldName,
                                        childValue, parentValue));
                    }
                }
                
                // Copy from parent to child
                m.childSetter.invoke(child, parentValue);
            } catch (IllegalStateException e) {
                throw e;
            } catch (Throwable e) {
                throw new RuntimeException(
                        String.format("Failed to copy field %s -> %s on %s",
                                m.parentFieldName, m.childFieldName,
                                child.getClass().getSimpleName()), e);
            }
        }
    }

    /**
     * Checks whether a value is the default for its type.
     * For reference types: {@code null}.
     * For primitives: exact comparison against the boxed default ({@code 0}, {@code false}, {@code '\0'}, etc.).
     */
    private static boolean isDefaultValue(Object value, Class<?> fieldType) {
        if (value == null) {
            return !fieldType.isPrimitive();
        }
        if (!fieldType.isPrimitive()) {
            return false;
        }
        return Objects.equals(value, PRIMITIVE_DEFAULTS.get(fieldType));
    }

    private static <T, U> List<FieldMismatch> findMismatches(
            T parent, U child, List<FieldHandleMapping> mappings) {
        List<FieldMismatch> mismatches = new ArrayList<>();
        for (FieldHandleMapping m : mappings) {
            try {
                Object parentValue = m.parentGetter.invoke(parent);
                Object childValue = m.childGetter.invoke(child);
                if (!Objects.equals(parentValue, childValue)) {
                    mismatches.add(new FieldMismatch(
                            m.parentFieldName, m.childFieldName, parentValue, childValue));
                }
            } catch (Throwable e) {
                log.error("Failed to detect mismatch for field {} -> {} on {}",
                        m.parentFieldName, m.childFieldName,
                        child.getClass().getSimpleName(), e);
            }
        }
        return mismatches;
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

    private static ParentEntity findParentEntityAnnotation(Class<?> cls) {
        Class<?> current = cls;
        while (current != null && current != Object.class) {
            ParentEntity ann = current.getAnnotation(ParentEntity.class);
            if (ann != null) {
                return ann;
            }
            current = current.getSuperclass();
        }
        return null;
    }

    /**
     * Represents a mismatch between a parent field value and the corresponding child field value.
     */
    @Getter
    @AllArgsConstructor
    public static class FieldMismatch {
        private final String parentFieldName;
        private final String childFieldName;
        private final Object parentValue;
        private final Object childValue;
    }

    @AllArgsConstructor
    private static class FieldHandleMapping {
        final MethodHandle parentGetter;
        final MethodHandle childSetter;
        final MethodHandle childGetter;
        final String parentFieldName;
        final String childFieldName;
        final Class<?> childFieldType;
        final boolean override;
    }
}
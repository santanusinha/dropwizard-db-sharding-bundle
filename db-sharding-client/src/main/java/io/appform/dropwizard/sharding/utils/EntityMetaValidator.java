package io.appform.dropwizard.sharding.utils;

import com.google.common.base.Preconditions;
import io.appform.dropwizard.sharding.sharding.BucketKey;
import io.appform.dropwizard.sharding.sharding.EntityMeta;
import io.appform.dropwizard.sharding.sharding.LookupKey;
import io.appform.dropwizard.sharding.sharding.ShardingKey;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.ClassUtils;

import java.lang.annotation.Annotation;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Validates entity annotations ({@link BucketKey}, {@link LookupKey}, {@link ShardingKey})
 * and builds {@link EntityMeta} instances with MethodHandle-based getters/setters.
 * <p>
 * Extracted from BundleCommonBase to be usable without Dropwizard.
 */
@Slf4j
@UtilityClass
public class EntityMetaValidator {

    /**
     * Scans all entity classes for sharding annotations and returns a map of
     * entity class name → {@link EntityMeta}.
     *
     * @param entities collection of JPA entity classes to validate
     * @return map of fully-qualified class name → EntityMeta (only for entities with {@link BucketKey})
     */
    public Map<String, EntityMeta> validateAndBuildEntitiesMeta(final Collection<Class<?>> entities) {
        final Map<String, EntityMeta> entitiesMeta = new HashMap<>();
        entities.forEach(clazz -> {
            try {
                final var bucketKeyFieldEntry = fetchAndValidateAnnotatedField(clazz, BucketKey.class, Integer.class);
                if (bucketKeyFieldEntry.isEmpty()) {
                    return;
                }
                final var lookupKeyFieldEntry = fetchAndValidateAnnotatedField(clazz, LookupKey.class, String.class);
                final var shardingKeyFieldEntry = fetchAndValidateAnnotatedField(clazz, ShardingKey.class, String.class);
                final var shardingKeyField = shardingKeyFieldEntry.map(Map.Entry::getKey);
                final var lookupKeyField = lookupKeyFieldEntry.map(Map.Entry::getKey);

                if (shardingKeyField.isEmpty() && lookupKeyField.isEmpty()) {
                    throw new RuntimeException(String.format("Entity %s: ShardingKey or LookupKey must be present if BucketKey "
                            + "is present", clazz.getName()));
                }

                if (shardingKeyField.isPresent() && lookupKeyField.isPresent()) {
                    throw new RuntimeException(String.format("Entity %s: Both ShardingKey and LookupKey cannot be present at the "
                            + "same time", clazz.getName()));
                }

                final var bucketKeyFieldDeclaringClassLookup =
                        MethodHandles.privateLookupIn(bucketKeyFieldEntry.get().getValue(), MethodHandles.lookup());
                final var bucketKeyField = bucketKeyFieldEntry.get().getKey();
                final var bucketKeySetter = bucketKeyFieldDeclaringClassLookup.unreflectSetter(bucketKeyField);

                MethodHandle shardingKeyGetter;
                if (shardingKeyField.isPresent()) {
                    final var shardingKeyFieldDeclaringClassLookup =
                            MethodHandles.privateLookupIn(shardingKeyFieldEntry.get().getValue(), MethodHandles.lookup());
                    shardingKeyGetter = shardingKeyFieldDeclaringClassLookup.unreflectGetter(shardingKeyField.get());
                } else {
                    final var lookupKeyFieldDeclaringClassLookup =
                            MethodHandles.privateLookupIn(lookupKeyFieldEntry.get().getValue(), MethodHandles.lookup());
                    shardingKeyGetter = lookupKeyFieldDeclaringClassLookup.unreflectGetter(lookupKeyField.get());
                }

                final var entityMeta = EntityMeta.builder()
                        .bucketKeySetter(bucketKeySetter)
                        .shardingKeyGetter(shardingKeyGetter)
                        .build();
                entitiesMeta.put(clazz.getName(), entityMeta);

            } catch (Exception e) {
                log.error("Error validating/resolving entity meta for class: {}", clazz.getName(), e);
                throw new RuntimeException("Failed to validate/resolve entity meta for " + clazz.getName(), e);
            }
        });
        return entitiesMeta;
    }

    private <K> Optional<Map.Entry<Field, Class<?>>> fetchAndValidateAnnotatedField(
            final Class<K> clazz,
            final Class<? extends Annotation> annotationClazz,
            final Class<?> acceptableClass)
            throws IllegalAccessException {

        final List<Map.Entry<Field, Class<?>>> annotatedFieldEntries = new ArrayList<>();
        Class<?> currentClass = clazz;
        while (currentClass != null && currentClass != Object.class) {
            final var currentClassLookup = MethodHandles.privateLookupIn(currentClass, MethodHandles.lookup());
            for (Field field : currentClassLookup.lookupClass().getDeclaredFields()) {
                if (field.isAnnotationPresent(annotationClazz)) {
                    annotatedFieldEntries.add(Map.entry(field, currentClass));
                }
            }
            currentClass = currentClass.getSuperclass();
        }
        Preconditions.checkArgument(annotatedFieldEntries.size() <= 1,
                String.format("Only one field can be designated with @%s in class %s or its superclasses",
                        annotationClazz.getSimpleName(), clazz.getName()));
        if (annotatedFieldEntries.isEmpty()) {
            return Optional.empty();
        }
        return annotatedFieldEntries.stream()
                .findFirst()
                .map(entry -> {
                    validateField(entry.getKey(), annotationClazz.getSimpleName(), acceptableClass);
                    return entry;
                });
    }

    private void validateField(final Field field,
                               final String annotationName,
                               final Class<?> acceptableClass) {
        final var errorMessage = String.format("Field annotated with @%s (%s) must be of acceptable Type: %s, but found %s",
                annotationName, field.getName(), acceptableClass.getSimpleName(), field.getType().getSimpleName());
        Preconditions.checkArgument(ClassUtils.isAssignable(field.getType(), acceptableClass), errorMessage);
    }
}


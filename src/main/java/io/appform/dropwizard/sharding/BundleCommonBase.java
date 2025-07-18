package io.appform.dropwizard.sharding;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Maps;
import io.appform.dropwizard.sharding.config.ShardingBundleOptions;
import io.appform.dropwizard.sharding.filters.TransactionFilter;
import io.appform.dropwizard.sharding.listeners.TransactionListener;
import io.appform.dropwizard.sharding.observers.TransactionObserver;
import io.appform.dropwizard.sharding.sharding.BucketKey;
import io.appform.dropwizard.sharding.sharding.EntityMeta;
import io.appform.dropwizard.sharding.sharding.LookupKey;
import io.appform.dropwizard.sharding.sharding.NoopShardBlacklistingStore;
import io.appform.dropwizard.sharding.sharding.ShardBlacklistingStore;
import io.appform.dropwizard.sharding.sharding.ShardingKey;
import io.dropwizard.Configuration;
import io.dropwizard.ConfiguredBundle;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.ClassUtils;
import org.jasypt.encryption.pbe.StandardPBEBigDecimalEncryptor;
import org.jasypt.encryption.pbe.StandardPBEBigIntegerEncryptor;
import org.jasypt.encryption.pbe.StandardPBEByteEncryptor;
import org.jasypt.encryption.pbe.StandardPBEStringEncryptor;
import org.jasypt.hibernate5.encryptor.HibernatePBEEncryptorRegistry;
import org.jasypt.iv.StringFixedIvGenerator;
import org.reflections.Reflections;

import javax.persistence.Entity;
import java.lang.annotation.Annotation;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

@Slf4j
public abstract class BundleCommonBase<T extends Configuration> implements ConfiguredBundle<T> {

  protected final List<TransactionListener> listeners = new ArrayList<>();
  protected final List<TransactionFilter> filters = new ArrayList<>();

  protected final List<TransactionObserver> observers = new ArrayList<>();

  protected final List<Class<?>> initialisedEntities;

  protected final Map<String, EntityMeta> initialisedEntitiesMeta = Maps.newHashMap();

  protected TransactionObserver rootObserver;

  protected BundleCommonBase(Class<?> entity, Class<?>... entities) {
    this.initialisedEntities = ImmutableList.<Class<?>>builder().add(entity).add(entities).build();
    validateAndBuildEntitiesMeta(initialisedEntities);
  }

  protected BundleCommonBase(List<String> classPathPrefixList) {
    Set<Class<?>> entities = new Reflections(classPathPrefixList).getTypesAnnotatedWith(
        Entity.class);
    Preconditions.checkArgument(!entities.isEmpty(),
        String.format("No entity class found at %s",
            String.join(",", classPathPrefixList)));
    this.initialisedEntities = ImmutableList.<Class<?>>builder().addAll(entities).build();
    validateAndBuildEntitiesMeta(initialisedEntities);
  }

  protected ShardBlacklistingStore getBlacklistingStore() {
    return new NoopShardBlacklistingStore();
  }

  public List<Class<?>> getInitialisedEntities() {
    if (this.initialisedEntities == null) {
      throw new IllegalStateException("DB sharding bundle is not initialised !");
    }
    return this.initialisedEntities;
  }

  public final void registerObserver(final TransactionObserver observer) {
    if (null == observer) {
      return;
    }
    this.observers.add(observer);
    log.info("Registered observer: {}", observer.getClass().getSimpleName());
  }

  public final void registerListener(final TransactionListener listener) {
    if (null == listener) {
      return;
    }
    this.listeners.add(listener);
    log.info("Registered listener: {}", listener.getClass().getSimpleName());
  }

  public final void registerFilter(final TransactionFilter filter) {
    if (null == filter) {
      return;
    }
    this.filters.add(filter);
    log.info("Registered filter: {}", filter.getClass().getSimpleName());
  }

  protected void registerStringEncryptor(String tenantId, ShardingBundleOptions shardingOption) {
    StandardPBEStringEncryptor strongEncryptor = new StandardPBEStringEncryptor();
    HibernatePBEEncryptorRegistry encryptorRegistry = HibernatePBEEncryptorRegistry.getInstance();
    strongEncryptor.setAlgorithm(shardingOption.getEncryptionAlgorithm());
    strongEncryptor.setPassword(shardingOption.getEncryptionPassword());
    strongEncryptor.setIvGenerator(
        new StringFixedIvGenerator(shardingOption.getEncryptionIv()));
    if (Objects.nonNull(tenantId)) {
      encryptorRegistry.registerPBEStringEncryptor(tenantId, "encryptedString", strongEncryptor);
      encryptorRegistry.registerPBEStringEncryptor(tenantId, "encryptedCalendarAsString",
          strongEncryptor);
    } else {
      encryptorRegistry.registerPBEStringEncryptor("encryptedString", strongEncryptor);
      encryptorRegistry.registerPBEStringEncryptor("encryptedCalendarAsString", strongEncryptor);
    }
  }

  protected void registerBigIntegerEncryptor(String tenantId,
      ShardingBundleOptions shardingOption) {
    StandardPBEBigIntegerEncryptor strongEncryptor = new StandardPBEBigIntegerEncryptor();
    HibernatePBEEncryptorRegistry encryptorRegistry = HibernatePBEEncryptorRegistry.getInstance();
    strongEncryptor.setAlgorithm(shardingOption.getEncryptionAlgorithm());
    strongEncryptor.setPassword(shardingOption.getEncryptionPassword());
    strongEncryptor.setIvGenerator(
        new StringFixedIvGenerator(shardingOption.getEncryptionIv()));
    if (Objects.nonNull(tenantId)) {
      encryptorRegistry.registerPBEBigIntegerEncryptor(tenantId, "encryptedBigInteger",
          strongEncryptor);
    } else {
      encryptorRegistry.registerPBEBigIntegerEncryptor("encryptedBigInteger",
          strongEncryptor);
    }
  }

  protected void registerBigDecimalEncryptor(String tenantId,
      ShardingBundleOptions shardingOption) {
    StandardPBEBigDecimalEncryptor strongEncryptor = new StandardPBEBigDecimalEncryptor();
    HibernatePBEEncryptorRegistry encryptorRegistry = HibernatePBEEncryptorRegistry.getInstance();
    strongEncryptor.setAlgorithm(shardingOption.getEncryptionAlgorithm());
    strongEncryptor.setPassword(shardingOption.getEncryptionPassword());
    strongEncryptor.setIvGenerator(
        new StringFixedIvGenerator(shardingOption.getEncryptionIv()));
    if (Objects.nonNull(tenantId)) {
      encryptorRegistry.registerPBEBigDecimalEncryptor(tenantId, "encryptedBigDecimal",
          strongEncryptor);
    } else {
      encryptorRegistry.registerPBEBigDecimalEncryptor("encryptedBigDecimal",
          strongEncryptor);
    }
  }

  protected void registerByteEncryptor(String tenantId, ShardingBundleOptions shardingOption) {
    StandardPBEByteEncryptor strongEncryptor = new StandardPBEByteEncryptor();
    HibernatePBEEncryptorRegistry encryptorRegistry = HibernatePBEEncryptorRegistry.getInstance();
    strongEncryptor.setAlgorithm(shardingOption.getEncryptionAlgorithm());
    strongEncryptor.setPassword(shardingOption.getEncryptionPassword());
    strongEncryptor.setIvGenerator(
        new StringFixedIvGenerator(shardingOption.getEncryptionIv()));
    if (Objects.nonNull(tenantId)) {
      encryptorRegistry.registerPBEByteEncryptor(tenantId, "encryptedBinary", strongEncryptor);
    } else {
      encryptorRegistry.registerPBEByteEncryptor("encryptedBinary", strongEncryptor);
    }
  }

  private void validateAndBuildEntitiesMeta(final List<Class<?>> initialisedEntities) {
    initialisedEntities.forEach(clazz -> {
      try {
        final var bucketKeyFieldEntry = fetchAndValidateAnnotateField(clazz, BucketKey.class, Integer.class);
        if (bucketKeyFieldEntry.isEmpty()) {
          return;
        }
        final var lookupKeyFieldEntry = fetchAndValidateAnnotateField(clazz, LookupKey.class, String.class);
        final var shardingKeyFieldEntry = fetchAndValidateAnnotateField(clazz, ShardingKey.class, String.class);
        final var shardingKeyField = shardingKeyFieldEntry.map(Map.Entry::getKey);
        final var lookupKeyField = lookupKeyFieldEntry.map(Map.Entry::getKey);

        if (shardingKeyField.isEmpty() && lookupKeyField.isEmpty()) {
          throw new RuntimeException(String.format("Entity %s: ShardingKey or LookupKey must be present if BucketKey " +
                  "is present", clazz.getName()));
        }

        if (shardingKeyField.isPresent() && lookupKeyField.isPresent()) {
          throw new RuntimeException(String.format("Entity %s: Both ShardingKey and LookupKey cannot be present at the " +
                  "same time", clazz.getName()));
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
        initialisedEntitiesMeta.put(clazz.getName(), entityMeta);

      } catch (Exception e) {
        log.error("Error validating/resolving entity meta for class: {}", clazz.getName(), e);
        throw new RuntimeException("Failed to validate/resolve entity meta for " + clazz.getName(), e);
      }
    });
  }

  private <K> Optional<Map.Entry<Field, Class<?>>> fetchAndValidateAnnotateField(
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

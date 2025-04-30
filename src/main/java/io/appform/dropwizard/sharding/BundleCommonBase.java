package io.appform.dropwizard.sharding;

import com.google.common.base.Function;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Maps;
import io.appform.dropwizard.sharding.config.ShardingBundleOptions;
import io.appform.dropwizard.sharding.filters.TransactionFilter;
import io.appform.dropwizard.sharding.listeners.TransactionListener;
import io.appform.dropwizard.sharding.observers.TransactionObserver;
import io.appform.dropwizard.sharding.sharding.BucketKey;
import io.appform.dropwizard.sharding.sharding.EntityMeta;
import io.appform.dropwizard.sharding.sharding.InMemoryLocalShardBlacklistingStore;
import io.appform.dropwizard.sharding.sharding.LookupKey;
import io.appform.dropwizard.sharding.sharding.ShardBlacklistingStore;
import io.appform.dropwizard.sharding.sharding.ShardingKey;
import io.dropwizard.Configuration;
import io.dropwizard.ConfiguredBundle;
import lombok.extern.slf4j.Slf4j;
import lombok.val;
import org.apache.commons.lang3.ClassUtils;
import org.apache.commons.lang3.reflect.FieldUtils;
import org.jasypt.encryption.pbe.StandardPBEBigDecimalEncryptor;
import org.jasypt.encryption.pbe.StandardPBEBigIntegerEncryptor;
import org.jasypt.encryption.pbe.StandardPBEByteEncryptor;
import org.jasypt.encryption.pbe.StandardPBEStringEncryptor;
import org.jasypt.hibernate5.encryptor.HibernatePBEEncryptorRegistry;
import org.jasypt.iv.StringFixedIvGenerator;
import org.reflections.Reflections;

import javax.persistence.Entity;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
    return new InMemoryLocalShardBlacklistingStore();
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
      val bucketKeyField = resolveFieldFromEntity(clazz, BucketKey.class,
              entity -> validateAndResolveField(entity, BucketKey.class.getSimpleName(), Integer.class));
      if (Objects.isNull(bucketKeyField)) {
        return;
      }

      val lookupKeyField = resolveFieldFromEntity(clazz, LookupKey.class,
              entity -> validateAndResolveField(entity, LookupKey.class.getSimpleName(), String.class));
      val shardingKeyField = resolveFieldFromEntity(clazz, ShardingKey.class,
              entity -> validateAndResolveField(entity, ShardingKey.class.getSimpleName(), String.class));
      if (Objects.isNull(shardingKeyField) && Objects.isNull(lookupKeyField) ) {
        throw new RuntimeException("ShardingKey or LookupKey must be present if bucketKey is present");
      }

      val entityMeta = EntityMeta.builder()
              .bucketKeyField(bucketKeyField)
              .shardingKeyField(Objects.isNull(shardingKeyField) ? lookupKeyField : shardingKeyField)
              .build();
      initialisedEntitiesMeta.put(clazz.getName(), entityMeta);
    });
  }

  private Field validateAndResolveField(final Field[] fields,
                                        final String fieldType,
                                        final Class<?> acceptableClass) {
    if(fields.length == 0) {
      return null;
    }
    Preconditions.checkArgument(fields.length == 1, String.format("Only one field can be designated " +
            "as @%s", fieldType));
    val keyField = fields[0];
    Preconditions.checkArgument(ClassUtils.isAssignable(keyField.getType(), acceptableClass),
            String.format("Key field must be of acceptable Type: %s", acceptableClass));
    return keyField;
  }

  private Field resolveFieldFromEntity(Class<?> clazz, Class<? extends Annotation> annotationClazz,
                                       Function<Field[], Field> validateAndResolve) {
    val keyFields = FieldUtils.getFieldsWithAnnotation(clazz, annotationClazz);
    return validateAndResolve.apply(keyFields);
  }

}

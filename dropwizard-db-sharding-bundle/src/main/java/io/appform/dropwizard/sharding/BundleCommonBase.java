package io.appform.dropwizard.sharding;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import io.appform.dropwizard.sharding.config.ShardingBundleOptions;
import io.appform.dropwizard.sharding.filters.TransactionFilter;
import io.appform.dropwizard.sharding.listeners.TransactionListener;
import io.appform.dropwizard.sharding.observers.TransactionObserver;
import io.appform.dropwizard.sharding.sharding.EntityMeta;
import io.appform.dropwizard.sharding.sharding.NoopShardBlacklistingStore;
import io.appform.dropwizard.sharding.sharding.ShardBlacklistingStore;
import io.appform.dropwizard.sharding.utils.EntityMetaValidator;
import io.dropwizard.Configuration;
import io.dropwizard.ConfiguredBundle;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import javax.persistence.Entity;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.jasypt.encryption.pbe.StandardPBEBigDecimalEncryptor;
import org.jasypt.encryption.pbe.StandardPBEBigIntegerEncryptor;
import org.jasypt.encryption.pbe.StandardPBEByteEncryptor;
import org.jasypt.encryption.pbe.StandardPBEStringEncryptor;
import org.jasypt.hibernate5.encryptor.HibernatePBEEncryptorRegistry;
import org.jasypt.iv.StringFixedIvGenerator;
import org.reflections.Reflections;

@Slf4j
public abstract class BundleCommonBase<T extends Configuration> implements ConfiguredBundle<T> {

  protected final List<TransactionListener> listeners = new ArrayList<>();
  protected final List<TransactionFilter> filters = new ArrayList<>();
  protected final List<TransactionObserver> observers = new ArrayList<>();

  protected final List<Class<?>> initialisedEntities = new ArrayList<>();
  protected final Map<String, EntityMeta> initialisedEntitiesMeta = new HashMap<>();

  private volatile boolean bundleInitialised = false;

  protected TransactionObserver rootObserver;

  protected BundleCommonBase(final Class<?> entity, final Class<?>... entities) {
    initialiseEntities(ImmutableList.<Class<?>>builder()
            .add(entity).add(entities).build());
  }

  protected BundleCommonBase(final List<String> classPathPrefixList) {
    final var entities = new Reflections(classPathPrefixList).getTypesAnnotatedWith(Entity.class);
    Preconditions.checkArgument(!entities.isEmpty(),
            String.format("No entity class found at %s", String.join(",", classPathPrefixList)));
    initialiseEntities(entities);
  }

  public final void registerEntities(@NonNull final Class<?>... entities) {
      initialiseEntities(Arrays.asList(entities));
  }

  public final void registerEntities(@NonNull final List<String> classPathPrefixList) {
      final var entities = new Reflections(classPathPrefixList).getTypesAnnotatedWith(Entity.class);
      Preconditions.checkArgument(!entities.isEmpty(),
              String.format("No entity class found at %s", String.join(",", classPathPrefixList)));
      initialiseEntities(entities);
  }

  private synchronized void initialiseEntities(final Collection<Class<?>> entities) {
    if (this.bundleInitialised) {
      throw new UnsupportedOperationException("Entity registration is not supported after run method execution.");
    }
    if (this.initialisedEntities.isEmpty()) {
      this.initialisedEntities.addAll(entities);
      return;
    }
    final var pendingInitialisationEntities = entities.stream()
            .filter(entity -> !this.initialisedEntities.contains(entity))
            .collect(Collectors.toList());
    this.initialisedEntities.addAll(pendingInitialisationEntities);
  }

  protected final synchronized void completeBundleInitialization() {
    this.initialisedEntitiesMeta.putAll(
            EntityMetaValidator.validateAndBuildEntitiesMeta(this.initialisedEntities));
    this.bundleInitialised = true;
  }

  protected ShardBlacklistingStore getBlacklistingStore() {
    return new NoopShardBlacklistingStore();
  }

  public List<Class<?>> getInitialisedEntities() {
    if (!this.bundleInitialised) {
      throw new IllegalStateException("DB sharding bundle is not initialised !");
    }
    return Collections.unmodifiableList(this.initialisedEntities);
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
}

package io.appform.dropwizard.sharding;

import com.codahale.metrics.MetricRegistry;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import io.appform.dropwizard.sharding.config.MetricConfig;
import io.appform.dropwizard.sharding.config.ShardingBundleOptions;
import io.appform.dropwizard.sharding.filters.TransactionFilter;
import io.appform.dropwizard.sharding.listeners.TransactionListener;
import io.appform.dropwizard.sharding.metrics.TransactionMetricManager;
import io.appform.dropwizard.sharding.metrics.TransactionMetricObserver;
import io.appform.dropwizard.sharding.observers.TransactionObserver;
import io.appform.dropwizard.sharding.observers.internal.FilteringObserver;
import io.appform.dropwizard.sharding.observers.internal.ListenerTriggeringObserver;
import io.appform.dropwizard.sharding.observers.internal.TerminalTransactionObserver;
import io.appform.dropwizard.sharding.sharding.InMemoryLocalShardBlacklistingStore;
import io.appform.dropwizard.sharding.sharding.ShardBlacklistingStore;
import io.dropwizard.Configuration;
import io.dropwizard.ConfiguredBundle;
import lombok.extern.slf4j.Slf4j;
import org.jasypt.encryption.pbe.StandardPBEBigDecimalEncryptor;
import org.jasypt.encryption.pbe.StandardPBEBigIntegerEncryptor;
import org.jasypt.encryption.pbe.StandardPBEByteEncryptor;
import org.jasypt.encryption.pbe.StandardPBEStringEncryptor;
import org.jasypt.hibernate5.encryptor.HibernatePBEEncryptorRegistry;
import org.jasypt.iv.StringFixedIvGenerator;
import org.reflections.Reflections;

import javax.persistence.Entity;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

@Slf4j
public abstract class BundleCommonBase<T extends Configuration> implements ConfiguredBundle<T> {

  protected final List<TransactionListener> listeners = new ArrayList<>();
  protected final List<TransactionFilter> filters = new ArrayList<>();

  protected final List<TransactionObserver> observers = new ArrayList<>();

  protected final List<Class<?>> initialisedEntities;

  protected TransactionObserver rootObserver;

  protected BundleCommonBase(Class<?> entity, Class<?>... entities) {
    this.initialisedEntities = ImmutableList.<Class<?>>builder().add(entity).add(entities).build();
  }

  protected BundleCommonBase(List<String> classPathPrefixList) {
    Set<Class<?>> entities = new Reflections(classPathPrefixList).getTypesAnnotatedWith(
        Entity.class);
    Preconditions.checkArgument(!entities.isEmpty(),
        String.format("No entity class found at %s",
            String.join(",", classPathPrefixList)));
    this.initialisedEntities = ImmutableList.<Class<?>>builder().addAll(entities).build();
  }

  protected ShardBlacklistingStore getBlacklistingStore() {
    return new InMemoryLocalShardBlacklistingStore();
  }

  public void setupObservers(final MetricConfig metricConfig,
      final MetricRegistry metricRegistry) {
    //Observer chain starts with filters and ends with listener invocations
    //Terminal observer calls the actual method
    rootObserver = new ListenerTriggeringObserver(new TerminalTransactionObserver()).addListeners(
        listeners);
    for (var observer : observers) {
      if (null == observer) {
        return;
      }
      this.rootObserver = observer.setNext(rootObserver);
    }
    rootObserver = new TransactionMetricObserver(
        new TransactionMetricManager(() -> metricConfig,
            metricRegistry)).setNext(rootObserver);
    rootObserver = new FilteringObserver(rootObserver).addFilters(filters);
    //Print the observer chain
    log.debug("Observer chain");
    rootObserver.visit(observer -> {
      log.debug(" Observer: {}", observer.getClass().getSimpleName());
      if (observer instanceof FilteringObserver) {
        log.debug("  Filters:");
        ((FilteringObserver) observer).getFilters()
            .forEach(filter -> log.debug("    - {}", filter.getClass().getSimpleName()));
      }
      if (observer instanceof ListenerTriggeringObserver) {
        log.debug("  Listeners:");
        ((ListenerTriggeringObserver) observer).getListeners()
            .forEach(filter -> log.debug("    - {}", filter.getClass().getSimpleName()));
      }
    });
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
}

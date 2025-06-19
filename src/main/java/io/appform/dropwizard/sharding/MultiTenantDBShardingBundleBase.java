/*
 * Copyright 2019 Santanu Sinha <santanu.sinha@gmail.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package io.appform.dropwizard.sharding;

import com.fasterxml.jackson.datatype.hibernate5.Hibernate5Module;
import com.fasterxml.jackson.datatype.hibernate5.Hibernate5Module.Feature;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.Maps;
import io.appform.dropwizard.sharding.admin.BlacklistShardTask;
import io.appform.dropwizard.sharding.admin.UnblacklistShardTask;
import io.appform.dropwizard.sharding.caching.LookupCache;
import io.appform.dropwizard.sharding.caching.RelationalCache;
import io.appform.dropwizard.sharding.config.MetricConfig;
import io.appform.dropwizard.sharding.config.MultiTenantShardedHibernateFactory;
import io.appform.dropwizard.sharding.config.ShardingBundleOptions;
import io.appform.dropwizard.sharding.dao.AbstractDAO;
import io.appform.dropwizard.sharding.dao.MultiTenantCacheableLookupDao;
import io.appform.dropwizard.sharding.dao.MultiTenantCacheableRelationalDao;
import io.appform.dropwizard.sharding.dao.MultiTenantLookupDao;
import io.appform.dropwizard.sharding.dao.MultiTenantRelationalDao;
import io.appform.dropwizard.sharding.dao.WrapperDao;
import io.appform.dropwizard.sharding.healthcheck.HealthCheckManager;
import io.appform.dropwizard.sharding.hibernate.SessionFactoryFactory;
import io.appform.dropwizard.sharding.hibernate.SessionFactoryManager;
import io.appform.dropwizard.sharding.hibernate.SessionFactorySource;
import io.appform.dropwizard.sharding.sharding.ShardBlacklistingStore;
import io.appform.dropwizard.sharding.sharding.ShardManager;
import io.dropwizard.Configuration;
import io.dropwizard.db.PooledDataSourceFactory;
import io.dropwizard.setup.Bootstrap;
import io.dropwizard.setup.Environment;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import lombok.val;
import org.hibernate.SessionFactory;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Base for Multi-Tenant sharding bundles. Clients cannot use this. Use one of the derived classes.
 */
@Slf4j
public abstract class MultiTenantDBShardingBundleBase<T extends Configuration> extends
    BundleCommonBase<T> {

  @Getter
  private Map<String, List<SessionFactory>> sessionFactories = Maps.newHashMap();

  @Getter
  private Map<String, ShardManager> shardManagers = Maps.newHashMap();

  @Getter
  private Map<String, ShardingBundleOptions> shardingOptions = Maps.newHashMap();

  private Map<String, ShardInfoProvider> shardInfoProviders = Maps.newHashMap();

  private Map<String, HealthCheckManager> healthCheckManagers = Maps.newHashMap();

  protected MultiTenantDBShardingBundleBase(
      Class<?> entity,
      Class<?>... entities) {
    super(entity, entities);
  }

  protected MultiTenantDBShardingBundleBase(List<String> classPathPrefixList) {
    super(classPathPrefixList);
  }

  protected MultiTenantDBShardingBundleBase(String... classPathPrefixes) {
    this(Arrays.asList(classPathPrefixes));
  }

  protected abstract ShardManager createShardManager(int numShards,
      ShardBlacklistingStore blacklistingStore);

  @Override
  public void run(T configuration, Environment environment) {
    final var tenantedConfig = getConfig(configuration);
    tenantedConfig.getTenants().forEach((tenantId, shardConfig) -> {
      //Encryption Support through jasypt-hibernate5
      var shardingOption = shardConfig.getShardingOptions();
      shardingOption = Objects.nonNull(shardingOption) ? shardingOption : new ShardingBundleOptions();
      final int shardCount = shardConfig.getShards().size();
      final int shardInitializationParallelism = fetchParallelism(shardingOption);
      final var executorService = Executors.newFixedThreadPool(shardInitializationParallelism);
      try {
        final var blacklistingStore = getBlacklistingStore();
        final var shardManager = createShardManager(shardCount, blacklistingStore);
        this.shardManagers.put(tenantId, shardManager);
        final var shardInfoProvider = new ShardInfoProvider(tenantId);
        this.shardInfoProviders.put(tenantId, shardInfoProvider);
        final var healthCheckManager = new HealthCheckManager(tenantId, environment, shardInfoProvider,
                blacklistingStore, shardingOption);
        healthCheckManagers.put(tenantId, healthCheckManager);
        final List<CompletableFuture<SessionFactorySource>> futures = IntStream.range(0, shardCount)
                .mapToObj(shard -> CompletableFuture.supplyAsync(() -> {
                  try {
                    return new SessionFactoryFactory<T>(initialisedEntities, healthCheckManager) {
                      @Override
                      protected String name() {
                        return shardInfoProvider.shardName(shard);
                      }

                      @Override
                      public PooledDataSourceFactory getDataSourceFactory(T t) {
                        return shardConfig.getShards().get(shard);
                      }
                    }.build(configuration, environment);
                  } catch (Exception e) {
                    log.error("Failed to build session factory for shard {}", shard, e);
                    throw new RuntimeException("Shard " + shard + " build failed", e);
                  }
                }, executorService))
                .collect(Collectors.toList());
        final var sessionFactorySources = getSessionFactorySources(tenantId, futures);
        final var sessionFactoryManager = new SessionFactoryManager(sessionFactorySources);
        environment.lifecycle().manage(sessionFactoryManager);
        val sessionFactory = sessionFactorySources
                .stream()
                .map(SessionFactorySource::getFactory)
                .collect(Collectors.toList());
        sessionFactory.forEach(factory -> factory.getProperties().put("tenant.id", tenantId));
        if (shardingOption.isEncryptionSupportEnabled()) {
          Preconditions.checkArgument(shardingOption.getEncryptionIv().length() == 16,
                  "Encryption IV Should be 16 bytes long");
          registerStringEncryptor(tenantId, shardingOption);
          registerBigIntegerEncryptor(tenantId, shardingOption);
          registerBigDecimalEncryptor(tenantId, shardingOption);
          registerByteEncryptor(tenantId, shardingOption);
        }
        this.sessionFactories.put(tenantId, sessionFactory);
        this.shardingOptions.put(tenantId, shardingOption);
        setupObservers(shardConfig.getMetricConfig(), environment.metrics());
        environment.admin().addTask(new BlacklistShardTask(tenantId, shardManager));
        environment.admin().addTask(new UnblacklistShardTask(tenantId, shardManager));
      } finally {
        executorService.shutdown();
        try {
          if (!executorService.awaitTermination(5, TimeUnit.SECONDS)) {
            executorService.shutdownNow();
          }
        } catch (InterruptedException e) {
          executorService.shutdownNow();
          Thread.currentThread().interrupt();
        }
      }
    });
  }

  @Override
  @SuppressWarnings("unchecked")
  public void initialize(Bootstrap<?> bootstrap) {
    // Registers the Hibernate5Module with Jackson's ObjectMapper to support serialization of Hibernate entities.
    // Enables FORCE_LAZY_LOADING to automatically fetch and serialize lazy-loaded associations (e.g., @OneToMany(fetch = LAZY))
    // during JSON serialization, as long as the Hibernate session is still open.
    bootstrap.getObjectMapper().registerModule(new Hibernate5Module().enable(Feature.FORCE_LAZY_LOADING));
  }

  @VisibleForTesting
  protected Map<String, Map<Integer, Boolean>> healthStatus() {
    return healthCheckManagers.entrySet().stream()
        .collect(Collectors.toMap(Map.Entry::getKey, entry -> entry.getValue().status()));
  }

  protected abstract MultiTenantShardedHibernateFactory getConfig(T config);

  protected Supplier<MetricConfig> getMetricConfig(String tenantId, T config) {
    return () -> getConfig(config).getTenants().get(tenantId).getMetricConfig();
  }

  public <EntityType, T extends Configuration>
  MultiTenantLookupDao<EntityType> createParentObjectDao(Class<EntityType> clazz) {
    return new MultiTenantLookupDao<>(this.sessionFactories, clazz,
        this.shardManagers,
        this.shardingOptions,
        shardInfoProviders,
        rootObserver);
  }

  public <EntityType, T extends Configuration>
  MultiTenantCacheableLookupDao<EntityType> createParentObjectDao(Class<EntityType> clazz,
      Map<String, LookupCache<EntityType>> cacheManager) {
    return new MultiTenantCacheableLookupDao<>(this.sessionFactories,
        clazz, this.shardManagers,
        cacheManager,
        this.shardingOptions,
        shardInfoProviders,
        rootObserver);
  }

  public <EntityType, T extends Configuration>
  MultiTenantRelationalDao<EntityType> createRelatedObjectDao(Class<EntityType> clazz) {
    return new MultiTenantRelationalDao<>(this.sessionFactories, clazz,
        this.shardManagers,
        this.shardingOptions,
        shardInfoProviders,
        rootObserver);
  }


  public <EntityType, T extends Configuration>
  MultiTenantCacheableRelationalDao<EntityType> createRelatedObjectDao(Class<EntityType> clazz,
      Map<String, RelationalCache<EntityType>> cacheManager) {
    return new MultiTenantCacheableRelationalDao<>(this.sessionFactories,
        clazz,
        this.shardManagers,
        cacheManager,
        this.shardingOptions,
        shardInfoProviders,
        rootObserver);
  }

  public <EntityType, DaoType extends AbstractDAO<EntityType>, T extends Configuration>
  WrapperDao<EntityType, DaoType> createWrapperDao(String tenantId, Class<DaoType> daoTypeClass) {
    Preconditions.checkArgument(
            this.sessionFactories.containsKey(tenantId) && this.shardManagers.containsKey(tenantId),
            "Unknown tenant: " + tenantId);
    return new WrapperDao<>(tenantId, this.sessionFactories.get(tenantId), daoTypeClass, this.shardManagers.get(tenantId));
  }

  public <EntityType, DaoType extends AbstractDAO<EntityType>, T extends Configuration>
  WrapperDao<EntityType, DaoType> createWrapperDao(String tenantId,
      Class<DaoType> daoTypeClass,
      Class[] extraConstructorParamClasses,
      Class[] extraConstructorParamObjects) {
    Preconditions.checkArgument(
            this.sessionFactories.containsKey(tenantId) && this.shardManagers.containsKey(tenantId),
            "Unknown tenant: " + tenantId);
    return new WrapperDao<>(tenantId, this.sessionFactories.get(tenantId), daoTypeClass,
        extraConstructorParamClasses, extraConstructorParamObjects, this.shardManagers.get(tenantId));
  }

  private int fetchParallelism(final ShardingBundleOptions bundleOptions) {
    final var availableCpus = Runtime.getRuntime().availableProcessors();
    final var defaultParallelism = Math.max(1, availableCpus - 2);
    final var shardInitializationParallelism = bundleOptions.getShardInitializationParallelism();
    if (shardInitializationParallelism <= 0) {
      return defaultParallelism;
    }
    if (shardInitializationParallelism > defaultParallelism) {
      log.warn("A maximum of {} parallelism is allowed for initialization", defaultParallelism);
    }
    return Math.min(shardInitializationParallelism, defaultParallelism);
  }


  private List<SessionFactorySource> getSessionFactorySources(final String tenantId,
                                                              final List<CompletableFuture<SessionFactorySource>> futures) {
    final int TIMEOUT_SECONDS = 180;
    List<SessionFactorySource> sessionFactorySources;
    try {
      sessionFactorySources = CompletableFuture
              .allOf(futures.toArray(new CompletableFuture[0]))
              .thenApply(ignored ->
                      futures.stream()
                              .map(CompletableFuture::join)
                              .collect(Collectors.toList())
              )
              .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new RuntimeException("Initialization interrupted for tenant " + tenantId, e);
    } catch (ExecutionException e) {
      throw new RuntimeException("One or more session factories failed for tenant " + tenantId, e.getCause());
    } catch (TimeoutException e) {
      futures.forEach(f -> f.cancel(true));
      throw new RuntimeException("Timed out waiting " + TIMEOUT_SECONDS + "s for tenant " + tenantId, e);
    }
    return sessionFactorySources;
  }
}

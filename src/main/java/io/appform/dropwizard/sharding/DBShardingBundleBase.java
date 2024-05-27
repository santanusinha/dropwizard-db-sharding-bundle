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

import com.codahale.metrics.MetricRegistry;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import io.appform.dropwizard.sharding.admin.BlacklistShardTask;
import io.appform.dropwizard.sharding.admin.UnblacklistShardTask;
import io.appform.dropwizard.sharding.caching.LookupCache;
import io.appform.dropwizard.sharding.caching.RelationalCache;
import io.appform.dropwizard.sharding.config.MetricConfig;
import io.appform.dropwizard.sharding.config.ShardedHibernateFactory;
import io.appform.dropwizard.sharding.config.ShardingBundleOptions;
import io.appform.dropwizard.sharding.dao.CacheableLookupDao;
import io.appform.dropwizard.sharding.dao.CacheableRelationalDao;
import io.appform.dropwizard.sharding.dao.LookupDao;
import io.appform.dropwizard.sharding.dao.RelationalDao;
import io.appform.dropwizard.sharding.dao.WrapperDao;
import io.appform.dropwizard.sharding.filters.TransactionFilter;
import io.appform.dropwizard.sharding.healthcheck.HealthCheckManager;
import io.appform.dropwizard.sharding.listeners.TransactionListener;
import io.appform.dropwizard.sharding.metrics.TransactionMetricManager;
import io.appform.dropwizard.sharding.metrics.TransactionMetricObserver;
import io.appform.dropwizard.sharding.observers.TransactionObserver;
import io.appform.dropwizard.sharding.observers.internal.FilteringObserver;
import io.appform.dropwizard.sharding.observers.internal.ListenerTriggeringObserver;
import io.appform.dropwizard.sharding.observers.internal.TerminalTransactionObserver;
import io.appform.dropwizard.sharding.sharding.BucketIdExtractor;
import io.appform.dropwizard.sharding.sharding.InMemoryLocalShardBlacklistingStore;
import io.appform.dropwizard.sharding.sharding.ShardBlacklistingStore;
import io.appform.dropwizard.sharding.sharding.ShardManager;
import io.appform.dropwizard.sharding.sharding.impl.ConsistentHashBucketIdExtractor;
import io.appform.dropwizard.sharding.utils.BucketCalculator;
import io.appform.dropwizard.sharding.utils.ShardCalculator;
import io.dropwizard.Configuration;
import io.dropwizard.ConfiguredBundle;
import io.dropwizard.db.PooledDataSourceFactory;
import io.dropwizard.hibernate.AbstractDAO;
import io.dropwizard.hibernate.HibernateBundle;
import io.dropwizard.hibernate.SessionFactoryFactory;
import io.dropwizard.setup.Bootstrap;
import io.dropwizard.setup.Environment;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import lombok.val;
import org.apache.commons.lang3.StringUtils;
import org.hibernate.SessionFactory;
import org.reflections.Reflections;

import javax.persistence.Entity;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Base for bundles. This cannot be used by clients. Use one of the derived classes.
 */
@Slf4j
public abstract class DBShardingBundleBase<T extends Configuration> implements ConfiguredBundle<T> {

    private static final String DEFAULT_NAMESPACE = "default";
    private static final String SHARD_ENV = "db.shards";
    private static final String DEFAULT_SHARDS = "2";

    private List<HibernateBundle<T>> shardBundles = Lists.newArrayList();
    @Getter
    private List<SessionFactory> sessionFactories;
    @Getter
    private ShardManager shardManager;
    @Getter
    private String dbNamespace;
    @Getter
    private int numShards;
    @Getter
    private ShardingBundleOptions shardingOptions;

    private ShardInfoProvider shardInfoProvider;

    private HealthCheckManager healthCheckManager;

    private final List<TransactionListener> listeners = new ArrayList<>();
    private final List<TransactionFilter> filters = new ArrayList<>();

    private final List<TransactionObserver> observers = new ArrayList<>();

    private final List<Class<?>> initialisedEntities;

    private TransactionObserver rootObserver;

    protected DBShardingBundleBase(
            String dbNamespace,
            Class<?> entity,
            Class<?>... entities) {
        this.dbNamespace = dbNamespace;
        val inEntities = ImmutableList.<Class<?>>builder().add(entity).add(entities).build();
        this.initialisedEntities = inEntities;
        init(inEntities);
    }

    protected DBShardingBundleBase(String dbNamespace, List<String> classPathPrefixList) {
        this.dbNamespace = dbNamespace;
        Set<Class<?>> entities = new Reflections(classPathPrefixList).getTypesAnnotatedWith(Entity.class);
        Preconditions.checkArgument(!entities.isEmpty(),
                String.format("No entity class found at %s",
                        String.join(",", classPathPrefixList)));
        val inEntities = ImmutableList.<Class<?>>builder().addAll(entities).build();
        this.initialisedEntities = inEntities;
        init(inEntities);
    }

    protected DBShardingBundleBase(Class<?> entity, Class<?>... entities) {
        this(DEFAULT_NAMESPACE, entity, entities);
    }

    protected DBShardingBundleBase(String... classPathPrefixes) {
        this(DEFAULT_NAMESPACE, Arrays.asList(classPathPrefixes));
    }

    public List<Class<?>> getInitialisedEntities() {
        if(this.initialisedEntities == null){
            throw new RuntimeException("DB sharding bundle is not initialised !");
        }
        return this.initialisedEntities;
    }

    protected abstract ShardManager createShardManager(int numShards, ShardBlacklistingStore blacklistingStore);

    private void init(final ImmutableList<Class<?>> inEntities) {
        boolean defaultNamespace = StringUtils.equalsIgnoreCase(dbNamespace, DEFAULT_NAMESPACE);
        val numShardsProperty = defaultNamespace ? SHARD_ENV : String.join(".", dbNamespace, SHARD_ENV);
        String numShardsEnv = System.getProperty(numShardsProperty, DEFAULT_SHARDS);
        this.numShards = Integer.parseInt(numShardsEnv);
        val blacklistingStore = getBlacklistingStore();
        this.shardManager = createShardManager(numShards, blacklistingStore);
        this.shardInfoProvider = new ShardInfoProvider(dbNamespace);
        this.healthCheckManager = new HealthCheckManager(dbNamespace,
                shardInfoProvider,
                blacklistingStore,
                shardManager);
        IntStream.range(0, numShards).forEach(
                shard -> shardBundles.add(new HibernateBundle<T>(inEntities, new SessionFactoryFactory()) {
                    @Override
                    protected String name() {
                        return shardInfoProvider.shardName(shard);
                    }

                    @Override
                    public PooledDataSourceFactory getDataSourceFactory(T t) {
                        return getConfig(t).getShards().get(shard);
                    }
                }));
    }

    @Override
    public void run(T configuration, Environment environment) {
        val shardConfigurationListSize = getConfig(configuration).getShards().size();
        if (numShards != shardConfigurationListSize) {
            throw new RuntimeException(
                    "Shard count provided through environment does not match the size of the shard configuration list");
        }
        sessionFactories = shardBundles.stream().map(HibernateBundle::getSessionFactory).collect(Collectors.toList());
        this.shardingOptions = getShardingOptions(configuration);
        environment.admin().addTask(new BlacklistShardTask(shardManager));
        environment.admin().addTask(new UnblacklistShardTask(shardManager));
        healthCheckManager.manageHealthChecks(getConfig(configuration).getBlacklist(), environment);
        setupObservers(configuration, environment.metrics());
    }

    public final void registerObserver(final TransactionObserver observer) {
        if (null == observer) {
            return;
        }
        this.observers.add(observer);
        log.info("Registered observer: " + observer.getClass().getSimpleName());
    }

    public final void registerListener(final TransactionListener listener) {
        if (null == listener) {
            return;
        }
        this.listeners.add(listener);
        log.info("Registered listener: " + listener.getClass().getSimpleName());
    }

    public final void registerFilter(final TransactionFilter filter) {
        if (null == filter) {
            return;
        }
        this.filters.add(filter);
        log.info("Registered filter: " + filter.getClass().getSimpleName());
    }

    @Override
    @SuppressWarnings("unchecked")
    public void initialize(Bootstrap<?> bootstrap) {
        bootstrap.getHealthCheckRegistry().addListener(healthCheckManager);
        shardBundles.forEach(hibernateBundle -> bootstrap.addBundle((ConfiguredBundle) hibernateBundle));
    }

    @VisibleForTesting
    public void runBundles(T configuration, Environment environment) {
        shardBundles.forEach(hibernateBundle -> {
            try {
                hibernateBundle.run(configuration, environment);
            } catch (Exception e) {
                log.error("Error initializing db sharding bundle", e);
                throw new RuntimeException(e);
            }
        });
    }

    @VisibleForTesting
    public void initBundles(Bootstrap bootstrap) {
        shardBundles.forEach(hibernameBundle -> initialize(bootstrap));
    }

    @VisibleForTesting
    public Map<Integer, Boolean> healthStatus() {
        return healthCheckManager.status();
    }

    protected abstract ShardedHibernateFactory getConfig(T config);

    protected Supplier<MetricConfig> getMetricConfig(T config) {
        return () -> getConfig(config).getMetricConfig();
    }

    protected ShardBlacklistingStore getBlacklistingStore() {
        return new InMemoryLocalShardBlacklistingStore();
    }

    private ShardingBundleOptions getShardingOptions(T configuration) {
        val shardingOptions = getConfig(configuration).getShardingOptions();
        return Objects.nonNull(shardingOptions) ? shardingOptions : new ShardingBundleOptions();
    }

    public <EntityType, T extends Configuration>
    LookupDao<EntityType> createParentObjectDao(Class<EntityType> clazz) {
        return new LookupDao<>(this.sessionFactories, clazz,
                new ShardCalculator<>(this.shardManager,
                        new ConsistentHashBucketIdExtractor<>(this.shardManager)),
                this.shardingOptions,
                shardInfoProvider,
                rootObserver);
    }

    public <EntityType, T extends Configuration>
    CacheableLookupDao<EntityType> createParentObjectDao(
            Class<EntityType> clazz,
            LookupCache<EntityType> cacheManager) {
        return new CacheableLookupDao<>(this.sessionFactories,
                clazz,
                new ShardCalculator<>(this.shardManager,
                        new ConsistentHashBucketIdExtractor<>(this.shardManager)),
                cacheManager,
                this.shardingOptions,
                shardInfoProvider,
                rootObserver);
    }

    public <EntityType, T extends Configuration>
    LookupDao<EntityType> createParentObjectDao(
            Class<EntityType> clazz,
            BucketIdExtractor<String> bucketIdExtractor) {
        return new LookupDao<>(this.sessionFactories,
                clazz,
                new ShardCalculator<>(this.shardManager, bucketIdExtractor),
                this.shardingOptions,
                shardInfoProvider,
                rootObserver);
    }

    public BucketCalculator<String> bucketCalculator() {
        return new BucketCalculator<>(new ConsistentHashBucketIdExtractor<>(this.shardManager));
    }

    public <EntityType, T extends Configuration>
    CacheableLookupDao<EntityType> createParentObjectDao(
            Class<EntityType> clazz,
            BucketIdExtractor<String> bucketIdExtractor,
            LookupCache<EntityType> cacheManager) {
        return new CacheableLookupDao<>(this.sessionFactories,
                clazz,
                new ShardCalculator<>(this.shardManager, bucketIdExtractor),
                cacheManager,
                this.shardingOptions,
                shardInfoProvider,
                rootObserver);
    }


    public <EntityType, T extends Configuration>
    RelationalDao<EntityType> createRelatedObjectDao(Class<EntityType> clazz) {
        return new RelationalDao<>(this.sessionFactories, clazz,
                new ShardCalculator<>(this.shardManager,
                        new ConsistentHashBucketIdExtractor<>(this.shardManager)),
                this.shardingOptions,
                shardInfoProvider,
                rootObserver);
    }


    public <EntityType, T extends Configuration>
    CacheableRelationalDao<EntityType> createRelatedObjectDao(
            Class<EntityType> clazz,
            RelationalCache<EntityType> cacheManager) {
        return new CacheableRelationalDao<>(this.sessionFactories,
                clazz,
                new ShardCalculator<>(this.shardManager,
                        new ConsistentHashBucketIdExtractor<>(this.shardManager)),
                cacheManager,
                this.shardingOptions,
                shardInfoProvider,
                rootObserver);
    }


    public <EntityType, T extends Configuration>
    RelationalDao<EntityType> createRelatedObjectDao(
            Class<EntityType> clazz,
            BucketIdExtractor<String> bucketIdExtractor) {
        return new RelationalDao<>(this.sessionFactories,
                clazz,
                new ShardCalculator<>(this.shardManager, bucketIdExtractor),
                this.shardingOptions,
                shardInfoProvider,
                rootObserver);
    }

    public <EntityType, T extends Configuration>
    CacheableRelationalDao<EntityType> createRelatedObjectDao(
            Class<EntityType> clazz,
            BucketIdExtractor<String> bucketIdExtractor,
            RelationalCache<EntityType> cacheManager) {
        return new CacheableRelationalDao<>(this.sessionFactories,
                clazz,
                new ShardCalculator<>(this.shardManager, bucketIdExtractor),
                cacheManager,
                this.shardingOptions,
                shardInfoProvider,
                rootObserver);
    }


    public <EntityType, DaoType extends AbstractDAO<EntityType>, T extends Configuration>
    WrapperDao<EntityType, DaoType> createWrapperDao(Class<DaoType> daoTypeClass) {
        return new WrapperDao<>(this.sessionFactories,
                daoTypeClass,
                new ShardCalculator<>(this.shardManager,
                        new ConsistentHashBucketIdExtractor<>(this.shardManager)));
    }

    public <EntityType, DaoType extends AbstractDAO<EntityType>, T extends Configuration>
    WrapperDao<EntityType, DaoType> createWrapperDao(
            Class<DaoType> daoTypeClass,
            BucketIdExtractor<String> bucketIdExtractor) {
        return new WrapperDao<>(this.sessionFactories,
                daoTypeClass,
                new ShardCalculator<>(this.shardManager, bucketIdExtractor));
    }

    public <EntityType, DaoType extends AbstractDAO<EntityType>, T extends Configuration>
    WrapperDao<EntityType, DaoType> createWrapperDao(
            Class<DaoType> daoTypeClass,
            Class[] extraConstructorParamClasses,
            Class[] extraConstructorParamObjects) {
        return new WrapperDao<>(this.sessionFactories, daoTypeClass,
                extraConstructorParamClasses, extraConstructorParamObjects,
                new ShardCalculator<>(this.shardManager,
                        new ConsistentHashBucketIdExtractor<>(this.shardManager)));
    }

    private void setupObservers(final T config,
                                final MetricRegistry metricRegistry) {
        //Observer chain starts with filters and ends with listener invocations
        //Terminal observer calls the actual method
        rootObserver = new ListenerTriggeringObserver(new TerminalTransactionObserver()).addListeners(listeners);
        for (var observer : observers) {
            if (null == observer) {
                return;
            }
            this.rootObserver = observer.setNext(rootObserver);
        }
        rootObserver = new TransactionMetricObserver(new TransactionMetricManager(getMetricConfig(config),
                metricRegistry)).setNext(rootObserver);
        rootObserver = new FilteringObserver(rootObserver).addFilters(filters);

        //Print the observer chain
        log.debug("Observer chain");
        rootObserver.visit(observer -> {
            log.debug(" Observer: {}", observer.getClass().getSimpleName());
            if (observer instanceof FilteringObserver) {
                log.debug("  Filters:");
                ((FilteringObserver) observer).getFilters().forEach(filter -> log.debug("    - {}", filter.getClass().getSimpleName()));
            }
            if (observer instanceof ListenerTriggeringObserver) {
                log.debug("  Listeners:");
                ((ListenerTriggeringObserver) observer).getListeners().forEach(filter -> log.debug("    - {}", filter.getClass().getSimpleName()));
            }
        });
    }

}

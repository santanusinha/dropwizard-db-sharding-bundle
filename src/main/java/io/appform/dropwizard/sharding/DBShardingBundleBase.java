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

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
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
import io.appform.dropwizard.sharding.healthcheck.HealthCheckManager;
import io.appform.dropwizard.sharding.sharding.BucketIdExtractor;
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

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Base for bundles. This cannot be used by clients. Use one of the derived classes.
 */
@Slf4j
public abstract class DBShardingBundleBase<T extends Configuration> extends BundleCommonBase<T> {

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
    private ShardingBundleOptions shardingOptions = new ShardingBundleOptions();

    private ShardInfoProvider shardInfoProvider;

    private HealthCheckManager healthCheckManager;

    protected DBShardingBundleBase(
            String dbNamespace,
            Class<?> entity,
            Class<?>... entities) {
        super(entity, entities);
        this.dbNamespace = dbNamespace;
        init();
    }

    protected DBShardingBundleBase(String dbNamespace, List<String> classPathPrefixList) {
        super(classPathPrefixList);
        this.dbNamespace = dbNamespace;
        init();
    }

    protected DBShardingBundleBase(Class<?> entity, Class<?>... entities) {
        this(DEFAULT_NAMESPACE, entity, entities);
    }

    protected DBShardingBundleBase(String... classPathPrefixes) {
        this(DEFAULT_NAMESPACE, Arrays.asList(classPathPrefixes));
    }

    protected abstract ShardManager createShardManager(int numShards, ShardBlacklistingStore blacklistingStore);

    private void init() {
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
                shard ->
                    shardBundles.add(new HibernateBundle<T>(initialisedEntities, new SessionFactoryFactory()) {
                    @Override
                    protected String name() {
                        return shardInfoProvider.shardName(shard);
                    }

                    @Override
                    public PooledDataSourceFactory getDataSourceFactory(T t) {
                        var bundleConfig = getConfig(t);
                        //Encryption Support through jasypt-hibernate5
                        //All types will have to be added before a session factory is created.
                        //In multitenant bundle, it is created during run, which gives
                        //an opportunity to evaluate sharding options and enable encryption support if required.
                        //In the base bundle it is created before run and hence this ugly workaround is required.
                        //If we move the init to run, this can be done more elegantly.
                        if(shard == 0 && Objects.nonNull(bundleConfig.getShardingOptions()) && bundleConfig.getShardingOptions().isEncryptionSupportEnabled()) {
                            shardingOptions = bundleConfig.getShardingOptions();
                            Preconditions.checkArgument(shardingOptions.getEncryptionIv().length() == 16, "Encryption IV Should be 16 bytes long");
                            registerStringEncryptor(null, shardingOptions);
                            registerBigIntegerEncryptor(null, shardingOptions);
                            registerBigDecimalEncryptor(null, shardingOptions);
                            registerByteEncryptor(null, shardingOptions);
                        }
                        return bundleConfig.getShards().get(shard);
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
        setupObservers(getConfig(configuration).getMetricConfig(), environment.metrics());
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
}

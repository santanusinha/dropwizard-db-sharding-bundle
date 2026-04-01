package io.appform.dropwizard.sharding;

import com.codahale.metrics.MetricRegistry;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.google.common.base.Preconditions;
import io.appform.dropwizard.sharding.caching.LookupCache;
import io.appform.dropwizard.sharding.caching.RelationalCache;
import io.appform.dropwizard.sharding.config.MetricConfig;
import io.appform.dropwizard.sharding.config.ShardConfig;
import io.appform.dropwizard.sharding.config.ShardType;
import io.appform.dropwizard.sharding.config.ShardingBundleOptions;
import io.appform.dropwizard.sharding.config.ShardingEngineConfig;
import io.appform.dropwizard.sharding.config.ShardingYamlConfig;
import io.appform.dropwizard.sharding.dao.AbstractDAO;
import io.appform.dropwizard.sharding.dao.LookupDao;
import io.appform.dropwizard.sharding.dao.MultiTenantCacheableLookupDao;
import io.appform.dropwizard.sharding.dao.MultiTenantCacheableRelationalDao;
import io.appform.dropwizard.sharding.dao.MultiTenantLookupDao;
import io.appform.dropwizard.sharding.dao.MultiTenantRelationalDao;
import io.appform.dropwizard.sharding.dao.RelationalDao;
import io.appform.dropwizard.sharding.dao.WrapperDao;
import io.appform.dropwizard.sharding.filters.TransactionFilter;
import io.appform.dropwizard.sharding.hibernate.CoreSessionFactoryBuilder;
import io.appform.dropwizard.sharding.listeners.TransactionListener;
import io.appform.dropwizard.sharding.metrics.TransactionMetricManager;
import io.appform.dropwizard.sharding.metrics.TransactionMetricObserver;
import io.appform.dropwizard.sharding.observers.TransactionObserver;
import io.appform.dropwizard.sharding.observers.bucket.BucketKeyObserver;
import io.appform.dropwizard.sharding.observers.bucket.BucketKeyPersistor;
import io.appform.dropwizard.sharding.observers.internal.FilteringObserver;
import io.appform.dropwizard.sharding.observers.internal.ListenerTriggeringObserver;
import io.appform.dropwizard.sharding.observers.internal.TerminalTransactionObserver;
import io.appform.dropwizard.sharding.sharding.BalancedShardManager;
import io.appform.dropwizard.sharding.sharding.EntityMeta;
import io.appform.dropwizard.sharding.sharding.LegacyShardManager;
import io.appform.dropwizard.sharding.sharding.ShardManager;
import io.appform.dropwizard.sharding.sharding.impl.ConsistentHashBucketIdExtractor;
import io.appform.dropwizard.sharding.utils.EntityMetaValidator;
import io.appform.dropwizard.sharding.utils.ShardCalculator;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections.MapUtils;
import org.hibernate.SessionFactory;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Framework-agnostic entry point for db-sharding-client.
 * <p>
 * Builds SessionFactories from simple {@link ShardConfig} strings, creates shard managers,
 * observer chains, and provides DAO factory methods. No Dropwizard dependency.
 * <p>
 * Usage:
 * <pre>{@code
 * ShardingEngine engine = ShardingEngine.create(ShardingEngineConfig.builder()
 *     .tenantShards(Map.of("default", List.of(
 *         ShardConfig.builder().url("jdbc:mysql://localhost/shard0")
 *             .driverClass("com.mysql.cj.jdbc.Driver")
 *             .dialect("org.hibernate.dialect.MySQL8Dialect")
 *             .username("root").password("pass").build(),
 *         ShardConfig.builder().url("jdbc:mysql://localhost/shard1")
 *             .driverClass("com.mysql.cj.jdbc.Driver")
 *             .dialect("org.hibernate.dialect.MySQL8Dialect")
 *             .username("root").password("pass").build()
 *     )))
 *     .entities(List.of(Order.class, OrderItem.class))
 *     .build());
 *
 * LookupDao<Order> orderDao = engine.createLookupDao("default", Order.class);
 * orderDao.save(order);
 * }</pre>
 */
@Slf4j
public class ShardingEngine {

    public static final String DEFAULT_NAMESPACE = "default";

    @Getter
    private final Map<String, List<SessionFactory>> sessionFactories;
    @Getter
    private final Map<String, ShardManager> shardManagers;
    private final Map<String, ShardingBundleOptions> shardingOptions;
    private final Map<String, ShardInfoProvider> shardInfoProviders;
    private final Map<String, EntityMeta> entitiesMeta;
    private final List<Class<?>> entities;
    private TransactionObserver rootObserver;

    private final List<TransactionObserver> observers = new ArrayList<>();
    private final List<TransactionListener> listeners = new ArrayList<>();
    private final List<TransactionFilter> filters = new ArrayList<>();
    private final MetricRegistry metricRegistry;
    private final MetricConfig metricConfig;

    private ShardingEngine(ShardingEngineConfig config) {
        this.sessionFactories = new HashMap<>();
        this.shardManagers = new HashMap<>();
        this.shardingOptions = new HashMap<>();
        this.shardInfoProviders = new HashMap<>();
        this.entities = config.getEntities();
        this.metricRegistry = new MetricRegistry();
        this.metricConfig = config.getMetricConfig();

        // this will validate entities and build EntityMeta
        this.entitiesMeta = EntityMetaValidator.validateAndBuildEntitiesMeta(entities);

        config.getTenantShards().forEach((tenantId, shardConfigs) -> {
            final int shardCount = shardConfigs.size();
            Preconditions.checkArgument(shardCount > 0, "Tenant %s has no shards configured", tenantId);

            final List<SessionFactory> factories = shardConfigs.stream()
                    .map(shardConfig -> CoreSessionFactoryBuilder.build(shardConfig, entities))
                    .collect(Collectors.toList());
            factories.forEach(factory -> factory.getProperties().put("tenant.id", tenantId));
            this.sessionFactories.put(tenantId, factories);

            final ShardManager shardManager = config.getShardType() == ShardType.BALANCED
                    ? new BalancedShardManager(shardCount, config.getBlacklistingStore())
                    : new LegacyShardManager(shardCount, config.getBlacklistingStore());
            this.shardManagers.put(tenantId, shardManager);

            this.shardInfoProviders.put(tenantId, new ShardInfoProvider(tenantId));

            this.shardingOptions.put(tenantId, config.getShardingOptions());
        });

        setupObservers();

        log.info("ShardingEngine initialized: {} tenant(s), {} entity class(es)",
                sessionFactories.size(), entities.size());
    }

    /**
     * Creates and initializes a ShardingEngine from the given config.
     */
    public static ShardingEngine create(ShardingEngineConfig config) {
        return new ShardingEngine(config);
    }

    /**
     * Creates a ShardingEngine from a YAML config file.
     * <p>
     * Example usage:
     * <pre>{@code
     * ShardingEngine engine = ShardingEngine.fromYaml(
     *     new File("local.yml"),
     *     Order.class, OrderItem.class
     * );
     * }</pre>
     *
     * @param yamlFile path to the YAML config file
     * @param entities entity classes to register
     * @return initialized ShardingEngine
     */
    public static ShardingEngine fromYaml(File yamlFile, Class<?>... entities) {
        try {
            final ShardingYamlConfig yamlConfig = yamlMapper().readValue(yamlFile, ShardingYamlConfig.class);
            return fromYamlConfig(yamlConfig, List.of(entities));
        } catch (IOException e) {
            throw new RuntimeException("Failed to load sharding config from " + yamlFile.getPath(), e);
        }
    }

    /**
     * Creates a ShardingEngine from a YAML config file path string.
     *
     * @param yamlPath path to the YAML config file
     * @param entities entity classes to register
     * @return initialized ShardingEngine
     */
    public static ShardingEngine fromYaml(String yamlPath, Class<?>... entities) {
        return fromYaml(new File(yamlPath), entities);
    }

    /**
     * Creates a ShardingEngine from a YAML InputStream (e.g., classpath resource).
     * <p>
     * Example usage:
     * <pre>{@code
     * ShardingEngine engine = ShardingEngine.fromYaml(
     *     getClass().getResourceAsStream("/sharding.yml"),
     *     Order.class, OrderItem.class
     * );
     * }</pre>
     *
     * @param yamlStream InputStream of the YAML config
     * @param entities   entity classes to register
     * @return initialized ShardingEngine
     */
    public static ShardingEngine fromYaml(InputStream yamlStream, Class<?>... entities) {
        try {
            final ShardingYamlConfig yamlConfig = yamlMapper().readValue(yamlStream, ShardingYamlConfig.class);
            return fromYamlConfig(yamlConfig, List.of(entities));
        } catch (IOException e) {
            throw new RuntimeException("Failed to load sharding config from input stream", e);
        }
    }

    private static ShardingEngine fromYamlConfig(ShardingYamlConfig yamlConfig, List<Class<?>> entities) {
        return new ShardingEngine(ShardingEngineConfig.builder()
                .tenantShards(yamlConfig.getTenantShards())
                .shardType(yamlConfig.getShardType())
                .shardingOptions(yamlConfig.getShardingOptions())
                .metricConfig(yamlConfig.getMetricConfig())
                .entities(entities)
                .build());
    }

    private static ObjectMapper yamlMapper() {
        return new ObjectMapper(new YAMLFactory())
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    // ---- Extension point registration (call BEFORE create(), or re-init observers) ----

    public ShardingEngine registerObserver(TransactionObserver observer) {
        if (observer != null) {
            this.observers.add(observer);
            setupObservers();
        }
        return this;
    }

    public ShardingEngine registerListener(TransactionListener listener) {
        if (listener != null) {
            this.listeners.add(listener);
            setupObservers();
        }
        return this;
    }

    public ShardingEngine registerFilter(TransactionFilter filter) {
        if (filter != null) {
            this.filters.add(filter);
            setupObservers();
        }
        return this;
    }

    // ---- DAO factory methods ----

    public <EntityType> MultiTenantLookupDao<EntityType> createMultiTenantLookupDao(Class<EntityType> clazz) {
        return new MultiTenantLookupDao<>(sessionFactories, clazz, shardManagers,
                shardingOptions, shardInfoProviders, rootObserver);
    }

    public <EntityType> LookupDao<EntityType> createLookupDao(String namespace, Class<EntityType> clazz) {
        return new LookupDao<>(namespace, createMultiTenantLookupDao(clazz));
    }

    public <EntityType> LookupDao<EntityType> createLookupDao(Class<EntityType> clazz) {
        return createLookupDao(DEFAULT_NAMESPACE, clazz);
    }

    public <EntityType> MultiTenantRelationalDao<EntityType> createMultiTenantRelationalDao(Class<EntityType> clazz) {
        return new MultiTenantRelationalDao<>(sessionFactories, clazz, shardManagers,
                shardingOptions, shardInfoProviders, rootObserver);
    }

    public <EntityType> RelationalDao<EntityType> createRelationalDao(String namespace, Class<EntityType> clazz) {
        return new RelationalDao<>(namespace, createMultiTenantRelationalDao(clazz));
    }

    public <EntityType> RelationalDao<EntityType> createRelationalDao(Class<EntityType> clazz) {
        return createRelationalDao(DEFAULT_NAMESPACE, clazz);
    }

    public <EntityType> MultiTenantCacheableLookupDao<EntityType> createMultiTenantCacheableLookupDao(
            Class<EntityType> clazz, Map<String, LookupCache<EntityType>> cacheMap) {
        return new MultiTenantCacheableLookupDao<>(sessionFactories, clazz, shardManagers,
                cacheMap, shardingOptions, shardInfoProviders, rootObserver);
    }

    public <EntityType> MultiTenantCacheableRelationalDao<EntityType> createMultiTenantCacheableRelationalDao(
            Class<EntityType> clazz, Map<String, RelationalCache<EntityType>> cacheMap) {
        return new MultiTenantCacheableRelationalDao<>(sessionFactories, clazz, shardManagers,
                cacheMap, shardingOptions, shardInfoProviders, rootObserver);
    }

    public <EntityType, DaoType extends AbstractDAO<EntityType>>
    WrapperDao<EntityType, DaoType> createWrapperDao(String tenantId, Class<DaoType> daoTypeClass) {
        Preconditions.checkArgument(
                sessionFactories.containsKey(tenantId) && shardManagers.containsKey(tenantId),
                "Unknown tenant: " + tenantId);
        return new WrapperDao<>(tenantId, sessionFactories.get(tenantId), daoTypeClass, shardManagers.get(tenantId));
    }

    /**
     * Closes all SessionFactories. Call on application shutdown.
     */
    public void stop() {
        sessionFactories.values().stream()
                .flatMap(List::stream)
                .forEach(sf -> {
                    try {
                        sf.close();
                    } catch (Exception e) {
                        log.error("Error closing session factory", e);
                    }
                });
        log.info("ShardingEngine stopped");
    }

    private void setupObservers() {
        rootObserver = new TerminalTransactionObserver();

        if (!MapUtils.isEmpty(entitiesMeta)) {
            rootObserver = new BucketKeyObserver(
                    new BucketKeyPersistor(DEFAULT_NAMESPACE,
                            new ConsistentHashBucketIdExtractor<>(shardManagers),
                            entitiesMeta)).setNext(rootObserver);
        }

        rootObserver = new ListenerTriggeringObserver(rootObserver).addListeners(listeners);

        for (var observer : observers) {
            if (null == observer) {
                return;
            }
            this.rootObserver = observer.setNext(rootObserver);
        }

        rootObserver = new TransactionMetricObserver(
                new TransactionMetricManager(() -> metricConfig, metricRegistry))
                .setNext(rootObserver);

        rootObserver = new FilteringObserver(rootObserver).addFilters(filters);

        log.debug("Observer chain built");
        rootObserver.visit(obs -> log.debug("  Observer: {}", obs.getClass().getSimpleName()));
    }
}

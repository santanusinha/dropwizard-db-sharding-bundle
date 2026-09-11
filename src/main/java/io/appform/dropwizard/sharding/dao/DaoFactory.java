package io.appform.dropwizard.sharding.dao;

import io.appform.dropwizard.sharding.ShardInfoProvider;
import io.appform.dropwizard.sharding.caching.LookupCache;
import io.appform.dropwizard.sharding.caching.RelationalCache;
import io.appform.dropwizard.sharding.config.ShardingBundleOptions;
import io.appform.dropwizard.sharding.observers.TransactionObserver;
import io.appform.dropwizard.sharding.sharding.ShardManager;
import org.hibernate.SessionFactory;

import java.util.List;
import java.util.Map;

/**
 * The supported way to build the DAOs in this package.
 * <p>
 * This is an enum so it cannot be instantiated by client code, and its methods take
 * bundle-internal collaborators that clients cannot obtain. The DAO constructors will become
 * package private so that this factory is the only construction path.
 */
public enum DaoFactory {
    INSTANCE;

    public <T> MultiTenantLookupDao<T> createMultiTenantLookupDao(
            final Map<String, List<SessionFactory>> sessionFactories,
            final Class<T> entityClass,
            final Map<String, ShardManager> shardManagers,
            final Map<String, ShardingBundleOptions> shardingOptions,
            final Map<String, ShardInfoProvider> shardInfoProviders,
            final TransactionObserver observer) {
        return new MultiTenantLookupDao<>(sessionFactories, entityClass, shardManagers,
                shardingOptions, shardInfoProviders, observer);
    }

    public <T> MultiTenantCacheableLookupDao<T> createMultiTenantCacheableLookupDao(
            final Map<String, List<SessionFactory>> sessionFactories,
            final Class<T> entityClass,
            final Map<String, ShardManager> shardManagers,
            final Map<String, LookupCache<T>> cache,
            final Map<String, ShardingBundleOptions> shardingOptions,
            final Map<String, ShardInfoProvider> shardInfoProviders,
            final TransactionObserver observer) {
        return new MultiTenantCacheableLookupDao<>(sessionFactories, entityClass, shardManagers,
                cache, shardingOptions, shardInfoProviders, observer);
    }

    public <T> MultiTenantRelationalDao<T> createMultiTenantRelationalDao(
            final Map<String, List<SessionFactory>> sessionFactories,
            final Class<T> entityClass,
            final Map<String, ShardManager> shardManagers,
            final Map<String, ShardingBundleOptions> shardingOptions,
            final Map<String, ShardInfoProvider> shardInfoProviders,
            final TransactionObserver observer) {
        return new MultiTenantRelationalDao<>(sessionFactories, entityClass, shardManagers,
                shardingOptions, shardInfoProviders, observer);
    }

    public <T> MultiTenantCacheableRelationalDao<T> createMultiTenantCacheableRelationalDao(
            final Map<String, List<SessionFactory>> sessionFactories,
            final Class<T> entityClass,
            final Map<String, ShardManager> shardManagers,
            final Map<String, RelationalCache<T>> cache,
            final Map<String, ShardingBundleOptions> shardingOptions,
            final Map<String, ShardInfoProvider> shardInfoProviders,
            final TransactionObserver observer) {
        return new MultiTenantCacheableRelationalDao<>(sessionFactories, entityClass, shardManagers,
                cache, shardingOptions, shardInfoProviders, observer);
    }

    public <T> LookupDao<T> createLookupDao(final String tenantId,
                                            final MultiTenantLookupDao<T> delegate) {
        return new LookupDao<>(tenantId, delegate);
    }

    public <T> CacheableLookupDao<T> createCacheableLookupDao(
            final String tenantId,
            final MultiTenantCacheableLookupDao<T> delegate) {
        return new CacheableLookupDao<>(tenantId, delegate);
    }

    public <T> RelationalDao<T> createRelationalDao(final String tenantId,
                                                    final MultiTenantRelationalDao<T> delegate) {
        return new RelationalDao<>(tenantId, delegate);
    }

    public <T> CacheableRelationalDao<T> createCacheableRelationalDao(
            final String tenantId,
            final MultiTenantCacheableRelationalDao<T> delegate) {
        return new CacheableRelationalDao<>(tenantId, delegate);
    }

    public <T, DaoType extends AbstractDAO<T>> WrapperDao<T, DaoType> createWrapperDao(
            final String tenantId,
            final List<SessionFactory> sessionFactories,
            final Class<DaoType> daoClass,
            final ShardManager shardManager) {
        return new WrapperDao<>(tenantId, sessionFactories, daoClass, shardManager);
    }

    public <T, DaoType extends AbstractDAO<T>> WrapperDao<T, DaoType> createWrapperDao(
            final String tenantId,
            final List<SessionFactory> sessionFactories,
            final Class<DaoType> daoClass,
            final Class[] extraConstructorParamClasses,
            final Class[] extraConstructorParamObjects,
            final ShardManager shardManager) {
        return new WrapperDao<>(tenantId, sessionFactories, daoClass, extraConstructorParamClasses,
                extraConstructorParamObjects, shardManager);
    }
}

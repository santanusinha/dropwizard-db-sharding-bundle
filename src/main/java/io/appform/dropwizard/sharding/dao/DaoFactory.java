/*
 * Copyright 2016 Santanu Sinha <santanu.sinha@gmail.com>
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

package io.appform.dropwizard.sharding.dao;

import io.appform.dropwizard.sharding.ShardInfoProvider;
import io.appform.dropwizard.sharding.caching.LookupCache;
import io.appform.dropwizard.sharding.caching.RelationalCache;
import io.appform.dropwizard.sharding.config.ShardingBundleOptions;
import io.appform.dropwizard.sharding.observers.TransactionObserver;
import org.hibernate.SessionFactory;

import java.util.List;
import java.util.Map;

/**
 * Single construction path for sharded DAOs. Applications should obtain DAOs from the
 * sharding bundle's {@code create...Dao} methods rather than calling this directly.
 * Shard placement always comes from {@link io.appform.dropwizard.sharding.ShardCalculatorRegistry}.
 */
public enum DaoFactory {
    INSTANCE;

    public <T> MultiTenantLookupDao<T> createLookupDao(
            Map<String, List<SessionFactory>> sessionFactories,
            Class<T> entityClass,
            Map<String, ShardingBundleOptions> shardingOptions,
            Map<String, ShardInfoProvider> shardInfoProviders,
            TransactionObserver observer) {
        return new MultiTenantLookupDao<>(sessionFactories, entityClass, shardingOptions, shardInfoProviders, observer);
    }

    public <T> MultiTenantCacheableLookupDao<T> createCacheableLookupDao(
            Map<String, List<SessionFactory>> sessionFactories,
            Class<T> entityClass,
            Map<String, LookupCache<T>> cache,
            Map<String, ShardingBundleOptions> shardingOptions,
            Map<String, ShardInfoProvider> shardInfoProviders,
            TransactionObserver observer) {
        return new MultiTenantCacheableLookupDao<>(sessionFactories, entityClass, cache, shardingOptions,
                shardInfoProviders, observer);
    }

    public <T> MultiTenantRelationalDao<T> createRelationalDao(
            Map<String, List<SessionFactory>> sessionFactories,
            Class<T> entityClass,
            Map<String, ShardingBundleOptions> shardingOptions,
            Map<String, ShardInfoProvider> shardInfoProviders,
            TransactionObserver observer) {
        return new MultiTenantRelationalDao<>(sessionFactories, entityClass, shardingOptions, shardInfoProviders,
                observer);
    }

    public <T> MultiTenantCacheableRelationalDao<T> createCacheableRelationalDao(
            Map<String, List<SessionFactory>> sessionFactories,
            Class<T> entityClass,
            Map<String, RelationalCache<T>> cache,
            Map<String, ShardingBundleOptions> shardingOptions,
            Map<String, ShardInfoProvider> shardInfoProviders,
            TransactionObserver observer) {
        return new MultiTenantCacheableRelationalDao<>(sessionFactories, entityClass, cache, shardingOptions,
                shardInfoProviders, observer);
    }

    public <T> LookupDao<T> createLookupDao(String dbNamespace, MultiTenantLookupDao<T> delegate) {
        return new LookupDao<>(dbNamespace, delegate);
    }

    public <T> CacheableLookupDao<T> createCacheableLookupDao(String dbNamespace,
                                                              MultiTenantCacheableLookupDao<T> delegate) {
        return new CacheableLookupDao<>(dbNamespace, delegate);
    }

    public <T> RelationalDao<T> createRelationalDao(String dbNamespace, MultiTenantRelationalDao<T> delegate) {
        return new RelationalDao<>(dbNamespace, delegate);
    }

    public <T> CacheableRelationalDao<T> createCacheableRelationalDao(String dbNamespace,
                                                                      MultiTenantCacheableRelationalDao<T> delegate) {
        return new CacheableRelationalDao<>(dbNamespace, delegate);
    }

    public <T, D extends AbstractDAO<T>> WrapperDao<T, D> createWrapperDao(
            String dbNamespace, List<SessionFactory> sessionFactories, Class<D> daoClass) {
        return new WrapperDao<>(dbNamespace, sessionFactories, daoClass);
    }

    @SuppressWarnings("rawtypes")
    public <T, D extends AbstractDAO<T>> WrapperDao<T, D> createWrapperDao(
            String dbNamespace, List<SessionFactory> sessionFactories, Class<D> daoClass,
            Class[] extraConstructorParamClasses, Class[] extraConstructorParamObjects) {
        return new WrapperDao<>(dbNamespace, sessionFactories, daoClass, extraConstructorParamClasses,
                extraConstructorParamObjects);
    }
}

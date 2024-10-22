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

import io.appform.dropwizard.sharding.DBShardingBundleBase;
import io.appform.dropwizard.sharding.ShardInfoProvider;
import io.appform.dropwizard.sharding.caching.RelationalCache;
import io.appform.dropwizard.sharding.config.ShardingBundleOptions;
import io.appform.dropwizard.sharding.observers.TransactionObserver;
import io.appform.dropwizard.sharding.query.QuerySpec;
import io.appform.dropwizard.sharding.utils.ShardCalculator;
import org.hibernate.SessionFactory;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A read/write through cache enabled {@link RelationalDao}
 */
public class CacheableRelationalDao<T> extends RelationalDao<T> {

    private final String dbNamespace;

    private final MultiTenantCacheableRelationalDao<T> delegate;

    public CacheableRelationalDao(String dbNamespace,
                                  MultiTenantCacheableRelationalDao<T> delegate) {
        super(dbNamespace, delegate);
        this.delegate = delegate;
        this.dbNamespace = dbNamespace;
    }

    /**
     * Constructs a CacheableRelationalDao instance for managing entities across multiple shards with caching support.
     * <p>
     * This constructor initializes a CacheableRelationalDao instance, which extends the functionality of a
     * RelationalDao, for working with entities of the specified class distributed across multiple shards.
     * It requires a list of session factories, a shard calculator, a relational cache, a shard information provider,
     * and a transaction observer. The entity class should designate one field as the primary key using the `@Id` annotation.
     *
     * @param sessionFactories  A list of SessionFactory instances for database access across shards.
     * @param entityClass       The Class representing the type of entities managed by this CacheableRelationalDao.
     * @param shardCalculator   A ShardCalculator instance used to determine the shard for each operation.
     * @param cache             A RelationalCache instance for caching entity data.
     * @param shardInfoProvider A ShardInfoProvider for retrieving shard information.
     * @param observer          A TransactionObserver for monitoring transaction events.
     * @throws IllegalArgumentException If the entity class does not have exactly one field designated as @Id,
     *                                  if the designated key field is not accessible, or if it is not of type String.
     */
    public CacheableRelationalDao(List<SessionFactory> sessionFactories, Class<T> entityClass,
                                  ShardCalculator<String> shardCalculator,
                                  RelationalCache<T> cache,
                                  ShardingBundleOptions shardingOptions,
                                  ShardInfoProvider shardInfoProvider,
                                  TransactionObserver observer) {
        super(sessionFactories, entityClass, shardCalculator, shardingOptions, shardInfoProvider, observer);
        this.dbNamespace = DBShardingBundleBase.DEFAULT_NAMESPACE;
        this.delegate = new MultiTenantCacheableRelationalDao<>(
                Map.of(dbNamespace, sessionFactories),
                entityClass,
                shardCalculator,
                Map.of(dbNamespace, cache),
                Map.of(dbNamespace, shardingOptions),
                Map.of(dbNamespace, shardInfoProvider),
                observer
        );
    }

    @Override
    public Optional<T> get(String parentKey, Object key) {
        return delegate.get(dbNamespace, parentKey, key);
    }

    @Override
    public Optional<T> save(String parentKey, T entity) throws Exception {
        return delegate.save(dbNamespace, parentKey, entity);
    }

    @Override
    public List<T> select(String parentKey, QuerySpec<T, T> criteria, int first, int numResults) throws Exception {
        return delegate.select(dbNamespace, parentKey, criteria, first, numResults);
    }

}

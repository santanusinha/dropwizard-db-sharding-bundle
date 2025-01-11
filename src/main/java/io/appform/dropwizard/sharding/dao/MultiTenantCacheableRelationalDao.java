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
import io.appform.dropwizard.sharding.caching.RelationalCache;
import io.appform.dropwizard.sharding.config.ShardingBundleOptions;
import io.appform.dropwizard.sharding.observers.TransactionObserver;
import io.appform.dropwizard.sharding.utils.ShardCalculator;
import org.hibernate.SessionFactory;
import org.hibernate.criterion.DetachedCriteria;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A read/write through cache enabled {@link MultiTenantRelationalDao}
 */
public class MultiTenantCacheableRelationalDao<T> extends MultiTenantRelationalDao<T> {

  private Map<String, RelationalCache<T>> cache;


  /**
   * Constructs a CacheableRelationalDao instance for managing entities across multiple shards with
   * caching support.
   * <p>
   * This constructor initializes a CacheableRelationalDao instance, which extends the functionality
   * of a RelationalDao, for working with entities of the specified class distributed across
   * multiple shards. It requires a list of session factories, a shard calculator, a relational
   * cache, a shard information provider, and a transaction observer. The entity class should
   * designate one field as the primary key using the `@Id` annotation.
   *
   * @param sessionFactories  A list of SessionFactory instances for database access across shards.
   * @param entityClass       The Class representing the type of entities managed by this
   *                          CacheableRelationalDao.
   * @param shardCalculator   A ShardCalculator instance used to determine the shard for each
   *                          operation.
   * @param cache             A RelationalCache instance for caching entity data.
   * @param shardInfoProvider A ShardInfoProvider for retrieving shard information.
   * @param observer          A TransactionObserver for monitoring transaction events.
   * @throws IllegalArgumentException If the entity class does not have exactly one field designated
   *                                  as @Id, if the designated key field is not accessible, or if
   *                                  it is not of type String.
   */
  public MultiTenantCacheableRelationalDao(Map<String, List<SessionFactory>> sessionFactories,
      Class<T> entityClass,
      ShardCalculator<String> shardCalculator,
      Map<String, RelationalCache<T>> cache,
      Map<String, ShardingBundleOptions> shardingOptions,
      Map<String, ShardInfoProvider> shardInfoProvider,
      TransactionObserver observer) {
    super(sessionFactories, entityClass, shardCalculator, shardingOptions, shardInfoProvider,
        observer);
    this.cache = cache;
  }


  /**
   * Retrieves an entity from the cache or the database based on the parent key and entity key.
   * <p>
   * This method attempts to retrieve an entity from the cache first using the provided parent key
   * and entity key. If the entity is found in the cache, it is returned as an Optional. If not
   * found in the cache, the method falls back to the parent class's (superclass) `get` method to
   * retrieve the entity from the database. If the entity is found in the database, it is added to
   * the cache for future access. If the entity is not found in either the cache or the database, an
   * empty Optional is returned.
   *
   * @param parentKey The parent key associated with the entity.
   * @param key       The key of the entity to retrieve.
   * @return An Optional containing the retrieved entity if found, or an empty Optional if the
   * entity is not found.
   * @throws IllegalArgumentException If the parent key or entity key is invalid.
   */
  @Override
  public Optional<T> get(String tenantId, String parentKey, Object key) {
    if (cache.get(tenantId).exists(parentKey, key)) {
      return Optional.ofNullable(cache.get(tenantId).get(parentKey, key));
    }
    T entity = super.get(tenantId, parentKey, key, t -> t);
    if (entity != null) {
      cache.get(tenantId).put(parentKey, key, entity);
    }
    return Optional.ofNullable(entity);
  }

  @Override
  public Optional<T> save(String tenantId, String parentKey, T entity) throws Exception {
    T savedEntity = super.save(tenantId, parentKey, entity, t -> t);
    if (savedEntity != null) {
      final String key = getKeyField().get(entity).toString();
      cache.get(tenantId).put(parentKey, key, entity);
    }
    return Optional.ofNullable(savedEntity);
  }

  @Override
  public List<T> select(String tenantId, String parentKey, DetachedCriteria criteria, int first,
      int numResults) throws Exception {
    List<T> result = cache.get(tenantId).select(parentKey, first, numResults);
    if (result == null) {
      result = super.select(tenantId, parentKey, criteria, first, numResults);
    }
    if (result != null) {
      cache.get(tenantId).put(parentKey, first, numResults, result);
    }
    return select(tenantId, parentKey, criteria, first, numResults, t -> t);
  }
}

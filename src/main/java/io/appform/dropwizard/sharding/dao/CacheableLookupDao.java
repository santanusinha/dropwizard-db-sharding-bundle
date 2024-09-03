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
import io.appform.dropwizard.sharding.config.ShardingBundleOptions;
import io.appform.dropwizard.sharding.exceptions.DaoFwdException;
import io.appform.dropwizard.sharding.observers.TransactionObserver;
import io.appform.dropwizard.sharding.sharding.LookupKey;
import io.appform.dropwizard.sharding.utils.ShardCalculator;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.SessionFactory;

import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * A write through/read through cache enabled dao to manage lookup and top level elements in the system.
 * Can save and retrieve an object (tree) from any shard.
 * <b>Note:</b>
 * - The element must have only one String key for lookup.
 * - The key needs to be annotated with {@link LookupKey}
 * The entity can be retrieved from any shard using the key.
 */
@Slf4j
public class CacheableLookupDao<T> extends LookupDao<T> {

    private LookupCache<T> cache;

    /**
     * Constructs a CacheableLookupDao instance with caching support.
     *
     * This constructor initializes a CacheableLookupDao instance with the provided parameters, enabling caching for
     * improved performance and data retrieval optimization.
     *
     * @param sessionFactories A list of SessionFactory instances for database access.
     * @param entityClass The Class representing the entity type handled by the DAO.
     * @param shardCalculator A ShardCalculator for determining the database shard based on keys.
     * @param cache The LookupCache implementation for caching entities.
     * @param shardingOptions The ShardingBundleOptions for configuring the DAO.
     * @param shardInfoProvider The ShardInfoProvider for obtaining shard information.
     * @param observer A TransactionObserver for observing transaction events.
     */
    public CacheableLookupDao(List<SessionFactory> sessionFactories,
                              Class<T> entityClass,
                              ShardCalculator<String> shardCalculator,
                              LookupCache<T> cache,
                              ShardingBundleOptions shardingOptions,
                              ShardInfoProvider shardInfoProvider,
                              TransactionObserver observer) {
        super(sessionFactories, entityClass, shardCalculator, shardingOptions, shardInfoProvider, observer);
        this.cache = cache;
    }

    /**
     * Retrieves an entity from the cache or the database based on the specified key and caches it if necessary.
     *
     * This method first checks if the entity exists in the cache based on the provided key. If the entity is found
     * in the cache, it is returned as an Optional. If not found in the cache, the method falls back to the superclass's
     * `get` method to retrieve the entity from the database using the specified key. If the entity is found in the database,
     * it is added to the cache for future access and returned as an Optional. If the entity is not found in either the
     * cache or the database, an empty Optional is returned.
     *
     * @param key The key or identifier of the entity to retrieve.
     * @return An Optional containing the retrieved entity if found, or an empty Optional if the entity is not found.
     * @throws Exception If an error occurs during the retrieval process.
     */
    @Override
    public Optional<T> get(String key) throws Exception {
        if (cache.exists(key)) {
            return Optional.of(cache.get(key));
        }
        T entity = super.get(key, Function.identity());
        if (entity != null) {
            cache.put(key, entity);
        }
        return Optional.ofNullable(entity);
    }

    /**
     * Saves an entity to the database and caches the saved entity if successful.
     *
     * This method attempts to save the provided entity to the database using the superclass's `save` method.
     * If the save operation succeeds, it retrieves the saved entity from the database, caches it, and returns it
     * wrapped in an Optional. If the save operation fails, it returns an empty Optional.
     *
     * @param entity The entity to be saved.
     * @return An Optional containing the saved entity if the save operation is successful, or an empty Optional
     *         if the save operation fails.
     * @throws Exception If an error occurs during the save operation.
     */
    @Override
    public Optional<T> save(T entity) throws Exception {
        T savedEntity = super.save(entity, t -> t);
        if (savedEntity != null) {
            final String key = getKeyField().get(entity).toString();
            cache.put(key, entity);
        }
        return Optional.ofNullable(savedEntity);
    }

    /**
     * Updates an entity using the provided updater function and caches the updated entity.
     *
     * This method updates an entity identified by the given ID using the provided updater function. It first attempts
     * to update the entity using the superclass's `update` method. If the update operation succeeds, it retrieves the
     * updated entity from the database, caches it, and returns `true`. If the update operation fails, it returns `false`.
     *
     * @param id The ID of the entity to update.
     * @param updater A function that takes an Optional of the current entity and returns the updated entity.
     * @return `true` if the entity is successfully updated and cached, or `false` if the update operation fails.
     * @throws DaoFwdException If an error occurs while updating or caching the entity.
     */
    @Override
    public boolean update(String id, Function<Optional<T>, T> updater) {
        boolean result = super.update(id, updater);
        if (result) {
            try {
                Optional<T> updatedEntity = super.get(id);
                updatedEntity.ifPresent(t -> cache.put(id, t));
            } catch (Exception e) {
                throw new DaoFwdException("Error updating entity: " + id, e);
            }
        }
        return result;
    }

    /**
     * Read through exists check on the basis of key (value of field annotated with {@link LookupKey}) from cache.
     * Cache miss will be delegated to {@link LookupDao#exists(String)} method.
     *
     * @param key The value of the key field to look for.
     * @return Whether the entity exists or not
     * @throws Exception if backing dao throws
     */
    @Override
    public boolean exists(String key) throws Exception {
        if (cache.exists(key)) {
            return true;
        }
        Optional<T> entity = super.get(key);
        entity.ifPresent(t -> cache.put(key, t));
        return entity.isPresent();
    }
}

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

import com.codahale.metrics.MetricRegistry;
import io.appform.dropwizard.sharding.ShardInfoProvider;
import io.appform.dropwizard.sharding.caching.LookupCache;
import io.appform.dropwizard.sharding.exceptions.DaoFwdException;
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

    public CacheableLookupDao(MetricRegistry metricRegistry,
                              ShardInfoProvider shardInfoProvider,
                              List<SessionFactory> sessionFactories,
                              Class<T> entityClass,
                              ShardCalculator<String> shardCalculator, LookupCache<T> cache) {
        super(metricRegistry, shardInfoProvider, sessionFactories, entityClass, shardCalculator);
        this.cache = cache;
    }

    /**
     * Read through an object on the basis of key (value of field annotated with {@link LookupKey}) from cache.
     * Cache miss will be delegated to {@link LookupDao#get(String)} method.
     * <b>Note:</b> Lazy loading will not work once the object is returned.
     * If you need lazy loading functionality use the alternate {@link LookupDao#get(String, Function)} method.
     * @param key The value of the key field to look for.
     * @return The entity
     * @throws Exception if backing dao throws
     */
    @Override
    public Optional<T> get(String key) throws Exception {
        if(cache.exists(key)) {
            return Optional.of(cache.get(key));
        }
        T entity = super.get(key, t -> t);
        if(entity != null) {
            cache.put(key, entity);
        }
        return Optional.ofNullable(entity);
    }

    /**
     * Write through the entity on proper shard based on hash of the value in the key field in the object and into cache.
     * <b>Note:</b> Lazy loading will not work on the augmented entity.
     * @param entity Entity to save
     * @return Entity
     * @throws Exception if backing dao throws
     */
    @Override
    public Optional<T> save(T entity) throws Exception {
        T savedEntity = super.save(entity, t -> t);
        if(savedEntity != null) {
            final String key = getKeyField().get(entity).toString();
            cache.put(key, entity);
        }
        return Optional.ofNullable(savedEntity);
    }

    /**
     * Update the entity with a given id and refresh the object in the cache.
     * Actual save will be delegated to {@link LookupDao#update(String, Function)} method.
     * @param id Id of the entity that will be updated
     * @return True/False
     */
    @Override
    public boolean update(String id, Function<Optional<T>, T> updater) {
        boolean result = super.update(id, updater);
        if(result) {
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
     * @param key The value of the key field to look for.
     * @return Whether the entity exists or not
     * @throws Exception if backing dao throws
     */
    @Override
    public boolean exists(String key) throws Exception {
        if(cache.exists(key)) {
            return true;
        }
        Optional<T> entity = super.get(key);
        entity.ifPresent(t -> cache.put(key, t));
        return entity.isPresent();
    }
}

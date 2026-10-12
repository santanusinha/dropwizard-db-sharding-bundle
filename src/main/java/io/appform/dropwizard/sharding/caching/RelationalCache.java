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
package io.appform.dropwizard.sharding.caching;


import java.util.List;
import java.util.function.Function;

/**
 * A simple cache interface which allows plugging in any caching framework or infrastructure to enable
 * write through caching
 */
public interface RelationalCache<V> {

    /**
     * Write through method that will be called if cache enabled {@link io.appform.dropwizard.sharding.dao.CacheableRelationalDao#save(String, Object)} is used
     *
     * @param parentKey The key of the parent that the entity is related to
     * @param key       The key that needs to be used to write this element to cache
     * @param entity    Entity that needs to be written into cache
     */
    void put(String parentKey, Object key, V entity);

    /**
     * Write through method that will be called if cache enabled {@link io.appform.dropwizard.sharding.dao.CacheableRelationalDao#select(String, io.appform.dropwizard.sharding.query.QuerySpec, int, int, Function)} is used
     *
     * @param parentKey The key of the parent that the entity is related to
     * @param entities  List of entities that needs to be written into cache
     */
    void put(String parentKey, List<V> entities);

    /**
     * Write through method that will be called if cache enabled {@link io.appform.dropwizard.sharding.dao.CacheableRelationalDao#select(String, io.appform.dropwizard.sharding.query.QuerySpec, int, int, Function)} is used
     *
     * @param parentKey The key of the parent that the entity is related to
     * @param entities  List of entities that needs to be written into cache
     */
    void put(String parentKey, int first, int numResults, List<V> entities);

    /**
     * Read through exists method that will be called if cache enabled {@link io.appform.dropwizard.sharding.dao.CacheableRelationalDao#exists(String, Object)} is used
     *
     * @param parentKey The key of the parent that the entity is related to
     * @param key       The key that needs to be used to write this element to cache
     * @return Whether the entity exists or not.
     */
    boolean exists(String parentKey, Object key);

    /**
     * Read through method that will be called if a cache enabled {@link io.appform.dropwizard.sharding.dao.CacheableRelationalDao#get(String, Object)} is used
     *
     * @param parentKey The key of the parent the entity is related to
     * @param key       The key of the entity that needs to be read
     * @return entity Entity that was read through the cache
     */
    V get(String parentKey, Object key);

    /**
     * Read through method that will be called if a cache enabled {@link io.appform.dropwizard.sharding.dao.CacheableRelationalDao#select(String, io.appform.dropwizard.sharding.query.QuerySpec, int, int, Function)} is used
     *
     * @param parentKey The key of the parent the entity is related to
     * @return Entities that was read through the cache
     */
    List<V> select(String parentKey);

    /**
     * Read through method that will be called if a cache enabled {@link io.appform.dropwizard.sharding.dao.CacheableRelationalDao#select(String, io.appform.dropwizard.sharding.query.QuerySpec, int, int, Function)} is used
     *
     * @param parentKey The key of the parent the entity is related to
     * @return Entities that was read through the cache
     */
    List<V> select(String parentKey, int first, int numResults);

}

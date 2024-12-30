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


/**
 * A simple cache interface which allows plugging in any caching framework or infrastructure to enable
 * write through caching
 */
public interface LookupCache<V> {

    /**
     * Write through method that will be called if cache enabled {@link io.appform.dropwizard.sharding.dao.CacheableLookupDao#save(Object)} is used
     *
     * @param key    The key that needs to be used to write this element to cache
     * @param entity Entity that needs to be written into cache
     */
    void put(String key, V entity);

    /**
     * Read through exists method that will be called if cache enabled {@link io.appform.dropwizard.sharding.dao.CacheableLookupDao#exists(String)} is used
     *
     * @param key The key of the entity that needs to be checked
     * @return whether the entity exists or not.
     */
    boolean exists(String key);

    /**
     * Read through method that will be called if a cache enabled {@link io.appform.dropwizard.sharding.dao.CacheableLookupDao#get(String)} is used
     *
     * @param key The key of the entity that needs to be read
     * @return entity Entity that was read through the cache
     */
    V get(String key);
}

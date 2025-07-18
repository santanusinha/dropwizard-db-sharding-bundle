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

import io.appform.dropwizard.sharding.query.QuerySpec;
import org.hibernate.criterion.DetachedCriteria;

import java.util.List;
import java.util.Optional;

/**
 * A read/write through cache enabled {@link RelationalDao}
 */
public class CacheableRelationalDao<T> extends RelationalDao<T> {

    private final String dbNamespace;

    private final MultiTenantCacheableRelationalDao<T> delegate;

    /**
     * Constructs a CacheableRelationalDao instance for managing entities across multiple shards with caching support.
     * <p>
     * This constructor initializes a CacheableRelationalDao instance, which extends the functionality of a
     * RelationalDao, for working with entities of the specified class distributed across multiple shards.
     * The entity class should designate one field as the primary key using the `@Id` annotation.
     *
     */
    public CacheableRelationalDao(String dbNamespace,
                                  MultiTenantCacheableRelationalDao<T> delegate) {
        super(dbNamespace, delegate);
        this.delegate = delegate;
        this.dbNamespace = dbNamespace;
    }

    /**
     * Retrieves an entity from the cache or the database based on the parent key and entity key.
     *
     * This method attempts to retrieve an entity from the cache first using the provided parent key and entity key.
     * If the entity is found in the cache, it is returned as an Optional. If not found in the cache, the method falls
     * back to the parent class's (superclass) `get` method to retrieve the entity from the database. If the entity is
     * found in the database, it is added to the cache for future access. If the entity is not found in either the cache
     * or the database, an empty Optional is returned.
     *
     * @param parentKey The parent key associated with the entity.
     * @param key The key of the entity to retrieve.
     * @return An Optional containing the retrieved entity if found, or an empty Optional if the entity is not found.
     * @throws IllegalArgumentException If the parent key or entity key is invalid.
     */
    @Override
    public Optional<T> get(String parentKey, Object key) {
        return delegate.get(dbNamespace, parentKey, key);
    }

    @Override
    public Optional<T> save(String parentKey, T entity) throws Exception {
        return delegate.save(dbNamespace, parentKey, entity);
    }

    @Override
    public List<T> select(String parentKey, DetachedCriteria criteria, int first, int numResults) throws Exception {
        return delegate.select(dbNamespace, parentKey, criteria, first, numResults);
    }

    @Override
    public List<T> select(String parentKey, QuerySpec<T, T> criteria, int first, int numResults) throws Exception {
        return delegate.select(dbNamespace, parentKey, criteria, first, numResults);
    }

}

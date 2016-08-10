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

package io.dropwizard.sharding.dao;

import com.google.common.base.Preconditions;
import io.dropwizard.sharding.sharding.BucketIdExtractor;
import io.dropwizard.sharding.sharding.LookupKey;
import io.dropwizard.sharding.sharding.ShardManager;
import io.dropwizard.sharding.sharding.impl.ConsistentHashBucketIdExtractor;
import io.dropwizard.sharding.utils.ShardCalculator;
import io.dropwizard.sharding.utils.Transactions;
import io.dropwizard.hibernate.AbstractDAO;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.ClassUtils;
import org.apache.commons.lang3.reflect.FieldUtils;
import org.hibernate.SessionFactory;
import org.hibernate.criterion.DetachedCriteria;
import org.hibernate.criterion.Restrictions;

import java.lang.reflect.Field;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * A dao to manage lookup and top level elements in the system. Can save and retrieve an object (tree) from any shard.
 * <b>Note:</b>
 * - The element must have only one String key for lookup.
 * - The key needs to be annotated with {@link LookupKey}
 * The entity can be retrieved from any shard using the key.
 */
@Slf4j
public class LookupDao<T> {

    /**
     * This DAO wil be used to perform the ops inside a shard
     */
    private final class LookupDaoPriv extends AbstractDAO<T> {

        private final SessionFactory sessionFactory;

        public LookupDaoPriv(SessionFactory sessionFactory) {
            super(sessionFactory);
            this.sessionFactory = sessionFactory;
        }

        /**
         * Get an element from the shard.
         * @param lookupKey  Id of the object
         * @return Extracted element or null if not found.
         */
        T get(String lookupKey) {
            return uniqueResult(currentSession()
                                .createCriteria(entityClass)
                                .add(
                                        Restrictions.eq(keyField.getName(), lookupKey)));
        }

        /**
         * Save the lookup element. Returns the augmented element id any generated fields are present.
         * @param entity Object to save
         * @return Augmented entity
         */
        T save(T entity) {
            return persist(entity);
        }

        void update(T entity) {
            currentSession().evict(entity); //Detach .. otherwise update is a no-op
            currentSession().update(entity);
        }

        /**
         * Run a query inside this shard and return the matching list.
         * @param criteria selection criteria to be applied.
         * @return List of elements or empty list if none found
         */
        List<T> select(DetachedCriteria criteria) {
            return list(criteria.getExecutableCriteria(currentSession()));
        }

    }

    private List<LookupDaoPriv> daos;
    private final Class<T> entityClass;
    private final ShardManager shardManager;
    private final Field keyField;
    private final BucketIdExtractor bucketIdExtractor;

    /**
     * Creates a new sharded DAO. The number of managed shards and buckeing is controlled by the {@link ShardManager}.
     *
     * @param sessionFactories a session provider for each shard
     */
    public LookupDao(List<SessionFactory> sessionFactories, Class<T> entityClass, ShardManager shardManager) {
        this(sessionFactories, entityClass, shardManager, new ConsistentHashBucketIdExtractor<>());
    }


    public LookupDao(List<SessionFactory> sessionFactories, Class<T> entityClass, ShardManager shardManager, BucketIdExtractor bucketIdExtractor) {
        this.shardManager = shardManager;
        this.daos = sessionFactories.stream().map(LookupDaoPriv::new).collect(Collectors.toList());
        this.entityClass = entityClass;
        this.bucketIdExtractor = bucketIdExtractor;

        Field fields[] = FieldUtils.getFieldsWithAnnotation(entityClass, LookupKey.class);
        Preconditions.checkArgument(fields.length != 0, "At least one field needs to be sharding key");
        Preconditions.checkArgument(fields.length == 1, "Only one field can be sharding key");
        keyField = fields[0];
        if(!keyField.isAccessible()) {
            try {
                keyField.setAccessible(true);
            } catch (SecurityException e) {
                log.error("Error making key field accessible please use a public method and mark that as LookupKey", e);
                throw new IllegalArgumentException("Invalid class, DAO cannot be created.", e);
            }
        }
        Preconditions.checkArgument(ClassUtils.isAssignable(keyField.getType(), String.class), "Key field must be a string");
    }

    /**
     * Get an object on the basis of key (value of field annotated with {@link LookupKey}) from any shard.
     * <b>Note:</b> Lazy loading will not work once the object is returned.
     * If you need lazy loading functionality use the alternate {@link #get(String, Function)} method.
     * @param key The value of the key field to look for.
     * @return The entity
     * @throws Exception
     */
    public Optional<T> get(String key) throws Exception {
        return Optional.ofNullable(get(key, t -> t));
    }

    /**
     * Get an object on the basis of key (value of field annotated with {@link LookupKey}) from any shard
     * and applies the provided function/lambda to it. The return from the handler becomes the return to the get function.
     * <b>Note:</b> The transaction is open when handler is applied. So lazy loading will work inside the handler.
     * Once get returns, lazy loading will nt owrok.
     * @param key The value of the key field to look for.
     * @param handler Handler function/lambda that receives the retrieved object.
     * @return Whatever is returned by the handler function
     * @throws Exception
     */
    public <U> U get(String key, Function<T, U> handler) throws Exception {
        int shardId = ShardCalculator.shardId(shardManager, key, bucketIdExtractor);
        LookupDaoPriv dao = daos.get(shardId);
        return Transactions.execute(dao.sessionFactory, true, dao::get, key, handler);
    }

    /**
     * Check if object with specified key exists in any shard.
     * @param key id of the element to look for
     * @return true/false depending on if it's found or not.
     * @throws Exception
     */
    public boolean exists(String key) throws Exception {
        return get(key).isPresent();
    }

    /**
     * Saves an entity on proper shard based on hash of the value in the key field in the object.
     * The updated entity is returned. If {@link org.hibernate.annotations.Cascade} is specified, this can be used
     * to save an object tree based on the shard of the top entity that has the key field.
     * <b>Note:</b> Lazy loading will not work on the augmented entity. Use the alternate {@link #save(Object, Function)} for that.
     * @param entity Entity to save
     * @return Entity
     * @throws Exception
     */
    public Optional<T> save(T entity) throws Exception {
        return Optional.ofNullable(save(entity, t -> t));
    }

    /**
     * Save an object on the basis of key (value of field annotated with {@link LookupKey}) to target shard
     * and applies the provided function/lambda to it. The return from the handler becomes the return to the get function.
     * <b>Note:</b> Handler is executed in the same transactional context as the save operation.
     * So any updates made to the object in this context will also get persisted.
     * @param entity The value of the key field to look for.
     * @param handler Handler function/lambda that receives the retrieved object.
     * @return The entity
     * @throws Exception
     */
    public <U> U save(T entity, Function<T, U> handler) throws Exception {
        final String key = keyField.get(entity).toString();
        int shardId = ShardCalculator.shardId(shardManager, key, bucketIdExtractor);
        log.debug("Saving entity of type {} with key {} to shard {}", entityClass.getSimpleName(), key, shardId);
        LookupDaoPriv dao = daos.get(shardId);
        return Transactions.execute(dao.sessionFactory, false, dao::save, entity, handler);
    }

    public boolean update(String id, Function<Optional<T>, T> updater) {
        int shardId = ShardCalculator.shardId(shardManager, id, bucketIdExtractor);
        LookupDaoPriv dao = daos.get(shardId);
        try {
            return Transactions.<T, String, Boolean>execute(dao.sessionFactory, true, dao::get, id, entity -> {
                T newEntity = updater.apply(Optional.ofNullable(entity));
                if(null == newEntity) {
                    return false;
                }
                dao.update(newEntity);
                return true;
            });
        } catch (Exception e) {
            throw new RuntimeException("Error updating entity: " + id, e);
        }
    }

    /**
     * Queries using the specified criteria across all shards and returns the result.
     * <b>Note:</b> This method runs the query serially and it's usage is not recommended.
     * @param criteria The selct criteria
     * @return List of elements or empty if none match
     */
    public List<T> scatterGather(DetachedCriteria criteria) {
        return daos.stream().map(dao -> {
            try {
                return Transactions.execute(dao.sessionFactory, true, dao::select, criteria);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }).flatMap(Collection::stream).collect(Collectors.toList());
    }






}

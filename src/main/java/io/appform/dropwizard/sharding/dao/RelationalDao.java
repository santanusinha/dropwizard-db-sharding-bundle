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

import com.google.common.base.Preconditions;
import io.appform.dropwizard.sharding.ShardInfoProvider;
import io.appform.dropwizard.sharding.execution.TransactionExecutor;
import io.appform.dropwizard.sharding.observers.TransactionObserver;
import io.appform.dropwizard.sharding.query.QuerySpec;
import io.appform.dropwizard.sharding.utils.ShardCalculator;
import io.dropwizard.hibernate.AbstractDAO;
import lombok.Builder;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import lombok.val;
import org.apache.commons.lang3.reflect.FieldUtils;
import org.hibernate.Criteria;
import org.hibernate.LockMode;
import org.hibernate.ScrollMode;
import org.hibernate.ScrollableResults;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.criterion.DetachedCriteria;
import org.hibernate.criterion.Projections;
import org.hibernate.criterion.Restrictions;
import org.hibernate.query.Query;

import javax.persistence.Id;
import javax.persistence.LockModeType;
import javax.persistence.criteria.CriteriaBuilder;
import javax.persistence.criteria.CriteriaQuery;
import javax.persistence.criteria.Root;
import java.lang.reflect.Field;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * A dao used to work with entities related to a parent shard. The parent may or maynot be physically present.
 * A murmur 128 hash of the string parent key is used to route the save and retrieve calls from the proper shard.
 */
@Slf4j
public class RelationalDao<T> implements ShardedDao<T> {

    private final class RelationalDaoPriv extends AbstractDAO<T> {

        private final SessionFactory sessionFactory;

        /**
         * Creates a new DAO with a given session provider.
         *
         * @param sessionFactory a session provider
         */
        public RelationalDaoPriv(SessionFactory sessionFactory) {
            super(sessionFactory);
            this.sessionFactory = sessionFactory;
        }


        /**
         * Get a row matching the field annotated with {@code @Id} annotation
         *
         * @param lookupKey ID for which data needs to be fetched
         */

        T get(Object lookupKey) {
            return uniqueResult(currentSession()
                    .createCriteria(entityClass)
                    .add(Restrictions.eq(keyField.getName(), lookupKey))
                    .setLockMode(LockMode.READ));
        }

        /**
         * Reads all rows matching the {@code querySpec} in locked mode. This is equivalent to <i>for update</i> semantics
         * during database fetch
         *
         * @param querySpec QuerySpec to be used. This should contain all JPA filters which need to be applied for row selection
         */
        T getLockedForWrite(final QuerySpec<T, T> querySpec) {
            val q = createQuery(currentSession(), entityClass, querySpec);
            return uniqueResult(q.setLockMode(LockModeType.PESSIMISTIC_WRITE));
        }


        T getLockedForWrite(DetachedCriteria criteria) {
            return uniqueResult(criteria.getExecutableCriteria(currentSession())
                    .setLockMode(LockMode.UPGRADE_NOWAIT));
        }

        T save(T entity) {
            return persist(entity);
        }

        boolean saveAll(Collection<T> entities) {
            for (T entity : entities) {
                persist(entity);
            }
            return true;
        }

        void update(T oldEntity, T entity) {
            currentSession().evict(oldEntity); //Detach .. otherwise update is a no-op
            currentSession().update(entity);
        }

        List<T> select(SelectParamPriv<T> selectParam) {
            if (selectParam.criteria != null) {
                val criteria = selectParam.criteria.getExecutableCriteria(currentSession());
                criteria.setFirstResult(selectParam.start);
                criteria.setMaxResults(selectParam.numRows);
                return list(criteria);
            }
            val query = createQuery(currentSession(), entityClass, selectParam.querySpec);
            query.setFirstResult(selectParam.start);
            query.setMaxResults(selectParam.numRows);
            return list(query);
        }

        ScrollableResults scroll(ScrollParamPriv<T> scrollDetails) {
            if (scrollDetails.getCriteria() != null) {
                final Criteria criteria = scrollDetails.getCriteria().getExecutableCriteria(currentSession());
                return criteria.scroll(ScrollMode.FORWARD_ONLY);
            }
            return createQuery(currentSession(), entityClass, scrollDetails.querySpec)
                    .scroll(ScrollMode.FORWARD_ONLY);
        }


        long count(final DetachedCriteria criteria) {
            return (long) criteria.getExecutableCriteria(currentSession())
                    .setProjection(Projections.rowCount())
                    .uniqueResult();
        }

        /**
         * Return count of rows matching the {@code querySpec}
         * @param querySpec  QuerySpec to be used. This should contain all JPA filters which need to be applied for row selection
         */
        long count(final QuerySpec<T, Long> querySpec) {
            val session = currentSession();
            CriteriaBuilder criteriaBuilder = session.getCriteriaBuilder();
            CriteriaQuery<Long> criteriaQuery = criteriaBuilder.createQuery(Long.class);
            Root<T> root = criteriaQuery.from(entityClass);
            criteriaQuery.select(criteriaBuilder.count(root));
            querySpec.apply(root, criteriaQuery, criteriaBuilder);
            Query<Long> query = session.createQuery(criteriaQuery);
            return query.getSingleResult();
        }

        public int update(final UpdateOperationMeta updateOperationMeta) {
            Query query = currentSession().createNamedQuery(updateOperationMeta.getQueryName());
            updateOperationMeta.getParams().forEach(query::setParameter);
            return query.executeUpdate();
        }

    }

    @Builder
    private static class SelectParamPriv<T> {
        DetachedCriteria criteria;
        QuerySpec<T, T> querySpec;
        int start;
        int numRows;
    }

    @Builder
    private static class ScrollParamPriv<T> {
        @Getter
        private DetachedCriteria criteria;
        @Getter
        private QuerySpec<T, T> querySpec;
    }

    private List<RelationalDaoPriv> daos;
    private final Class<T> entityClass;
    @Getter
    private final ShardCalculator<String> shardCalculator;
    private final Field keyField;

    private final TransactionExecutor transactionExecutor;
    private final ShardInfoProvider shardInfoProvider;
    private final TransactionObserver observer;

    /**
     * Constructs a RelationalDao instance for managing entities across multiple shards.
     *
     * This constructor initializes a RelationalDao instance for working with entities of the specified class
     * distributed across multiple shards. It requires a list of session factories, a shard calculator,
     * a shard information provider, and a transaction observer. The entity class must designate one field as
     * the primary key using the `@Id` annotation.
     *
     * @param sessionFactories A list of SessionFactory instances for database access across shards.
     * @param entityClass The Class representing the type of entities managed by this RelationalDao.
     * @param shardCalculator A ShardCalculator instance used to determine the shard for each operation.
     * @param shardInfoProvider A ShardInfoProvider for retrieving shard information.
     * @param observer A TransactionObserver for monitoring transaction events.
     * @throws IllegalArgumentException If the entity class does not have exactly one field designated as @Id,
     *         if the designated key field is not accessible, or if it is not of type String.
     */
    public RelationalDao(
            List<SessionFactory> sessionFactories, Class<T> entityClass,
            ShardCalculator<String> shardCalculator,
            final ShardInfoProvider shardInfoProvider,
            final TransactionObserver observer) {
        this.shardCalculator = shardCalculator;
        this.daos = sessionFactories.stream().map(RelationalDaoPriv::new).collect(Collectors.toList());
        this.entityClass = entityClass;
        this.shardInfoProvider = shardInfoProvider;
        this.observer = observer;
        this.transactionExecutor = new TransactionExecutor(shardInfoProvider, getClass(), entityClass, observer);

        Field fields[] = FieldUtils.getFieldsWithAnnotation(entityClass, Id.class);
        Preconditions.checkArgument(fields.length != 0, "A field needs to be designated as @Id");
        Preconditions.checkArgument(fields.length == 1, "Only one field can be designated as @Id");
        keyField = fields[0];
        if (!keyField.isAccessible()) {
            try {
                keyField.setAccessible(true);
            } catch (SecurityException e) {
                log.error("Error making key field accessible please use a public method and mark that as @Id", e);
                throw new IllegalArgumentException("Invalid class, DAO cannot be created.", e);
            }
        }
    }

    /**
     * Retrieves an entity associated with a specific key from the database and returns it wrapped in an Optional.
     *
     * This method allows you to retrieve an entity associated with a parent key and a specific key from the database.
     * It uses the superclass's `get` method for the retrieval operation and returns the retrieved entity wrapped in an Optional.
     * If the entity is found in the database, it is returned within the Optional; otherwise, an empty Optional is returned.
     *
     * @param parentKey A string representing the parent key that determines the shard for updating the entity.
     * @param key The specific key or identifier of the entity to retrieve.
     * @return An Optional containing the retrieved entity if found, or an empty Optional if the entity is not found.
     * @throws Exception If an error occurs during the retrieval process.
     */
    public Optional<T> get(String parentKey, Object key) throws Exception {
        return Optional.ofNullable(get(parentKey, key, t -> t));
    }


    public <U> U get(String parentKey, Object key, Function<T, U> function) {
        int shardId = shardCalculator.shardId(parentKey);
        RelationalDaoPriv dao = daos.get(shardId);
        return transactionExecutor.execute(dao.sessionFactory, true, dao::get, key, function,
                "get", shardId);
    }

    /**
     * Saves an entity to the database and returns the saved entity wrapped in an Optional.
     *
     * This method allows you to save an entity associated with a parent key to the database using the specified parent key.
     * It uses the superclass's `save` method for the saving operation and returns the saved entity wrapped in an Optional.
     * If the save operation is successful, the saved entity is returned; otherwise, an empty Optional is returned.
     *
     * @param parentKey A string representing the parent key that determines the shard for updating the entity.
     * @param entity The entity to be saved.
     * @return An Optional containing the saved entity if the save operation is successful, or an empty Optional if the save operation fails.
     * @throws Exception If an error occurs during the save operation.
     */
    public Optional<T> save(String parentKey, T entity) throws Exception {
        return Optional.ofNullable(save(parentKey, entity, t -> t));
    }

    public <U> U save(String parentKey, T entity, Function<T, U> handler) {
        int shardId = shardCalculator.shardId(parentKey);
        RelationalDaoPriv dao = daos.get(shardId);
        return transactionExecutor.execute(dao.sessionFactory, false, dao::save, entity, handler, "save",
                shardId);
    }


    /**
     * Saves a collection of entities associated to the database and returns a boolean indicating the success of the operation.
     *
     * This method allows you to save a collection of entities associated with a parent key to the database using the specified parent key.
     * It uses the superclass's `saveAll` method for the bulk saving operation and returns `true` if the operation is successful;
     * otherwise, it returns `false`.
     *
     * @param parentKey A string representing the parent key that determines the shard for updating the entity.
     * @param entities The collection of entities to be saved.
     * @return `true` if the bulk save operation is successful, or `false` if it fails.
     */
    public boolean saveAll(String parentKey, Collection<T> entities) {
        int shardId = shardCalculator.shardId(parentKey);
        RelationalDaoPriv dao = daos.get(shardId);
        return transactionExecutor.execute(dao.sessionFactory, false, dao::saveAll, entities, "saveAll", shardId);
    }

    <U> void save(LockedContext<U> context, T entity) {
        RelationalDaoPriv dao = daos.get(context.getShardId());
        transactionExecutor.execute(context.getSessionFactory(), false, dao::save, entity, t -> t, false,
                "save", context.getShardId());
    }

    <U> void save(LockedContext<U> context, T entity, Function<T, T> handler) {
        RelationalDaoPriv dao = daos.get(context.getShardId());
        transactionExecutor.execute(context.getSessionFactory(), false, dao::save, entity, handler, false,
                "save", context.getShardId());
    }



    /**
     * Updates an entity within a locked context using a specific ID and an updater function.
     *
     * This method updates an entity within a locked context using the provided ID and an updater function.
     * It is designed to work within the context of a locked transaction. The method delegates the update operation to
     * the DAO associated with the locked context and returns a boolean indicating the success of the update operation.
     *
     * @param context The locked context within which the entity is updated.
     * @param id The ID of the entity to be updated.
     * @param updater A function that takes the current entity and returns the updated entity.
     * @return `true` if the entity is successfully updated, or `false` if the update operation fails.
     */
    <U> boolean update(LockedContext<U> context, Object id, Function<T, T> updater) {
        RelationalDaoPriv dao = daos.get(context.getShardId());
        return update(context.getShardId(), context.getSessionFactory(), dao, id, updater, false);
    }

    /**
     * Updates entities matching the specified criteria within a locked context using an updater function.
     *
     * This method updates entities within a locked context based on the provided criteria and an updater function.
     * It allows you to specify a DetachedCriteria object to filter the entities to be updated. The method iterates
     * through the matched entities, applies the updater function to each entity, and performs the update operation.
     * The update process continues as long as the `updateNext` supplier returns `true` and there are more matching entities.
     *
     * @param context The locked context within which entities are updated.
     * @param criteria A DetachedCriteria object representing the criteria for filtering entities to update.
     * @param updater A function that takes an entity and returns the updated entity.
     * @param updateNext A BooleanSupplier that determines whether to continue updating the next entity in the result set.
     * @return `true` if at least one entity is successfully updated, or `false` if no entities are updated or the update process fails.
     * @throws RuntimeException If an error occurs during the update process.
     */
    <U> boolean update(LockedContext<U> context,
                       DetachedCriteria criteria,
                       Function<T, T> updater,
                       BooleanSupplier updateNext) {
        final RelationalDaoPriv dao = daos.get(context.getShardId());

        try {
            final ScrollParamPriv<T> scrollParam = ScrollParamPriv.<T>builder()
                    .criteria(criteria)
                    .build();

            return transactionExecutor.<ScrollableResults, ScrollParamPriv<T>, Boolean>execute(context.getSessionFactory(), true, dao::scroll, scrollParam, scrollableResults -> {
                boolean updateNextObject = true;
                try {
                    while (scrollableResults.next() && updateNextObject) {
                        final T entity = (T) scrollableResults.get(0);
                        if (null == entity) {
                            return false;
                        }
                        final T newEntity = updater.apply(entity);
                        if (null == newEntity) {
                            return false;
                        }
                        dao.update(entity, newEntity);
                        updateNextObject = updateNext.getAsBoolean();
                    }
                } finally {
                    scrollableResults.close();
                }
                return true;
            }, false, "update", context.getShardId());
        } catch (Exception e) {
            throw new RuntimeException("Error updating entity with scroll: " + criteria, e);
        }
    }

    /**
     * Updates entities within a specific shard based on a query, an update function, and scrolling through results.
     *
     * This method performs an update operation on a set of entities within a specific shard, as determined
     * by the provided context and shard ID. It uses the provided query criteria to select entities and
     * applies the provided updater function to update each entity. The scrolling mechanism allows for
     * processing a large number of results efficiently.
     *
     * @param context A LockedContext<U> object containing shard information and a session factory.
     * @param querySpec A QuerySpec object specifying the query criteria.
     * @param updater A function that takes an old entity, applies updates, and returns a new entity.
     * @param updateNext A BooleanSupplier that controls whether to continue updating the next entity.
     * @return true if all entities were successfully updated, false otherwise.
     * @throws RuntimeException If any exception occurs during the update operation with scrolling
     *                          or if criteria are not met during the process.
     */
    <U> boolean update(LockedContext<U> context,
                       QuerySpec<T, T> querySpec,
                       Function<T, T> updater,
                       BooleanSupplier updateNext) {
        final RelationalDaoPriv dao = daos.get(context.getShardId());

        try {
            final ScrollParamPriv<T> scrollParam = ScrollParamPriv.<T>builder()
                    .querySpec(querySpec)
                    .build();

            return transactionExecutor.<ScrollableResults, ScrollParamPriv<T>, Boolean>execute(context.getSessionFactory(), true, dao::scroll, scrollParam, scrollableResults -> {
                boolean updateNextObject = true;
                try {
                    while (scrollableResults.next() && updateNextObject) {
                        final T entity = (T) scrollableResults.get(0);
                        if (null == entity) {
                            return false;
                        }
                        final T newEntity = updater.apply(entity);
                        if (null == newEntity) {
                            return false;
                        }
                        dao.update(entity, newEntity);
                        updateNextObject = updateNext.getAsBoolean();
                    }
                } finally {
                    scrollableResults.close();
                }
                return true;
            }, false, "update", context.getShardId());
        } catch (Exception e) {
            throw new RuntimeException("Error updating entity with scroll: " + querySpec, e);
        }
    }


    <U> List<T> select(LookupDao.ReadOnlyContext<U> context, DetachedCriteria criteria, int start, int numResults) throws Exception {
        final RelationalDaoPriv dao = daos.get(context.getShardId());
        SelectParamPriv<T> selectParam = SelectParamPriv.<T>builder()
                .criteria(criteria)
                .start(start)
                .numRows(numResults)
                .build();
        return transactionExecutor.execute(context.getSessionFactory(), true, dao::select, selectParam, t -> t, false,
                "select", context.getShardId());
    }


    /**
     * Executes a database query within a specific shard, retrieving a list of query results.
     *
     * This method performs a database query on a specific shard, as determined by the provided
     * context and shard ID. It retrieves a specified number of results starting from a given index
     * based on the provided query criteria. The query results are returned as a list of entities.
     *
     * @param context A LookupDao.ReadOnlyContext object containing shard information and a session factory.
     * @param querySpec A QuerySpec object specifying the query criteria and projection.
     * @param start The starting index for the query results (pagination).
     * @param numResults The number of results to retrieve from the query (pagination).
     * @return A List of query results of type T.
     */
    <U> List<T> select(LookupDao.ReadOnlyContext<U> context, QuerySpec<T, T> querySpec, int start, int numResults) {
        final RelationalDaoPriv dao = daos.get(context.getShardId());
        SelectParamPriv<T> selectParam = SelectParamPriv.<T>builder()
                .querySpec(querySpec)
                .start(start)
                .numRows(numResults)
                .build();
        return transactionExecutor.execute(context.getSessionFactory(), true, dao::select, selectParam, t -> t, false,
                "select", context.getShardId());
    }

    public boolean update(String parentKey, Object id, Function<T, T> updater) {
        int shardId = shardCalculator.shardId(parentKey);
        RelationalDaoPriv dao = daos.get(shardId);
        return update(shardId, dao.sessionFactory, dao, id, updater, true);
    }

    public <U> U runInSession(String id, Function<Session, U> handler) {
        int shardId = shardCalculator.shardId(id);
        RelationalDaoPriv dao = daos.get(shardId);
        return transactionExecutor.execute(dao.sessionFactory, true, handler, true, "runInSession", shardId);
    }

    /**
     * Updates an entity within a specific shard based on its unique identifier and an update function.
     *
     * This private method is responsible for updating an entity within a specific shard, as identified
     * by the shard ID. It retrieves the entity with the provided unique identifier, applies the provided
     * updater function to update the entity, and saves the updated entity back to the database.
     *
     * @param shardId The identifier of the shard where the entity is located.
     * @param daoSessionFactory The SessionFactory associated with the DAO for database access.
     * @param dao The RelationalDaoPriv responsible for accessing the database.
     * @param id The unique identifier of the entity to be updated.
     * @param updater A function that takes the old entity, applies updates, and returns the new entity.
     * @param completeTransaction A boolean indicating whether the transaction should be completed after
     *                           the update operation.
     * @return true if the entity was successfully updated, false otherwise.
     * @throws RuntimeException If any exception occurs during the update operation or if the entity with
     *                          the given identifier is not found.
     */
    private boolean update(int shardId, SessionFactory daoSessionFactory, RelationalDaoPriv dao,
                           Object id, Function<T, T> updater, boolean completeTransaction) {
        try {
            return transactionExecutor.<T, Object, Boolean>execute(daoSessionFactory, true, dao::get, id, (T entity) -> {
                if (null == entity) {
                    return false;
                }
                T newEntity = updater.apply(entity);
                if (null == newEntity) {
                    return false;
                }
                dao.update(entity, newEntity);
                return true;
            }, completeTransaction, "update", shardId);
        } catch (Exception e) {
            throw new RuntimeException("Error updating entity: " + id, e);
        }
    }

    public boolean update(String parentKey, DetachedCriteria criteria, Function<T, T> updater) {
        int shardId = shardCalculator.shardId(parentKey);
        RelationalDaoPriv dao = daos.get(shardId);
        try {
            SelectParamPriv selectParam = SelectParamPriv.builder()
                    .criteria(criteria)
                    .start(0)
                    .numRows(1)
                    .build();
            return transactionExecutor.<List<T>, SelectParamPriv, Boolean>execute(dao.sessionFactory, true, dao::select, selectParam, (List<T> entityList) -> {
                if (entityList == null || entityList.isEmpty()) {
                    return false;
                }
                T oldEntity = entityList.get(0);
                if (null == oldEntity) {
                    return false;
                }
                T newEntity = updater.apply(oldEntity);
                if (null == newEntity) {
                    return false;
                }
                dao.update(oldEntity, newEntity);
                return true;
            }, "update", shardId);
        } catch (Exception e) {
            throw new RuntimeException("Error updating entity with criteria: " + criteria, e);
        }
    }


    /**
     * Updates a single entity within a specific shard based on query criteria and an update function.
     *
     * This method performs the operation of updating an entity within a specific shard, as determined
     * by the provided parent key and shard calculator. It uses the provided query criteria to select
     * the entity to be updated. If the entity is found, the provided updater function is applied to
     * update the entity, and the updated entity is saved.
     *
     * @param parentKey A string representing the parent key that determines the shard for updating
     *                  the entity.
     * @param querySpec A QuerySpec object specifying the criteria for selecting the entity to update.
     * @param updater A function that takes the old entity, applies updates, and returns the new entity.
     * @return true if the entity was successfully updated, false otherwise.
     * @throws RuntimeException If any exception occurs during the update operation or if criteria are
     *                          not met during the process.
     */
    public boolean update(String parentKey, QuerySpec<T, T> querySpec, Function<T, T> updater) {
        int shardId = shardCalculator.shardId(parentKey);
        RelationalDaoPriv dao = daos.get(shardId);
        try {
            SelectParamPriv<T> selectParam = SelectParamPriv.<T>builder()
                    .querySpec(querySpec)
                    .start(0)
                    .numRows(1)
                    .build();
            return transactionExecutor.<List<T>, SelectParamPriv, Boolean>execute(dao.sessionFactory, true, dao::select, selectParam, (List<T> entityList) -> {
                if (entityList == null || entityList.isEmpty()) {
                    return false;
                }
                T oldEntity = entityList.get(0);
                if (null == oldEntity) {
                    return false;
                }
                T newEntity = updater.apply(oldEntity);
                if (null == newEntity) {
                    return false;
                }
                dao.update(oldEntity, newEntity);
                return true;
            }, "update", shardId);
        } catch (Exception e) {
            throw new RuntimeException("Error updating entity with criteria: " + querySpec, e);
        }
    }


    public int updateUsingQuery(String parentKey, UpdateOperationMeta updateOperationMeta) {
        int shardId = shardCalculator.shardId(parentKey);
        val dao = daos.get(shardId);
        return transactionExecutor.execute(dao.sessionFactory, false, dao::update, updateOperationMeta,
                "updateUsingQuery", shardId);
    }

    public <U> int updateUsingQuery(LockedContext<U> lockedContext, UpdateOperationMeta updateOperationMeta) {
        val dao = daos.get(lockedContext.getShardId());
        return transactionExecutor.execute(lockedContext.getSessionFactory(), false, dao::update, updateOperationMeta, false,
                "updateUsingQuery", lockedContext.getShardId());
    }

    public LockedContext<T> lockAndGetExecutor(String parentKey, DetachedCriteria criteria) {
        int shardId = shardCalculator.shardId(parentKey);
        RelationalDaoPriv dao = daos.get(shardId);
        return new LockedContext<T>(shardId, dao.sessionFactory, () -> dao.getLockedForWrite(criteria),
                entityClass, shardInfoProvider, observer);
    }


    /**
     * Acquires a write lock on entities matching the provided query criteria within a specific shard
     * and returns a LockedContext for further operations.
     *
     * This method performs the operation of acquiring a write lock on entities within a specific shard,
     * as determined by the provided parent key and shard calculator. It uses the provided query criteria
     * to select the entities to be locked. It then constructs and returns a LockedContext object that
     * encapsulates the shard information and allows for subsequent operations on the locked entities.
     *
     * @param parentKey A string representing the parent key that determines the shard for
     *                  acquiring the write lock.
     * @param querySpec A QuerySpec object specifying the criteria for selecting entities to lock.
     * @return A LockedContext<T> object containing shard information and the locked entities,
     *         enabling further operations on the locked entities within the specified shard.
     */
    public LockedContext<T> lockAndGetExecutor(String parentKey, QuerySpec<T, T> querySpec) {
        int shardId = shardCalculator.shardId(parentKey);
        RelationalDaoPriv dao = daos.get(shardId);
        return new LockedContext<T>(shardId, dao.sessionFactory, () -> dao.getLockedForWrite(querySpec),
                entityClass, shardInfoProvider, observer);
    }

    /**
     * Saves an entity within a specific shard and returns a LockedContext for further operations.
     *
     * This method performs the operation of saving an entity within a specific shard, as determined by
     * the provided parent key and shard calculator. It then constructs and returns a LockedContext
     * object that encapsulates the shard information and allows for subsequent operations on the
     * saved entity.
     *
     * @param parentKey A string representing the parent key that determines the shard for
     *                  saving the entity.
     * @param entity The entity of type T to be saved.
     * @return A LockedContext<T> object containing shard information and the saved entity,
     *         enabling further operations on the entity within the specified shard.
     */
    public LockedContext<T> saveAndGetExecutor(String parentKey, T entity) {
        int shardId = shardCalculator.shardId(parentKey);
        RelationalDaoPriv dao = daos.get(shardId);
        return new LockedContext<T>(shardId, dao.sessionFactory, dao::save, entity,
                entityClass, shardInfoProvider, observer);
    }

    <U> boolean createOrUpdate(LockedContext<U> context,
                               DetachedCriteria criteria,
                               Function<T, T> updater,
                               Supplier<T> entityGenerator) {
        final RelationalDaoPriv dao = daos.get(context.getShardId());

        try {
            final SelectParamPriv<T> selectParam = SelectParamPriv.<T>builder()
                    .criteria(criteria)
                    .start(0)
                    .numRows(1)
                    .build();

            return transactionExecutor.<List<T>, SelectParamPriv, Boolean>execute(context.getSessionFactory(), true, dao::select, selectParam, (List<T> entityList) -> {
                if (entityList == null || entityList.isEmpty()) {
                    Preconditions.checkNotNull(entityGenerator, "Entity generator can't be null");
                    final T newEntity = entityGenerator.get();
                    Preconditions.checkNotNull(newEntity, "Generated entity can't be null");
                    dao.save(newEntity);
                    return true;
                }

                final T oldEntity = entityList.get(0);
                if (null == oldEntity) {
                    return false;
                }
                final T newEntity = updater.apply(oldEntity);
                if (null == newEntity) {
                    return false;
                }
                dao.update(oldEntity, newEntity);
                return true;
            }, false, "createOrUpdate", context.getShardId());
        } catch (Exception e) {
            throw new RuntimeException("Error updating entity with criteria: " + criteria, e);
        }
    }


    /**
     * Creates or updates a single entity within a specific shard based on a query and update logic.
     *
     * This method performs a create or update operation on a single entity within a specific shard,
     * as determined by the provided LockedContext and shard ID. It uses the provided query to check
     * for the existence of an entity, and based on the result:
     * - If no entity is found, it generates a new entity using the entity generator and saves it.
     * - If an entity is found, it applies the provided updater function to update the entity.
     *
     * @param context A LockedContext object containing information about the shard and session factory.
     * @param querySpec A QuerySpec object specifying the criteria for selecting an entity.
     * @param updater A function that takes an old entity, applies updates, and returns a new entity.
     * @param entityGenerator A supplier function for generating a new entity if none exists.
     * @param <U> The type of result associated with the LockedContext.
     * @return true if the entity was successfully created or updated, false otherwise.
     * @throws RuntimeException If any exception occurs during the create/update operation or if criteria
     *                          are not met during the process.
     */
    <U> boolean createOrUpdate(LockedContext<U> context,
                               QuerySpec<T, T> querySpec,
                               Function<T, T> updater,
                               Supplier<T> entityGenerator) {
        final RelationalDaoPriv dao = daos.get(context.getShardId());

        try {
            final SelectParamPriv<T> selectParam = SelectParamPriv.<T>builder()
                    .querySpec(querySpec)
                    .start(0)
                    .numRows(1)
                    .build();

            return transactionExecutor.<List<T>, SelectParamPriv<T>, Boolean>execute(context.getSessionFactory(), true, dao::select, selectParam, (List<T> entityList) -> {
                if (entityList == null || entityList.isEmpty()) {
                    Preconditions.checkNotNull(entityGenerator, "Entity generator can't be null");
                    final T newEntity = entityGenerator.get();
                    Preconditions.checkNotNull(newEntity, "Generated entity can't be null");
                    dao.save(newEntity);
                    return true;
                }

                final T oldEntity = entityList.get(0);
                if (null == oldEntity) {
                    return false;
                }
                final T newEntity = updater.apply(oldEntity);
                if (null == newEntity) {
                    return false;
                }
                dao.update(oldEntity, newEntity);
                return true;
            }, false, "createOrUpdate", context.getShardId());
        } catch (Exception e) {
            throw new RuntimeException("Error updating entity with criteria: " + querySpec, e);
        }
    }


    public boolean updateAll(String parentKey, int start, int numRows, DetachedCriteria criteria, Function<T, T> updater) {
        int shardId = shardCalculator.shardId(parentKey);
        RelationalDaoPriv dao = daos.get(shardId);
        try {
            SelectParamPriv selectParam = SelectParamPriv.builder()
                    .criteria(criteria)
                    .start(start)
                    .numRows(numRows)
                    .build();
            return transactionExecutor.<List<T>, SelectParamPriv, Boolean>execute(dao.sessionFactory, true, dao::select, selectParam, entityList -> {
                if (entityList == null || entityList.isEmpty()) {
                    return false;
                }
                for (T oldEntity : entityList) {
                    if (null == oldEntity) {
                        return false;
                    }
                    T newEntity = updater.apply(oldEntity);
                    if (null == newEntity) {
                        return false;
                    }
                    dao.update(oldEntity, newEntity);
                }
                return true;
            }, "updateAll", shardId);
        } catch (Exception e) {
            throw new RuntimeException("Error updating entity with criteria: " + criteria, e);
        }
    }


    /**
     * Updates a batch of entities within a specific shard based on a query and an update function.
     *
     * This method performs an update operation on a batch of entities within a specific shard,
     * as determined by the provided parent key and shard calculator. It retrieves a specified
     * number of entities that match the criteria defined in the provided QuerySpec object, applies
     * the provided updater function to each entity, and updates the entities in the database.
     *
     * @param parentKey A string representing the parent key that determines the shard for
     *                  the update operation.
     * @param start The starting index for selecting entities to update (pagination).
     * @param numResults The number of entities to retrieve and update (pagination).
     * @param querySpec A QuerySpec object specifying the criteria for selecting entities to update.
     * @param updater A function that takes an old entity, applies updates, and returns a new entity.
     * @return true if all entities were successfully updated, false otherwise.
     * @throws RuntimeException If any exception occurs during the update operation or if
     *                          criteria are not met, it is wrapped in a RuntimeException and
     *                          propagated.
     */
    public boolean updateAll(String parentKey, int start, int numResults, QuerySpec<T, T> querySpec, Function<T, T> updater) {
        int shardId = shardCalculator.shardId(parentKey);
        RelationalDaoPriv dao = daos.get(shardId);
        try {
            SelectParamPriv<T> selectParam = SelectParamPriv.<T>builder()
                    .querySpec(querySpec)
                    .start(start)
                    .numRows(numResults)
                    .build();
            return transactionExecutor.<List<T>, SelectParamPriv, Boolean>execute(dao.sessionFactory, true, dao::select, selectParam, entityList -> {
                if (entityList == null || entityList.isEmpty()) {
                    return false;
                }
                for (T oldEntity : entityList) {
                    if (null == oldEntity) {
                        return false;
                    }
                    T newEntity = updater.apply(oldEntity);
                    if (null == newEntity) {
                        return false;
                    }
                    dao.update(oldEntity, newEntity);
                }
                return true;
            }, "updateAll", shardId);
        } catch (Exception e) {
            throw new RuntimeException("Error updating entity with criteria: " + querySpec, e);
        }
    }

    public List<T> select(String parentKey, DetachedCriteria criteria, int start, int numResults) throws Exception {
        return select(parentKey, criteria, start, numResults, t -> t);
    }

    /**
     * Executes a database query within a specific shard, retrieving a list of query results.
     *
     * This method performs a database query on a specific shard, as determined by the provided
     * parent key and shard calculator. It retrieves a specified number of results starting from
     * a given index and returns them as a list. The query results are processed using a default
     * identity function.
     *
     * @param parentKey A string representing the parent key that determines the shard for
     *                  the query.
     * @param querySpec A QuerySpec object specifying the query criteria and projection.
     * @param start The starting index for the query results (pagination).
     * @param numResults The number of results to retrieve from the query (pagination).
     * @return A List of query results of type T.
     * @throws Exception If any exception occurs during the query execution, it is propagated.
     */
    public List<T> select(String parentKey, QuerySpec<T, T> querySpec, int start, int numResults) throws Exception {
        return select(parentKey, querySpec, start, numResults, t -> t);
    }

    public <U> U select(String parentKey, DetachedCriteria criteria, int start, int numResults, Function<List<T>, U> handler) throws Exception {
        int shardId = shardCalculator.shardId(parentKey);
        RelationalDaoPriv dao = daos.get(shardId);
        SelectParamPriv<T> selectParam = SelectParamPriv.<T>builder()
                .criteria(criteria)
                .start(start)
                .numRows(numResults)
                .build();
        return transactionExecutor.execute(dao.sessionFactory, true, dao::select, selectParam, handler, "select", shardId);
    }

    /**
     * Executes a database query within a specific shard, retrieving and processing the results.
     *
     * This method performs a database query on a specific shard, as determined by the provided
     * parent key and shard calculator. It retrieves a specified number of results starting from
     * a given index, processes the results using the provided handler function, and returns the
     * result of the handler function.
     *
     * @param parentKey A string representing the parent key that determines the shard for
     *                  the query.
     * @param querySpec A QuerySpec object specifying the query criteria and projection.
     * @param start The starting index for the query results (pagination).
     * @param numResults The number of results to retrieve from the query (pagination).
     * @param handler A function that processes the list of query results and returns a result
     *                of type U.
     * @param <U> The type of result to be returned by the handler function.
     * @return The result of applying the handler function to the query results.
     * @throws Exception If any exception occurs during the query execution or result
     *                   processing, it is propagated.
     */
    public <U> U select(String parentKey, QuerySpec<T, T> querySpec, int start, int numResults, Function<List<T>, U> handler) throws Exception {
        int shardId = shardCalculator.shardId(parentKey);
        RelationalDaoPriv dao = daos.get(shardId);
        SelectParamPriv<T> selectParam = SelectParamPriv.<T>builder()
                .querySpec(querySpec)
                .start(start)
                .numRows(numResults)
                .build();
        return transactionExecutor.execute(dao.sessionFactory, true, dao::select, selectParam, handler, "select", shardId);
    }

    public long count(String parentKey, DetachedCriteria criteria) {
        int shardId = shardCalculator.shardId(parentKey);
        RelationalDaoPriv dao = daos.get(shardId);
        return transactionExecutor.<Long, DetachedCriteria>execute(dao.sessionFactory, true, dao::count, criteria,
                "count", shardId);
    }

    /**
     * Counts the number of records matching a specified query in a given shard
     *
     * This method calculates and returns the count of records that match the criteria defined
     * in the provided QuerySpec object. The counting operation is performed within the shard
     * associated with the provided parent key, as determined by the shard calculator.
     *
     * @param parentKey A string representing the parent key that determines the shard for
     *                  the counting operation.
     * @param querySpec A QuerySpec object specifying the query criteria for counting records.
     * @return The total count of records matching the specified query criteria.
     * @throws RuntimeException If any exception occurs during the counting operation, it is
     *                          wrapped in a RuntimeException and propagated.
     */
    public long count(String parentKey, QuerySpec<T, Long> querySpec) {
        val shardId = shardCalculator.shardId(parentKey);
        val dao = daos.get(shardId);
        return transactionExecutor.<Long, QuerySpec<T, Long>>execute(dao.sessionFactory, true, dao::count, querySpec,
                "count", shardId);
    }


    public boolean exists(String parentKey, Object key) {
        val shardId = shardCalculator.shardId(parentKey);
        val dao = daos.get(shardId);
        Optional<T> result = transactionExecutor.<T, Object>executeAndResolve(dao.sessionFactory, true, dao::get, key,
                "exists", shardId);
        return result.isPresent();
    }

    /**
     * Queries using the specified criteria across all shards and returns the counts of rows satisfying the criteria.
     * <b>Note:</b> This method runs the query serially and it's usage is not recommended.
     *
     * @param criteria The select criteria
     * @return List of counts in each shard
     */
    public List<Long> countScatterGather(DetachedCriteria criteria) {
        return IntStream.range(0, daos.size())
                .mapToObj(shardId -> {
                    val dao = daos.get(shardId);
                    try {
                        return transactionExecutor.execute(dao.sessionFactory, true, dao::count, criteria,
                                "countScatterGather", shardId);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }).collect(Collectors.toList());
    }

    public List<T> scatterGather(DetachedCriteria criteria, int start, int numRows) {
        return IntStream.range(0, daos.size())
                .mapToObj(shardId -> {
                    val dao = daos.get(shardId);
                    try {
                        SelectParamPriv<T> selectParam = SelectParamPriv.<T>builder()
                                .criteria(criteria)
                                .start(start)
                                .numRows(numRows)
                                .build();
                        return transactionExecutor.execute(dao.sessionFactory, true, dao::select, selectParam,
                                "scatterGather", shardId);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }).flatMap(Collection::stream).collect(Collectors.toList());
    }

    /**
     * Executes a scatter-gather operation across multiple Data Access Objects (DAOs) in a serial manner
     *
     * @param querySpec A QuerySpec object specifying the query to execute.
     * @param start The starting index for the query results (pagination).
     * @param numRows The number of rows to retrieve in the query results (pagination).
     * @return A List of type T containing the aggregated query results from all shards.
     * @throws RuntimeException If any exception occurs during the execution of queries on
     *                          individual shards, it is wrapped in a RuntimeException and
     *                          propagated.
     */
    public List<T> scatterGather(QuerySpec<T, T> querySpec, int start, int numRows) {
        return IntStream.range(0, daos.size())
                .mapToObj(shardId -> {
                    val dao = daos.get(shardId);
                    try {
                        SelectParamPriv<T> selectParam = SelectParamPriv.<T>builder()
                                .querySpec(querySpec)
                                .start(start)
                                .numRows(numRows)
                                .build();
                        return transactionExecutor.execute(dao.sessionFactory, true, dao::select, selectParam,
                                "scatterGather", shardId);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }).flatMap(Collection::stream).collect(Collectors.toList());
    }

    protected Field getKeyField() {
        return this.keyField;
    }

    private Query<T> createQuery(final Session session,
                                 final Class<T> entityClass,
                                 final QuerySpec<T, T> querySpec) {
        CriteriaBuilder builder = session.getCriteriaBuilder();
        CriteriaQuery<T> criteria = builder.createQuery(entityClass);
        Root<T> root = criteria.from(entityClass);
        querySpec.apply(root, criteria, builder);
        return session.createQuery(criteria);
    }

}

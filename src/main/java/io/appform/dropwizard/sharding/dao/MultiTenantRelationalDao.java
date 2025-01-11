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
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import io.appform.dropwizard.sharding.ShardInfoProvider;
import io.appform.dropwizard.sharding.config.ShardingBundleOptions;
import io.appform.dropwizard.sharding.dao.operations.Count;
import io.appform.dropwizard.sharding.dao.operations.CountByQuerySpec;
import io.appform.dropwizard.sharding.dao.operations.Get;
import io.appform.dropwizard.sharding.dao.operations.GetAndUpdate;
import io.appform.dropwizard.sharding.dao.operations.OpContext;
import io.appform.dropwizard.sharding.dao.operations.RunInSession;
import io.appform.dropwizard.sharding.dao.operations.RunWithCriteria;
import io.appform.dropwizard.sharding.dao.operations.Save;
import io.appform.dropwizard.sharding.dao.operations.SaveAll;
import io.appform.dropwizard.sharding.dao.operations.ScrollParam;
import io.appform.dropwizard.sharding.dao.operations.Select;
import io.appform.dropwizard.sharding.dao.operations.SelectAndUpdate;
import io.appform.dropwizard.sharding.dao.operations.SelectParam;
import io.appform.dropwizard.sharding.dao.operations.UpdateAll;
import io.appform.dropwizard.sharding.dao.operations.UpdateByQuery;
import io.appform.dropwizard.sharding.dao.operations.UpdateWithScroll;
import io.appform.dropwizard.sharding.dao.operations.relationaldao.CreateOrUpdate;
import io.appform.dropwizard.sharding.dao.operations.relationaldao.CreateOrUpdateInLockedContext;
import io.appform.dropwizard.sharding.dao.operations.relationaldao.readonlycontext.ReadOnlyForRelationalDao;
import io.appform.dropwizard.sharding.execution.DaoType;
import io.appform.dropwizard.sharding.execution.TransactionExecutionContext;
import io.appform.dropwizard.sharding.execution.TransactionExecutor;
import io.appform.dropwizard.sharding.observers.TransactionObserver;
import io.appform.dropwizard.sharding.query.QuerySpec;
import io.appform.dropwizard.sharding.scroll.FieldComparator;
import io.appform.dropwizard.sharding.scroll.ScrollPointer;
import io.appform.dropwizard.sharding.scroll.ScrollResult;
import io.appform.dropwizard.sharding.scroll.ScrollResultItem;
import io.appform.dropwizard.sharding.utils.InternalUtils;
import io.appform.dropwizard.sharding.utils.ShardCalculator;
import io.appform.dropwizard.sharding.utils.TransactionHandler;
import io.dropwizard.hibernate.AbstractDAO;
import lombok.Builder;
import lombok.Getter;
import lombok.NonNull;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import lombok.val;
import org.apache.commons.beanutils.BeanUtils;
import org.apache.commons.beanutils.PropertyUtils;
import org.apache.commons.lang3.reflect.FieldUtils;
import org.hibernate.Criteria;
import org.hibernate.LockMode;
import org.hibernate.ScrollMode;
import org.hibernate.ScrollableResults;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.criterion.DetachedCriteria;
import org.hibernate.criterion.Order;
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
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static io.appform.dropwizard.sharding.query.QueryUtils.equalityFilter;

/**
 * A dao used to work with entities related to a parent shard. The parent may or maynot be
 * physically present. A murmur 128 hash of the string parent key is used to route the save and
 * retrieve calls from the proper shard.
 */
@Slf4j
@SuppressWarnings({"unchecked", "UnusedReturnValue"})
public class MultiTenantRelationalDao<T> implements ShardedDao<T> {

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

        T get(final Object lookupKey) {
            val q = createQuery(currentSession(),
                    entityClass,
                    (queryRoot, query, criteriaBuilder) ->
                            query.where(criteriaBuilder.equal(queryRoot.get(keyField.getName()),
                                    lookupKey)));
            return uniqueResult(q.setLockMode(LockModeType.NONE));
        }

        DetachedCriteria getDetachedCriteria(Object lookupKey) {
            return DetachedCriteria.forClass(entityClass).add(
                            Restrictions.eq(keyField.getName(), lookupKey))
                    .setLockMode(LockMode.READ);
        }

        /**
         * Reads all rows matching the {@code querySpec} in locked mode. This is equivalent to <i>for
         * update</i> semantics during database fetch
         *
         * @param querySpec QuerySpec to be used. This should contain all JPA filters which need to be
         *                  applied for row selection
         */
        T getLockedForWrite(final QuerySpec<T, T> querySpec) {
            val q = InternalUtils.createQuery(currentSession(), entityClass, querySpec);
            return uniqueResult(q.setLockMode(LockModeType.PESSIMISTIC_WRITE));
        }

        T get(DetachedCriteria criteria) {
            return uniqueResult(criteria.getExecutableCriteria(currentSession()));
        }

        T getLocked(Object lookupKey, UnaryOperator<Criteria> criteriaUpdater, LockMode lockMode) {
            Criteria criteria = criteriaUpdater.apply(currentSession()
                    .createCriteria(entityClass)
                    .add(Restrictions.eq(keyField.getName(), lookupKey))
                    .setLockMode(lockMode));
            return uniqueResult(criteria);
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
            currentSession().evict(oldEntity); //Detach ... otherwise update is a no-op
            currentSession().update(entity);
        }

        List<T> select(SelectParam selectParam) {
            if (selectParam.criteria != null) {
                val criteria = selectParam.criteria.getExecutableCriteria(currentSession());
                if (-1 != selectParam.getStart()) {
                    criteria.setFirstResult(selectParam.start);
                }
                if (-1 != selectParam.getNumRows()) {
                    criteria.setMaxResults(selectParam.numRows);
                }
                return list(criteria);
            }
            val query = InternalUtils.createQuery(currentSession(), entityClass, selectParam.querySpec);
            if (-1 != selectParam.getStart()) {
                query.setFirstResult(selectParam.start);
            }
            if (-1 != selectParam.getNumRows()) {
                query.setMaxResults(selectParam.numRows);
            }
            return list(query);
        }

        ScrollableResults scroll(ScrollParam<T> scrollDetails) {
            if (scrollDetails.getCriteria() != null) {
                final Criteria criteria = scrollDetails.getCriteria()
                        .getExecutableCriteria(currentSession());
                return criteria.scroll(ScrollMode.FORWARD_ONLY);
            }
            return InternalUtils.createQuery(currentSession(), entityClass, scrollDetails.getQuerySpec())
                    .scroll(ScrollMode.FORWARD_ONLY);
        }

        /**
         * Run a query inside this shard and return the matching list.
         *
         * @param criteria selection criteria to be applied.
         * @return List of elements or empty list if none found
         */
        @SuppressWarnings("rawtypes")
        List run(DetachedCriteria criteria) {
            return criteria.getExecutableCriteria(currentSession())
                    .list();
        }

        long count(final DetachedCriteria criteria) {
            return (long) criteria.getExecutableCriteria(currentSession())
                    .setProjection(Projections.rowCount())
                    .uniqueResult();
        }

        /**
         * Return count of rows matching the {@code querySpec}
         *
         * @param querySpec QuerySpec to be used. This should contain all JPA filters which need to be
         *                  applied for row selection
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
            val query = currentSession().createNamedQuery(updateOperationMeta.getQueryName());
            updateOperationMeta.getParams().forEach(query::setParameter);
            return query.executeUpdate();
        }

    }

    private final Map<String, List<RelationalDaoPriv>> daos = Maps.newHashMap();
    @Getter
    private final Class<T> entityClass;
    @Getter
    private final ShardCalculator<String> shardCalculator;
    @Getter
    private final Map<String, ShardingBundleOptions> shardingOptions;
    private final Field keyField;

    private final Map<String, TransactionExecutor> transactionExecutor = Maps.newHashMap();
    private final Map<String, ShardInfoProvider> shardInfoProviders;
    private final TransactionObserver observer;

    /**
     * Constructs a RelationalDao instance for managing entities across multiple shards. This
     * constructor initializes a RelationalDao instance for working with entities of the specified
     * class distributed across multiple shards. It requires a list of session factories, a shard
     * calculator, a shard information provider, and a transaction observer. The entity class must
     * designate one field as the primary key using the `@Id` annotation.
     *
     * @param sessionFactories   A list of SessionFactory instances for database access across
     *                           shards.
     * @param entityClass        The Class representing the type of entities managed by this
     *                           RelationalDao.
     * @param shardCalculator    A ShardCalculator instance used to determine the shard for each
     *                           operation.
     * @param shardInfoProviders A ShardInfoProvider for retrieving shard information.
     * @param observer           A TransactionObserver for monitoring transaction events.
     * @throws IllegalArgumentException If the entity class does not have exactly one field designated
     *                                  as @Id, if the designated key field is not accessible, or if
     *                                  it is not of type String.
     */
    public MultiTenantRelationalDao(
            Map<String, List<SessionFactory>> sessionFactories,
            Class<T> entityClass,
            ShardCalculator<String> shardCalculator,
            Map<String, ShardingBundleOptions> shardingOptions,
            final Map<String, ShardInfoProvider> shardInfoProviders,
            final TransactionObserver observer) {
        this.shardCalculator = shardCalculator;
        this.shardingOptions = shardingOptions;
        sessionFactories.forEach((tenantId, factories) -> daos.put(tenantId,
                factories.stream().map(RelationalDaoPriv::new).collect(Collectors.toList())));
        this.entityClass = entityClass;
        this.shardInfoProviders = shardInfoProviders;
        this.observer = observer;
        shardInfoProviders.forEach((tenantId, shardInfoProvider) -> {
            this.transactionExecutor.put(tenantId,
                    new TransactionExecutor(shardInfoProvider, DaoType.RELATIONAL, entityClass, observer));
        });
        Field[] fields = FieldUtils.getFieldsWithAnnotation(entityClass, Id.class);
        Preconditions.checkArgument(fields.length != 0, "A field needs to be designated as @Id");
        Preconditions.checkArgument(fields.length == 1, "Only one field can be designated as @Id");
        keyField = fields[0];
        if (!keyField.isAccessible()) {
            try {
                keyField.setAccessible(true);
            } catch (SecurityException e) {
                log.error(
                        "Error making key field accessible please use a public method and mark that as @Id", e);
                throw new IllegalArgumentException("Invalid class, DAO cannot be created.", e);
            }
        }
    }

    /**
     * Retrieves an entity associated with a specific key from the database and returns it wrapped in
     * an Optional. This method allows you to retrieve an entity associated with a parent key and a
     * specific key from the database. It uses the superclass's `get` method for the retrieval
     * operation and returns the retrieved entity wrapped in an Optional. If the entity is found in
     * the database, it is returned within the Optional; otherwise, an empty Optional is returned.
     *
     * @param tenantId  The tenant ID associated with the entity.
     * @param parentKey A string representing the parent key that determines the shard for updating
     *                  the entity.
     * @param key       The specific key or identifier of the entity to retrieve.
     * @return An Optional containing the retrieved entity if found, or an empty Optional if the
     * entity is not found.
     * @throws Exception If an error occurs during the retrieval process.
     */
    public Optional<T> get(String tenantId, String parentKey, Object key) throws Exception {
        return Optional.ofNullable(get(tenantId, parentKey, key, t -> t));
    }


    public <U> U get(String tenantId, String parentKey, Object key, Function<T, U> function) {
        Preconditions.checkArgument(daos.containsKey(tenantId), "Unknown tenant: " + tenantId);
        int shardId = shardCalculator.shardId(tenantId, parentKey);
        RelationalDaoPriv dao = daos.get(tenantId).get(shardId);
        val opContext = Get.<T, U>builder()
                .getter(dao::get)
                .criteria(dao.getDetachedCriteria(key))
                .afterGet(function).build();
        return transactionExecutor.get(tenantId)
                .execute(dao.sessionFactory, true, "get", opContext, shardId);
    }

    /**
     * Saves an entity to the database and returns the saved entity wrapped in an Optional. This
     * method allows you to save an entity associated with a parent key to the database using the
     * specified parent key. It uses the superclass's `save` method for the saving operation and
     * returns the saved entity wrapped in an Optional. If the save operation is successful, the saved
     * entity is returned; otherwise, an empty Optional is returned.
     *
     * @param tenantId  The tenant ID associated with the entity.
     * @param parentKey A string representing the parent key that determines the shard for updating
     *                  the entity.
     * @param entity    The entity to be saved.
     * @return An Optional containing the saved entity if the save operation is successful, or an
     * empty Optional if the save operation fails.
     * @throws Exception If an error occurs during the save operation.
     */
    public Optional<T> save(String tenantId, String parentKey, T entity) throws Exception {
        return Optional.ofNullable(save(tenantId, parentKey, entity, t -> t));
    }

    public <U> U save(String tenantId, String parentKey, T entity, Function<T, U> handler) {
        Preconditions.checkArgument(daos.containsKey(tenantId), "Unknown tenant: " + tenantId);
        int shardId = shardCalculator.shardId(tenantId, parentKey);
        RelationalDaoPriv dao = daos.get(tenantId).get(shardId);
        val opContext = Save.<T, U>builder()
                .saver(dao::save).entity(entity).afterSave(handler).build();
        return transactionExecutor.get(tenantId).execute(dao.sessionFactory, false, "save", opContext,
                shardId);
    }


    /**
     * Saves a collection of entities associated to the database and returns a boolean indicating the
     * success of the operation.
     * <p>
     * This method allows you to save a collection of entities associated with a parent key to the
     * database using the specified parent key. It uses the superclass's `saveAll` method for the bulk
     * saving operation and returns `true` if the operation is successful; otherwise, it returns
     * `false`.
     *
     * @param tenantId  The tenant ID associated with the entity.
     * @param parentKey A string representing the parent key that determines the shard for updating
     *                  the entity.
     * @param entities  The collection of entities to be saved.
     * @return `true` if the bulk save operation is successful, or `false` if it fails.
     */
    public boolean saveAll(String tenantId, String parentKey, Collection<T> entities) {
        Preconditions.checkArgument(daos.containsKey(tenantId), "Unknown tenant: " + tenantId);
        int shardId = shardCalculator.shardId(tenantId, parentKey);
        RelationalDaoPriv dao = daos.get(tenantId).get(shardId);
        val opContext = SaveAll.<T>builder().saver(dao::saveAll).entities(entities).build();
        return transactionExecutor.get(tenantId)
                .execute(dao.sessionFactory, false, "saveAll", opContext, shardId);
    }

    public Optional<T> createOrUpdate(String tenantId,
                                      final String parentKey,
                                      final DetachedCriteria selectionCriteria,
                                      final UnaryOperator<T> updater,
                                      final Supplier<T> entityGenerator) {
        Preconditions.checkArgument(daos.containsKey(tenantId), "Unknown tenant: " + tenantId);
        int shardId = shardCalculator.shardId(tenantId, parentKey);
        RelationalDaoPriv dao = daos.get(tenantId).get(shardId);
        val opContext = CreateOrUpdate.<T>builder()
                .criteria(selectionCriteria)
                .getLockedForWrite(dao::getLockedForWrite)
                .entityGenerator(entityGenerator)
                .saver(dao::save)
                .mutator(updater)
                .updater(dao::update)
                .getter(dao::get)
                .build();
        return Optional.of(transactionExecutor.get(tenantId).execute(
                dao.sessionFactory,
                false,
                "createOrUpdate",
                opContext,
                shardId));
    }

    public <U> void save(LockedContext<U> context, T entity) {
        val tenantId = context.getTenantId();
        RelationalDaoPriv dao = daos.get(tenantId).get(context.getShardId());
        val opContext = Save.<T, T>builder().entity(entity).saver(dao::save).build();
        transactionExecutor.get(tenantId).execute(context.getSessionFactory(), false, "save", opContext,
                context.getShardId(), false);
    }

    <U> void save(LockedContext<U> context, T entity, Function<T, T> handler) {
        val tenantId = context.getTenantId();
        RelationalDaoPriv dao = daos.get(tenantId).get(context.getShardId());
        val opContext = Save.<T, T>builder().entity(entity).saver(dao::save).afterSave(handler).build();
        transactionExecutor.get(tenantId).execute(context.getSessionFactory(), false, "save", opContext,
                context.getShardId(), false);
    }


    /**
     * Updates an entity within a locked context using a specific ID and an updater function.
     * <p>
     * This method updates an entity within a locked context using the provided ID and an updater
     * function. It is designed to work within the context of a locked transaction. The method
     * delegates the update operation to the DAO associated with the locked context and returns a
     * boolean indicating the success of the update operation.
     *
     * @param context The locked context within which the entity is updated.
     * @param id      The ID of the entity to be updated.
     * @param updater A function that takes the current entity and returns the updated entity.
     * @return `true` if the entity is successfully updated, or `false` if the update operation fails.
     */
    <U> boolean update(LockedContext<U> context, Object id, Function<T, T> updater) {
        val tenantId = context.getTenantId();
        RelationalDaoPriv dao = daos.get(tenantId).get(context.getShardId());
        return update(tenantId, context.getShardId(), context.getSessionFactory(), dao, id, updater, false);
    }

    /**
     * Updates entities matching the specified criteria within a locked context using an updater
     * function.
     * <p>
     * This method updates entities within a locked context based on the provided criteria and an
     * updater function. It allows you to specify a DetachedCriteria object to filter the entities to
     * be updated. The method iterates through the matched entities, applies the updater function to
     * each entity, and performs the update operation. The update process continues as long as the
     * `updateNext` supplier returns `true` and there are more matching entities.
     *
     * @param context    The locked context within which entities are updated.
     * @param criteria   A DetachedCriteria object representing the criteria for filtering entities to
     *                   update.
     * @param updater    A function that takes an entity and returns the updated entity.
     * @param updateNext A BooleanSupplier that determines whether to continue updating the next
     *                   entity in the result set.
     * @return `true` if at least one entity is successfully updated, or `false` if no entities are
     * updated or the update process fails.
     * @throws RuntimeException If an error occurs during the update process.
     */
    <U> boolean update(
            LockedContext<U> context,
            DetachedCriteria criteria,
            UnaryOperator<T> updater,
            BooleanSupplier updateNext) {
        val tenantId = context.getTenantId();
        val dao = daos.get(tenantId).get(context.getShardId());
        val opContext = UpdateWithScroll.<T>builder()
                .scroll(dao::scroll)
                .scrollParam(ScrollParam.<T>builder()
                        .criteria(criteria)
                        .build())
                .mutator(updater)
                .updater(dao::update)
                .updateNext(updateNext)
                .build();
        try {
            return transactionExecutor.get(tenantId).execute(context.getSessionFactory(),
                    true,
                    "update",
                    opContext,
                    context.getShardId(), false);
        } catch (Exception e) {
            throw new RuntimeException("Error updating entity with scroll: " + criteria, e);
        }
    }

    /**
     * Updates entities within a specific shard based on a query, an update function, and scrolling
     * through results.
     * <p>
     * This method performs an update operation on a set of entities within a specific shard, as
     * determined by the provided context and shard ID. It uses the provided query criteria to select
     * entities and applies the provided updater function to update each entity. The scrolling
     * mechanism allows for processing a large number of results efficiently.
     *
     * @param context    A LockedContext<U> object containing shard information and a session
     *                   factory.
     * @param querySpec  A QuerySpec object specifying the query criteria.
     * @param updater    A function that takes an old entity, applies updates, and returns a new
     *                   entity.
     * @param updateNext A BooleanSupplier that controls whether to continue updating the next
     *                   entity.
     * @return true if all entities were successfully updated, false otherwise.
     * @throws RuntimeException If any exception occurs during the update operation with scrolling or
     *                          if criteria are not met during the process.
     */
    <U> boolean update(
            LockedContext<U> context,
            QuerySpec<T, T> querySpec,
            UnaryOperator<T> updater,
            BooleanSupplier updateNext) {
        val tenantId = context.getTenantId();
        val dao = daos.get(tenantId).get(context.getShardId());
        val opContext = UpdateWithScroll.<T>builder()
                .scroll(dao::scroll)
                .scrollParam(ScrollParam.<T>builder()
                        .querySpec(querySpec)
                        .build())
                .mutator(updater)
                .updater(dao::update)
                .updateNext(updateNext)
                .build();
        try {
            return transactionExecutor.get(tenantId).execute(context.getSessionFactory(),
                    true,
                    "update",
                    opContext,
                    context.getShardId(), false);
        } catch (Exception e) {
            throw new RuntimeException("Error updating entity with scroll: " + querySpec, e);
        }
    }

    <U> List<T> select(
            MultiTenantLookupDao.ReadOnlyContext<U> context,
            DetachedCriteria criteria,
            int start,
            int numResults) {
        val tenantId = context.getTenantId();
        final RelationalDaoPriv dao = daos.get(tenantId).get(context.getShardId());
        val opContext = Select.<T, List<T>>builder()
                .getter(dao::select)
                .selectParam(SelectParam.<T>builder()
                        .criteria(criteria)
                        .start(start)
                        .numRows(numResults)
                        .build())
                .build();
        return transactionExecutor.get(tenantId).execute(context.getSessionFactory(), true,
                "select", opContext, context.getShardId(), false);
    }

    <U> List<T> select(
            MultiTenantRelationalDao.ReadOnlyContext<U> context,
            DetachedCriteria criteria,
            int start,
            int numResults) {
        val tenantId = context.getTenantId();
        final RelationalDaoPriv dao = daos.get(tenantId).get(context.getShardId());
        val opContext = Select.<T, List<T>>builder()
                .getter(dao::select)
                .selectParam(SelectParam.<T>builder()
                        .criteria(criteria)
                        .start(start)
                        .numRows(numResults)
                        .build())
                .build();
        return transactionExecutor.get(tenantId).execute(context.getSessionFactory(), true,
                "select", opContext, context.getShardId(), false);
    }


    /**
     * Executes a database query within a specific shard, retrieving a list of query results.
     * <p>
     * This method performs a database query on a specific shard, as determined by the provided
     * context and shard ID. It retrieves a specified number of results starting from a given index
     * based on the provided query criteria. The query results are returned as a list of entities.
     *
     * @param context    A LookupDao.ReadOnlyContext object containing shard information and a session
     *                   factory.
     * @param querySpec  A QuerySpec object specifying the query criteria and projection.
     * @param start      The starting index for the query results (pagination).
     * @param numResults The number of results to retrieve from the query (pagination).
     * @return A List of query results of type T.
     */
    <U> List<T> select(MultiTenantLookupDao.ReadOnlyContext<U> context,
                       QuerySpec<T, T> querySpec, int start,
                       int numResults) {
        val tenantId = context.getTenantId();
        Preconditions.checkArgument(daos.containsKey(tenantId), "Unknown tenant: " + tenantId);
        final RelationalDaoPriv dao = daos.get(tenantId).get(context.getShardId());
        val opContext = Select.<T, List<T>>builder()
                .getter(dao::select)
                .selectParam(SelectParam.<T>builder()
                        .querySpec(querySpec)
                        .start(start)
                        .numRows(numResults)
                        .build())
                .build();
        return transactionExecutor.get(tenantId)
                .execute(context.getSessionFactory(), true, "select", opContext,
                        context.getShardId(),
                        false);
    }

    <U> List<T> select(MultiTenantRelationalDao.ReadOnlyContext<U> context,
                       QuerySpec<T, T> querySpec, int start,
                       int numResults) {
        val tenantId = context.getTenantId();
        Preconditions.checkArgument(daos.containsKey(tenantId), "Unknown tenant: " + tenantId);
        final RelationalDaoPriv dao = daos.get(tenantId).get(context.getShardId());
        val opContext = Select.<T, List<T>>builder()
                .getter(dao::select)
                .selectParam(SelectParam.<T>builder()
                        .querySpec(querySpec)
                        .start(start)
                        .numRows(numResults)
                        .build())
                .build();
        return transactionExecutor.get(tenantId)
                .execute(context.getSessionFactory(), true, "select", opContext,
                        context.getShardId(),
                        false);
    }

    /**
     * Provides a scroll api for records across shards. This api will scroll down in ascending order
     * of the 'sortFieldName' field. Newly added records can be polled by passing the pointer
     * repeatedly. If nothing new is available, it will return an empty set of results. If the passed
     * pointer is null, it will return the first pageSize records with a pointer to be passed to get
     * the next pageSize set of records.
     * <p>
     * NOTES: - Do not modify the criteria between subsequent calls - It is important to provide a
     * sort field that is perpetually increasing - Pointer returned can be used to _only_ scroll down
     *
     * @param tenantId      The tenant ID associated with the entity.
     * @param inCriteria    The core criteria for the query
     * @param inPointer     Existing {@link ScrollPointer}, should be null at start of a scroll
     *                      session
     * @param pageSize      Count of records per shard
     * @param sortFieldName Field to sort by. For correct sorting, the field needs to be an
     *                      ever-increasing one
     * @return A {@link ScrollResult} object that contains a {@link ScrollPointer} and a list of
     * results with max N * pageSize elements
     */
    public ScrollResult<T> scrollDown(String tenantId,
                                      final DetachedCriteria inCriteria,
                                      final ScrollPointer inPointer,
                                      final int pageSize,
                                      @NonNull final String sortFieldName) {
        Preconditions.checkArgument(daos.containsKey(tenantId), "Unknown tenant: " + tenantId);
        log.debug("SCROLL POINTER: {}", inPointer);
        val pointer = inPointer == null ? new ScrollPointer(ScrollPointer.Direction.DOWN) : inPointer;
        Preconditions.checkArgument(pointer.getDirection().equals(ScrollPointer.Direction.DOWN),
                "A down scroll pointer needs to be passed to this method");
        return scrollImpl(tenantId, inCriteria,
                pointer,
                pageSize,
                criteria -> criteria.addOrder(Order.asc(sortFieldName)),
                new FieldComparator<T>(FieldUtils.getField(this.entityClass, sortFieldName, true))
                        .thenComparing(ScrollResultItem::getShardIdx),
                "scrollDown");
    }

    /**
     * Provides a scroll api for records across shards. This api will scroll up in descending order of
     * the 'sortFieldName' field. As this api goes back in order, newly added records will not be
     * available in the scroll. If the passed pointer is null, it will return the last pageSize
     * records with a pointer to be passed to get the previous pageSize set of records.
     * <p>
     * NOTES: - Do not modify the criteria between subsequent calls - It is important to provide a
     * sort field that is perpetually increasing - Pointer returned can be used to _only_ scroll up
     *
     * @param tenantId      The tenant ID associated with the entity.
     * @param inCriteria    The core criteria for the query
     * @param inPointer     Existing {@link ScrollPointer}, should be null at start of a scroll
     *                      session
     * @param pageSize      Count of records per shard
     * @param sortFieldName Field to sort by. For correct sorting, the field needs to be an
     *                      ever-increasing one
     * @return A {@link ScrollResult} object that contains a {@link ScrollPointer} and a list of
     * results with max N * pageSize elements
     */
    @SneakyThrows
    public ScrollResult<T> scrollUp(String tenantId,
                                    final DetachedCriteria inCriteria,
                                    final ScrollPointer inPointer,
                                    final int pageSize,
                                    @NonNull final String sortFieldName) {
        Preconditions.checkArgument(daos.containsKey(tenantId), "Unknown tenant: " + tenantId);
        val pointer = null == inPointer ? new ScrollPointer(ScrollPointer.Direction.UP) : inPointer;
        Preconditions.checkArgument(pointer.getDirection().equals(ScrollPointer.Direction.UP),
                "An up scroll pointer needs to be passed to this method");
        return scrollImpl(tenantId, inCriteria,
                pointer,
                pageSize,
                criteria -> criteria.addOrder(Order.desc(sortFieldName)),
                new FieldComparator<T>(FieldUtils.getField(this.entityClass, sortFieldName, true))
                        .reversed()
                        .thenComparing(ScrollResultItem::getShardIdx),
                "scrollUp");
    }

    <U> List<T> select(String tenantId, MultiTenantRelationalDao.ReadOnlyContext<U> context,
                       DetachedCriteria criteria,
                       int first, int numResults) {
        Preconditions.checkArgument(daos.containsKey(tenantId), "Unknown tenant: " + tenantId);
        final RelationalDaoPriv dao = daos.get(tenantId).get(context.getShardId());
        val opContext = Select.<T, List<T>>builder()
                .getter(dao::select)
                .selectParam(SelectParam.<T>builder()
                        .criteria(criteria)
                        .start(first)
                        .numRows(numResults)
                        .build())
                .build();
        return transactionExecutor.get(tenantId).execute(context.getSessionFactory(), true,
                "select", opContext, context.getShardId(), false);
    }

    <U> List<T> select(String tenantId, MultiTenantRelationalDao.ReadOnlyContext<U> context,
                       QuerySpec<T, T> querySpec,
                       int first, int numResults) {
        Preconditions.checkArgument(daos.containsKey(tenantId), "Unknown tenant: " + tenantId);
        final RelationalDaoPriv dao = daos.get(tenantId).get(context.getShardId());
        val opContext = Select.<T, List<T>>builder()
                .getter(dao::select)
                .selectParam(SelectParam.<T>builder()
                        .querySpec(querySpec)
                        .start(first)
                        .numRows(numResults)
                        .build())
                .build();
        return transactionExecutor.get(tenantId)
                .execute(context.getSessionFactory(), true, "select", opContext,
                        context.getShardId(),
                        false);
    }

    public boolean update(String tenantId, String parentKey, Object id, Function<T, T> updater) {
        Preconditions.checkArgument(daos.containsKey(tenantId), "Unknown tenant: " + tenantId);
        int shardId = shardCalculator.shardId(tenantId, parentKey);
        RelationalDaoPriv dao = daos.get(tenantId).get(shardId);
        return update(tenantId, shardId, dao.sessionFactory, dao, id, updater, true);
    }

    /**
     * Run arbitrary read-only queries on all shards and return results.
     *
     * @param tenantId The tenant ID associated with the entity.
     * @param criteria The detached criteria. Typically, a grouping or counting query
     * @return A map of shard vs result-list
     */
    @SuppressWarnings("rawtypes")
    public Map<Integer, List> run(String tenantId, DetachedCriteria criteria) {
        return run(tenantId, criteria, Function.identity());
    }


    /**
     * Run read-only queries on all shards and transform them into required types
     *
     * @param criteria   The detached criteria. Typically, a grouping or counting query
     * @param translator A method to transform results to required type
     * @param <U>        Return type
     * @return Translated result
     */
    @SuppressWarnings("rawtypes")
    public <U> U run(String tenantId, DetachedCriteria criteria,
                     Function<Map<Integer, List>, U> translator) {
        Preconditions.checkArgument(daos.containsKey(tenantId), "Unknown tenant: " + tenantId);
        val output = IntStream.range(0, daos.get(tenantId).size())
                .boxed()
                .collect(Collectors.toMap(Function.identity(), shardId -> {
                    final RelationalDaoPriv dao = daos.get(tenantId).get(shardId);
                    OpContext<List> opContext = RunWithCriteria.<List>builder()
                            .detachedCriteria(criteria).handler(dao::run).build();
                    return transactionExecutor.get(tenantId).execute(dao.sessionFactory,
                            true,
                            "run",
                            opContext,
                            shardId);
                }));
        return translator.apply(output);
    }

    public <U> U runInSession(String tenantId, String id, Function<Session, U> handler) {
        Preconditions.checkArgument(daos.containsKey(tenantId), "Unknown tenant: " + tenantId);
        int shardId = shardCalculator.shardId(tenantId, id);
        RelationalDaoPriv dao = daos.get(tenantId).get(shardId);
        val opContext = RunInSession.<U>builder().handler(handler).build();
        return transactionExecutor.get(tenantId).
                execute(dao.sessionFactory, true, "runInSession", opContext, shardId);
    }

    /**
     * Updates an entity within a specific shard based on its unique identifier and an update
     * function.
     * <p>
     * This private method is responsible for updating an entity within a specific shard, as
     * identified by the shard ID. It retrieves the entity with the provided unique identifier,
     * applies the provided updater function to update the entity, and saves the updated entity back
     * to the database.
     *
     * @param tenantId            The tenant ID associated with the entity.
     * @param shardId             The identifier of the shard where the entity is located.
     * @param daoSessionFactory   The SessionFactory associated with the DAO for database access.
     * @param dao                 The RelationalDaoPriv responsible for accessing the database.
     * @param id                  The unique identifier of the entity to be updated.
     * @param updater             A function that takes the old entity, applies updates, and returns
     *                            the new entity.
     * @param completeTransaction A boolean indicating whether the transaction should be completed
     *                            after the update operation.
     * @return true if the entity was successfully updated, false otherwise.
     * @throws RuntimeException If any exception occurs during the update operation or if the entity
     *                          with the given identifier is not found.
     */
    private boolean update(String tenantId,
                           int shardId,
                           SessionFactory daoSessionFactory,
                           RelationalDaoPriv dao,
                           Object id,
                           Function<T, T> updater,
                           boolean completeTransaction) {
        Preconditions.checkArgument(daos.containsKey(tenantId), "Unknown tenant: " + tenantId);
        val opContext = GetAndUpdate.<T>builder()
                .criteria(dao.getDetachedCriteria(id))
                .getter(dao::get)
                .mutator(updater)
                .updater(dao::update).build();
        try {
            return transactionExecutor.get(tenantId).execute(daoSessionFactory, true, "update",
                    opContext, shardId, completeTransaction);
        } catch (Exception e) {
            throw new RuntimeException("Error updating entity: " + id, e);
        }
    }

    public boolean update(String tenantId, String parentKey, DetachedCriteria criteria,
                          Function<T, T> updater) {
        Preconditions.checkArgument(daos.containsKey(tenantId), "Unknown tenant: " + tenantId);
        int shardId = shardCalculator.shardId(tenantId, parentKey);
        RelationalDaoPriv dao = daos.get(tenantId).get(shardId);
        val selectParam = SelectParam.<T>builder()
                .criteria(criteria)
                .start(0)
                .numRows(1)
                .build();
        val opContext = SelectAndUpdate.<T>builder()
                .selectParam(selectParam)
                .selector(dao::select)
                .mutator(updater)
                .updater(dao::update).build();
        try {
            return transactionExecutor.get(tenantId).execute(dao.sessionFactory,
                    true,
                    "update",
                    opContext,
                    shardId);
        } catch (Exception e) {
            throw new RuntimeException("Error updating entity with criteria: " + criteria, e);
        }
    }


    /**
     * Updates a single entity within a specific shard based on query criteria and an update
     * function.
     * <p>
     * This method performs the operation of updating an entity within a specific shard, as determined
     * by the provided parent key and shard calculator. It uses the provided query criteria to select
     * the entity to be updated. If the entity is found, the provided updater function is applied to
     * update the entity, and the updated entity is saved.
     *
     * @param tenantId  The tenant ID associated with the entity.
     * @param parentKey A string representing the parent key that determines the shard for updating
     *                  the entity.
     * @param querySpec A QuerySpec object specifying the criteria for selecting the entity to
     *                  update.
     * @param updater   A function that takes the old entity, applies updates, and returns the new
     *                  entity.
     * @return true if the entity was successfully updated, false otherwise.
     * @throws RuntimeException If any exception occurs during the update operation or if criteria are
     *                          not met during the process.
     */
    public boolean update(String tenantId, String parentKey, QuerySpec<T, T> querySpec,
                          Function<T, T> updater) {
        Preconditions.checkArgument(daos.containsKey(tenantId), "Unknown tenant: " + tenantId);
        int shardId = shardCalculator.shardId(tenantId, parentKey);
        RelationalDaoPriv dao = daos.get(tenantId).get(shardId);
        val selectParam = SelectParam.<T>builder()
                .querySpec(querySpec)
                .start(0)
                .numRows(1)
                .build();
        val opContext = SelectAndUpdate.<T>builder()
                .selectParam(selectParam)
                .selector(dao::select)
                .mutator(updater)
                .updater(dao::update).build();
        try {
            return transactionExecutor.get(tenantId).execute(dao.sessionFactory,
                    true,
                    "update",
                    opContext,
                    shardId);
        } catch (Exception e) {
            throw new RuntimeException("Error updating entity with criteria: " + querySpec, e);
        }
    }


    public int updateUsingQuery(String tenantId, String parentKey,
                                UpdateOperationMeta updateOperationMeta) {
        Preconditions.checkArgument(daos.containsKey(tenantId), "Unknown tenant: " + tenantId);
        int shardId = shardCalculator.shardId(tenantId, parentKey);
        val dao = daos.get(tenantId).get(shardId);
        val opContext = UpdateByQuery.builder()
                .updater(dao::update).updateOperationMeta(updateOperationMeta).build();
        return transactionExecutor.get(tenantId)
                .execute(dao.sessionFactory, false, "updateUsingQuery", opContext,
                        shardId);
    }

    public <U> int updateUsingQuery(LockedContext<U> lockedContext, UpdateOperationMeta updateOperationMeta) {
        val tenantId = lockedContext.getTenantId();
        val dao = daos.get(tenantId).get(lockedContext.getShardId());
        val opContext = UpdateByQuery.builder()
                .updater(dao::update).updateOperationMeta(updateOperationMeta).build();
        return transactionExecutor.get(tenantId).execute(lockedContext.getSessionFactory(),
                false,
                "updateUsingQuery",
                opContext,
                lockedContext.getShardId(), false);
    }

    public LockedContext<T> lockAndGetExecutor(String tenantId, String parentKey,
                                               DetachedCriteria criteria) {
        Preconditions.checkArgument(daos.containsKey(tenantId), "Unknown tenant: " + tenantId);
        int shardId = shardCalculator.shardId(tenantId, parentKey);
        RelationalDaoPriv dao = daos.get(tenantId).get(shardId);
        return new LockedContext<>(tenantId, shardId, dao.sessionFactory, () -> dao.getLockedForWrite(criteria),
                DaoType.RELATIONAL, entityClass, shardInfoProviders.get(tenantId), observer);
    }


    /**
     * Acquires a write lock on entities matching the provided query criteria within a specific shard
     * and returns a LockedContext for further operations.
     * <p>
     * This method performs the operation of acquiring a write lock on entities within a specific
     * shard, as determined by the provided parent key and shard calculator. It uses the provided
     * query criteria to select the entities to be locked. It then constructs and returns a
     * LockedContext object that encapsulates the shard information and allows for subsequent
     * operations on the locked entities.
     *
     * @param tenantId  The tenant ID associated with the entity.
     * @param parentKey A string representing the parent key that determines the shard for acquiring
     *                  the write lock.
     * @param querySpec A QuerySpec object specifying the criteria for selecting entities to lock.
     * @return A LockedContext object containing shard information and the locked entities, enabling
     * further operations on the locked entities within the specified shard.
     */
    public LockedContext<T> lockAndGetExecutor(String tenantId, String parentKey,
                                               QuerySpec<T, T> querySpec) {
        Preconditions.checkArgument(daos.containsKey(tenantId), "Unknown tenant: " + tenantId);
        int shardId = shardCalculator.shardId(tenantId, parentKey);
        RelationalDaoPriv dao = daos.get(tenantId).get(shardId);
        return new LockedContext<>(tenantId, shardId, dao.sessionFactory, () -> dao.getLockedForWrite(querySpec),
                DaoType.RELATIONAL, entityClass, shardInfoProviders.get(tenantId), observer);
    }

    /**
     * Saves an entity within a specific shard and returns a LockedContext for further operations.
     * <p>
     * This method performs the operation of saving an entity within a specific shard, as determined
     * by the provided parent key and shard calculator. It then constructs and returns a LockedContext
     * object that encapsulates the shard information and allows for subsequent operations on the
     * saved entity.
     *
     * @param tenantId  The tenant ID associated with the entity.
     * @param parentKey A string representing the parent key that determines the shard for saving the
     *                  entity.
     * @param entity    The entity of type T to be saved.
     * @return A LockedContext object containing shard information and the saved entity, enabling
     * further operations on the entity within the specified shard.
     */
    public LockedContext<T> saveAndGetExecutor(String tenantId, String parentKey, T entity) {
        Preconditions.checkArgument(daos.containsKey(tenantId), "Unknown tenant: " + tenantId);
        int shardId = shardCalculator.shardId(tenantId, parentKey);
        RelationalDaoPriv dao = daos.get(tenantId).get(shardId);
        return new LockedContext<>(tenantId, shardId, dao.sessionFactory, dao::save, DaoType.RELATIONAL, entity,
                entityClass, shardInfoProviders.get(tenantId), observer);
    }

    <U> boolean createOrUpdate(
            LockedContext<U> context,
            DetachedCriteria criteria,
            UnaryOperator<T> updater,
            U parent,
            Function<U, T> entityGenerator) {
        val tenantId = context.getTenantId();
        val dao = daos.get(tenantId).get(context.getShardId());
        val selectParam = SelectParam.<T>builder()
                .criteria(criteria)
                .start(0)
                .numRows(1)
                .build();
        val opContext = CreateOrUpdateInLockedContext.<T, U>builder()
                .lockedEntity(parent)
                .selector(dao::select)
                .selectParam(selectParam)
                .entityGenerator(entityGenerator)
                .saver(dao::save)
                .mutator(updater)
                .updater(dao::update)
                .build();

        try {
            return transactionExecutor.get(tenantId).execute(context.getSessionFactory(),
                    true,
                    "createOrUpdate",
                    opContext,
                    context.getShardId(), false);
        } catch (Exception e) {
            throw new RuntimeException("Error updating entity with criteria: " + criteria, e);
        }
    }

    <U> boolean createOrUpdate(
            LockedContext<U> context,
            QuerySpec<T, T> querySpec,
            UnaryOperator<T> updater,
            U parent,
            Function<U, T> entityGenerator) {
        val tenantId = context.getTenantId();
        val dao = daos.get(tenantId).get(context.getShardId());
        val selectParam = SelectParam.<T>builder()
                .querySpec(querySpec)
                .start(0)
                .numRows(1)
                .build();

        val opContext = CreateOrUpdateInLockedContext.<T, U>builder()
                .lockedEntity(parent)
                .selector(dao::select)
                .selectParam(selectParam)
                .entityGenerator(entityGenerator)
                .saver(dao::save)
                .mutator(updater)
                .updater(dao::update)
                .build();

        try {
            return transactionExecutor.get(tenantId).execute(context.getSessionFactory(),
                    true,
                    "createOrUpdate",
                    opContext,
                    context.getShardId(), false);
        } catch (Exception e) {
            throw new RuntimeException("Error updating entity with criteria: " + querySpec, e);
        }
    }


    /**
     * Creates or updates a single entity within a specific shard based on a query and update logic.
     * <p>
     * This method performs a create or update operation on a single entity within a specific shard,
     * as determined by the provided LockedContext and shard ID. It uses the provided query to check
     * for the existence of an entity, and based on the result: - If no entity is found, it generates
     * a new entity using the entity generator and saves it. - If an entity is found, it applies the
     * provided updater function to update the entity.
     *
     * @param context         A LockedContext object containing information about the shard and
     *                        session factory.
     * @param querySpec       A QuerySpec object specifying the criteria for selecting an entity.
     * @param updater         A function that takes an old entity, applies updates, and returns a new
     *                        entity.
     * @param entityGenerator A supplier function for generating a new entity if none exists.
     * @param <U>             The type of result associated with the LockedContext.
     * @return true if the entity was successfully created or updated, false otherwise.
     * @throws RuntimeException If any exception occurs during the create/update operation or if
     *                          criteria are not met during the process.
     */
    <U> boolean createOrUpdate(
            LockedContext<U> context,
            QuerySpec<T, T> querySpec,
            UnaryOperator<T> updater,
            U parent,
            Supplier<T> entityGenerator) {
        return createOrUpdate(context, querySpec, updater, parent,
                e -> entityGenerator.get());
    }


    public boolean updateAll(final String tenantId, String parentKey,
                             int start, int numRows, DetachedCriteria criteria, Function<T, T> updater) {
        Preconditions.checkArgument(daos.containsKey(tenantId), "Unknown tenant: " + tenantId);
        int shardId = shardCalculator.shardId(tenantId, parentKey);
        RelationalDaoPriv dao = daos.get(tenantId).get(shardId);
        try {
            val opContext = UpdateAll.<T>builder()
                    .selectParam(
                            SelectParam.<T>builder()
                                    .criteria(criteria)
                                    .start(start)
                                    .numRows(numRows).build())
                    .selector(dao::select)
                    .mutator(updater)
                    .updater(dao::update).build();
            return transactionExecutor.get(tenantId)
                    .<Boolean>execute(dao.sessionFactory, true, "updateAll",
                            opContext, shardId);
        } catch (Exception e) {
            throw new RuntimeException("Error updating entity with criteria: " + criteria, e);
        }
    }


    /**
     * Updates a batch of entities within a specific shard based on a query and an update function.
     * <p>
     * This method performs an update operation on a batch of entities within a specific shard, as
     * determined by the provided parent key and shard calculator. It retrieves a specified number of
     * entities that match the criteria defined in the provided QuerySpec object, applies the provided
     * updater function to each entity, and updates the entities in the database.
     *
     * @param tenantId   The tenant ID associated with the entity.
     * @param parentKey  A string representing the parent key that determines the shard for the update
     *                   operation.
     * @param start      The starting index for selecting entities to update (pagination).
     * @param numResults The number of entities to retrieve and update (pagination).
     * @param querySpec  A QuerySpec object specifying the criteria for selecting entities to update.
     * @param updater    A function that takes an old entity, applies updates, and returns a new
     *                   entity.
     * @return true if all entities were successfully updated, false otherwise.
     * @throws RuntimeException If any exception occurs during the update operation or if criteria are
     *                          not met, it is wrapped in a RuntimeException and propagated.
     */
    public boolean updateAll(final String tenantId,
                             String parentKey, int start, int numResults, QuerySpec<T, T> querySpec,
                             Function<T, T> updater) {
        Preconditions.checkArgument(daos.containsKey(tenantId), "Unknown tenant: " + tenantId);
        int shardId = shardCalculator.shardId(tenantId, parentKey);
        RelationalDaoPriv dao = daos.get(tenantId).get(shardId);
        try {
            val opContext = UpdateAll.<T>builder()
                    .selectParam(
                            SelectParam.<T>builder()
                                    .querySpec(querySpec)
                                    .start(start)
                                    .numRows(numResults).build())
                    .selector(dao::select)
                    .mutator(updater)
                    .updater(dao::update).build();
            return transactionExecutor.get(tenantId)
                    .<Boolean>execute(dao.sessionFactory, true, "updateAll",
                            opContext, shardId);
        } catch (Exception e) {
            throw new RuntimeException("Error updating entity with criteria: " + querySpec, e);
        }
    }

    public List<T> select(final String tenantId, String parentKey, DetachedCriteria criteria,
                          int start, int numResults)
            throws Exception {
        return select(tenantId, parentKey, criteria, start, numResults, t -> t);
    }

    /**
     * Executes a database query within a specific shard, retrieving a list of query results.
     * <p>
     * This method performs a database query on a specific shard, as determined by the provided parent
     * key and shard calculator. It retrieves a specified number of results starting from a given
     * index and returns them as a list. The query results are processed using a default identity
     * function.
     *
     * @param tenantId   The tenant ID associated with the entity.
     * @param parentKey  A string representing the parent key that determines the shard for the
     *                   query.
     * @param querySpec  A QuerySpec object specifying the query criteria and projection.
     * @param start      The starting index for the query results (pagination).
     * @param numResults The number of results to retrieve from the query (pagination).
     * @return A List of query results of type T.
     * @throws Exception If any exception occurs during the query execution, it is propagated.
     */
    public List<T> select(final String tenantId, String parentKey, QuerySpec<T, T> querySpec,
                          int start, int numResults)
            throws Exception {
        return select(tenantId, parentKey, querySpec, start, numResults, t -> t);
    }

    public <U> U select(final String tenantId,
                        String parentKey,
                        DetachedCriteria criteria,
                        int start,
                        int numResults,
                        Function<List<T>, U> handler) throws Exception {
        Preconditions.checkArgument(daos.containsKey(tenantId), "Unknown tenant: " + tenantId);
        int shardId = shardCalculator.shardId(tenantId, parentKey);
        RelationalDaoPriv dao = daos.get(tenantId).get(shardId);
        val opContext = Select.<T, U>builder()
                .getter(dao::select)
                .selectParam(SelectParam.<T>builder()
                        .criteria(criteria)
                        .start(start)
                        .numRows(numResults)
                        .build())
                .afterSelect(handler)
                .build();
        return transactionExecutor.get(tenantId).execute(dao.sessionFactory,
                true,
                "select",
                opContext,
                shardId);

    }

    /**
     * Executes a database query within a specific shard, retrieving and processing the results.
     * <p>
     * This method performs a database query on a specific shard, as determined by the provided parent
     * key and shard calculator. It retrieves a specified number of results starting from a given
     * index, processes the results using the provided handler function, and returns the result of the
     * handler function.
     *
     * @param tenantId   The tenant ID associated with the entity.
     * @param parentKey  A string representing the parent key that determines the shard for the
     *                   query.
     * @param querySpec  A QuerySpec object specifying the query criteria and projection.
     * @param start      The starting index for the query results (pagination).
     * @param numResults The number of results to retrieve from the query (pagination).
     * @param handler    A function that processes the list of query results and returns a result of
     *                   type U.
     * @param <U>        The type of result to be returned by the handler function.
     * @return The result of applying the handler function to the query results.
     * @throws Exception If any exception occurs during the query execution or result processing, it
     *                   is propagated.
     */
    public <U> U select(final String tenantId,
                        String parentKey,
                        QuerySpec<T, T> querySpec,
                        int start,
                        int numResults,
                        Function<List<T>, U> handler) throws Exception {
        Preconditions.checkArgument(daos.containsKey(tenantId), "Unknown tenant: " + tenantId);
        int shardId = shardCalculator.shardId(tenantId, parentKey);
        RelationalDaoPriv dao = daos.get(tenantId).get(shardId);
        val opContext = Select.<T, U>builder()
                .getter(dao::select)
                .selectParam(SelectParam.<T>builder()
                        .querySpec(querySpec)
                        .start(start)
                        .numRows(numResults)
                        .build())
                .afterSelect(handler)
                .build();
        return transactionExecutor.get(tenantId).execute(dao.sessionFactory,
                true,
                "select",
                opContext,
                shardId);
    }

    public long count(final String tenantId, String parentKey, DetachedCriteria criteria) {
        Preconditions.checkArgument(daos.containsKey(tenantId), "Unknown tenant: " + tenantId);
        int shardId = shardCalculator.shardId(tenantId, parentKey);
        RelationalDaoPriv dao = daos.get(tenantId).get(shardId);
        val opContext = Count.builder().counter(dao::count).criteria(criteria).build();
        return transactionExecutor.get(tenantId).<Long>execute(dao.sessionFactory,
                true,
                "count", opContext,
                shardId);
    }

    public boolean exists(String tenantId, String parentKey, Object key) {
        Preconditions.checkArgument(daos.containsKey(tenantId), "Unknown tenant: " + tenantId);
        int shardId = shardCalculator.shardId(tenantId, parentKey);
        RelationalDaoPriv dao = daos.get(tenantId).get(shardId);
        val opContext = Get.<T, T>builder()
                .criteria(dao.getDetachedCriteria(key))
                .getter(dao::get).build();
        Optional<T>
                result = Optional.ofNullable(transactionExecutor.get(tenantId).execute(dao.sessionFactory,
                true,
                "exists",
                opContext,
                shardId));
        return result.isPresent();
    }

    /**
     * Counts the number of records matching a specified query in a given shard
     * <p>
     * This method calculates and returns the count of records that match the criteria defined in the
     * provided QuerySpec object. The counting operation is performed within the shard associated with
     * the provided parent key, as determined by the shard calculator.
     *
     * @param tenantId  The tenant ID associated with the entity.
     * @param parentKey A string representing the parent key that determines the shard for the
     *                  counting operation.
     * @param querySpec A QuerySpec object specifying the query criteria for counting records.
     * @return The total count of records matching the specified query criteria.
     * @throws RuntimeException If any exception occurs during the counting operation, it is wrapped
     *                          in a RuntimeException and propagated.
     */
    public long count(final String tenantId, String parentKey, QuerySpec<T, Long> querySpec) {
        Preconditions.checkArgument(daos.containsKey(tenantId), "Unknown tenant: " + tenantId);
        val shardId = shardCalculator.shardId(tenantId, parentKey);
        val dao = daos.get(tenantId).get(shardId);
        val opContext = CountByQuerySpec.builder()
                .counter(dao::count)
                .querySpec(querySpec)
                .build();
        return transactionExecutor.get(tenantId).<Long>execute(dao.sessionFactory,
                true,
                "count", opContext,
                shardId);
    }

    /**
     * Queries using the specified criteria across all shards and returns the counts of rows
     * satisfying the criteria.
     * <b>Note:</b> This method runs the query serially and it's usage is not recommended.
     *
     * @param tenantId The tenant ID associated with the entity.
     * @param criteria The select criteria
     * @return List of counts in each shard
     */
    public List<Long> countScatterGather(final String tenantId, DetachedCriteria criteria) {
        Preconditions.checkArgument(daos.containsKey(tenantId), "Unknown tenant: " + tenantId);
        return IntStream.range(0, daos.get(tenantId).size())
                .mapToObj(shardId -> {
                    val dao = daos.get(tenantId).get(shardId);
                    try {
                        val opContext = Count.builder()
                                .counter(dao::count).criteria(criteria).build();
                        return transactionExecutor.get(tenantId).execute(dao.sessionFactory, true,
                                "countScatterGather", opContext, shardId);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }).collect(Collectors.toList());
    }

    public List<T> scatterGather(final String tenantId, DetachedCriteria criteria, int start,
                                 int numRows) {
        Preconditions.checkArgument(daos.containsKey(tenantId), "Unknown tenant: " + tenantId);
        return IntStream.range(0, daos.get(tenantId).size())
                .mapToObj(shardId -> {
                    val dao = daos.get(tenantId).get(shardId);
                    try {
                        val opContext = Select.<T, List<T>>builder()
                                .getter(dao::select)
                                .selectParam(SelectParam.<T>builder()
                                        .criteria(criteria)
                                        .start(start)
                                        .numRows(numRows)
                                        .build())
                                .build();
                        return transactionExecutor.get(tenantId).execute(dao.sessionFactory,
                                true,
                                "scatterGather",
                                opContext,
                                shardId);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }).flatMap(Collection::stream).collect(Collectors.toList());
    }

    /**
     * Executes a scatter-gather operation across multiple Data Access Objects (DAOs) in a serial
     * manner
     *
     * @param tenantId  The tenant ID associated with the entity.
     * @param querySpec A QuerySpec object specifying the query to execute.
     * @param start     The starting index for the query results (pagination).
     * @param numRows   The number of rows to retrieve in the query results (pagination).
     * @return A List of type T containing the aggregated query results from all shards.
     * @throws RuntimeException If any exception occurs during the execution of queries on individual
     *                          shards, it is wrapped in a RuntimeException and propagated.
     */
    public List<T> scatterGather(final String tenantId, QuerySpec<T, T> querySpec, int start,
                                 int numRows) {
        Preconditions.checkArgument(daos.containsKey(tenantId), "Unknown tenant: " + tenantId);
        return IntStream.range(0, daos.get(tenantId).size())
                .mapToObj(shardId -> {
                    val dao = daos.get(tenantId).get(shardId);
                    try {
                        val opContext = Select.<T, List<T>>builder()
                                .getter(dao::select)
                                .selectParam(SelectParam.<T>builder()
                                        .querySpec(querySpec)
                                        .start(start)
                                        .numRows(numRows)
                                        .build())
                                .build();
                        return transactionExecutor.get(tenantId).execute(dao.sessionFactory,
                                true,
                                "scatterGather",
                                opContext,
                                shardId);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }).flatMap(Collection::stream).collect(Collectors.toList());
    }

    protected Field getKeyField() {
        return this.keyField;
    }

    @SneakyThrows
    private ScrollResult<T> scrollImpl(String tenantId,
                                       final DetachedCriteria inCriteria,
                                       final ScrollPointer pointer,
                                       final int pageSize,
                                       final UnaryOperator<DetachedCriteria> criteriaMutator,
                                       final Comparator<ScrollResultItem<T>> comparator,
                                       String methodName) {
        Preconditions.checkArgument(daos.containsKey(tenantId), "Unknown tenant: " + tenantId);
        val daoIndex = new AtomicInteger();
        val results = daos.get(tenantId).stream()
                .flatMap(dao -> {
                    val currIdx = daoIndex.getAndIncrement();
                    val criteria = criteriaMutator.apply(InternalUtils.cloneObject(inCriteria));
                    val opContext = Select.<T, List<T>>builder()
                            .getter(dao::select)
                            .selectParam(SelectParam.<T>builder()
                                    .criteria(criteria)
                                    .start(pointer.getCurrOffset(currIdx))
                                    .numRows(pageSize)
                                    .build())
                            .build();
                    return transactionExecutor.get(tenantId)
                            .execute(dao.sessionFactory, true,
                                    methodName, opContext, currIdx)
                            .stream()
                            .map(item -> new ScrollResultItem<>(item, currIdx));
                })
                .sorted(comparator)
                .limit(pageSize)
                .collect(Collectors.toList());
        //This list will be of _pageSize_ long but max fetched might be _pageSize_ * numShards long
        val outputBuilder = ImmutableList.<T>builder();
        results.forEach(result -> {
            outputBuilder.add(result.getData());
            pointer.advance(result.getShardIdx(), 1);// will get advanced
        });
        return new ScrollResult<>(pointer, outputBuilder.build());
    }

    private Query<T> createQuery(
            final Session session,
            final Class<T> entityClass,
            final QuerySpec<T, T> querySpec) {
        CriteriaBuilder builder = session.getCriteriaBuilder();
        CriteriaQuery<T> criteria = builder.createQuery(entityClass);
        Root<T> root = criteria.from(entityClass);
        querySpec.apply(root, criteria, builder);
        return session.createQuery(criteria);
    }

    public ReadOnlyContext<T> readOnlyExecutor(final String tenantId, final String parentKey,
                                               final Object key) {
        return readOnlyExecutor(tenantId, parentKey, key, x -> x);
    }

    public ReadOnlyContext<T> readOnlyExecutor(final String tenantId, final String parentKey,
                                               final Object key,
                                               final UnaryOperator<Criteria> criteriaUpdater) {
        return readOnlyExecutor(tenantId, parentKey, key, criteriaUpdater, () -> false);
    }

    /**
     * Creates and returns a read-only context for executing read operations on an entities for
     * provided {@code querySpec}
     *
     * <p>This method calculates the shard ID based on the provided {@code parentKey}, retrieves the
     * SelectParamPriv for the corresponding shard, and creates a read-only context for executing read
     * operations on the entities.
     *
     * @param tenantId        Tenant Id.
     * @param parentKey       parentKey of the entity will be used to decide shard.
     * @param key             used to provide parent key to be pulld
     * @param criteriaUpdater Function to update criteria to add additional params
     * @param entityPopulator A supplier that determines whether entity population should be
     *                        performed.
     * @return A new ReadOnlyContext for executing read operations on the selected entity.
     */
    public ReadOnlyContext<T> readOnlyExecutor(final String tenantId, final String parentKey,
                                               final Object key,
                                               final UnaryOperator<Criteria> criteriaUpdater,
                                               final Supplier<Boolean> entityPopulator) {
        Preconditions.checkArgument(daos.containsKey(tenantId), "Unknown tenant: " + tenantId);
        val shardId = shardCalculator.shardId(tenantId, parentKey);
        val dao = daos.get(tenantId).get(shardId);
        return new ReadOnlyContext<>(tenantId, shardId,
                dao.sessionFactory,
                () -> Lists.newArrayList(dao.getLocked(key, criteriaUpdater, LockMode.NONE)),
                entityPopulator,
                shardingOptions.get(tenantId).isSkipReadOnlyTransaction(),
                shardInfoProviders.get(tenantId),
                DaoType.RELATIONAL,
                entityClass,
                observer
        );
    }

    public ReadOnlyContext<T> readOnlyExecutor(final String tenantId, final String parentKey,
                                               final DetachedCriteria criteria,
                                               final int first,
                                               final int numResults) {
        return readOnlyExecutor(tenantId, parentKey, criteria, first, numResults, () -> false);
    }

    /**
     * Creates and returns a read-only context for executing read operations on an entities for
     * provided {@code querySpec}
     *
     * <p>This method calculates the shard ID based on the provided {@code parentKey}, retrieves the
     * SelectParamPriv for the corresponding shard, and creates a read-only context for executing read
     * operations on the entities.
     *
     * @param tenantId        Tenant Id.
     * @param parentKey       parentKey of the entity will be used to decide shard.
     * @param criteria        used to provide query details to fetch parent entities
     * @param first           The index of the first parent entity to retrieve.
     * @param numResults      The maximum number of parent entities to retrieve.
     * @param entityPopulator A supplier that determines whether entity population should be
     *                        performed.
     * @return A new ReadOnlyContext for executing read operations on the selected entities.
     */
    public ReadOnlyContext<T> readOnlyExecutor(final String tenantId, final String parentKey,
                                               final DetachedCriteria criteria,
                                               final int first,
                                               final int numResults,
                                               final Supplier<Boolean> entityPopulator) {
        Preconditions.checkArgument(daos.containsKey(tenantId), "Unknown tenant: " + tenantId);
        val shardId = shardCalculator.shardId(tenantId, parentKey);
        val dao = daos.get(tenantId).get(shardId);
        val selectParam = SelectParam.<T>builder()
                .criteria(criteria)
                .start(first)
                .numRows(numResults)
                .build();
        return new ReadOnlyContext<>(tenantId, shardId,
                dao.sessionFactory,
                () -> dao.select(selectParam),
                entityPopulator,
                shardingOptions.get(tenantId).isSkipReadOnlyTransaction(),
                shardInfoProviders.get(tenantId),
                DaoType.RELATIONAL,
                entityClass,
                observer
        );
    }

    public ReadOnlyContext<T> readOnlyExecutor(final String tenantId, final String parentKey,
                                               final QuerySpec<T, T> querySpec,
                                               final int first,
                                               final int numResults) {
        return readOnlyExecutor(tenantId, parentKey, querySpec, first, numResults, () -> false);
    }

    /**
     * Creates and returns a read-only context for executing read operations on an entities for
     * provided {@code querySpec}
     *
     * <p>This method calculates the shard ID based on the provided {@code parentKey}, retrieves the
     * SelectParamPriv for the corresponding shard, and creates a read-only context for executing read
     * operations on the entities.
     *
     * @param tenantId        Tenant Id.
     * @param parentKey       parentKey of the entity will be used to decide shard.
     * @param querySpec       used to provide query details to fetch parent entities
     * @param first           The index of the first parent entity to retrieve.
     * @param numResults      The maximum number of parent entities to retrieve.
     * @param entityPopulator A supplier that determines whether entity population should be
     *                        performed.
     * @return A new ReadOnlyContext for executing read operations on the selected entities.
     */
    public ReadOnlyContext<T> readOnlyExecutor(final String tenantId,
                                               final String parentKey,
                                               final QuerySpec<T, T> querySpec,
                                               final int first,
                                               final int numResults,
                                               final Supplier<Boolean> entityPopulator) {
        Preconditions.checkArgument(daos.containsKey(tenantId), "Unknown tenant: " + tenantId);
        val shardId = shardCalculator.shardId(tenantId, parentKey);
        val dao = daos.get(tenantId).get(shardId);
        val selectParam = SelectParam.<T>builder()
                .querySpec(querySpec)
                .start(first)
                .numRows(numResults)
                .build();
        return new ReadOnlyContext<>(tenantId, shardId,
                dao.sessionFactory,
                () -> dao.select(selectParam),
                entityPopulator,
                shardingOptions.get(tenantId).isSkipReadOnlyTransaction(),
                shardInfoProviders.get(tenantId),
                DaoType.RELATIONAL,
                entityClass,
                observer
        );
    }

    /**
     * Class to get detail about mapping association between parent and child entity
     */
    @Builder
    @Getter
    public static class AssociationMappingSpec {

        private String parentMappingKey;
        private String childMappingKey;

    }

    /**
     * This is wrapper class to provide details for fetching child entities
     * <ul>
     *   <li>associationMappingSpecs : child and parent column mapping details can be given here,
     *      which are used to take equality join with parent table</li>
     *   <li>criteria : querying child using {@link DetachedCriteria}</li>
     *   <li>querySpec : querying child using {@link QuerySpec}.</li>
     *  </ul>
     *
     * @param <T>
     */
    @Builder
    @Getter
    public static class QueryFilterSpec<T> {

        private List<AssociationMappingSpec> associationMappingSpecs;
        private DetachedCriteria criteria;
        private QuerySpec<T, T> querySpec;
    }

    /**
     * The {@code ReadOnlyContext} class represents a context for executing read-only operations
     * within a specific shard of a distributed database. It provides a mechanism to define and
     * execute read operations on data stored in the shard while handling transaction management,
     * entity retrieval, and optional entity population.
     *
     * <p>This class is typically used for retrieving and processing data from a specific shard.
     *
     * @param <T> The type of entity being operated on within the shard.
     */
    @Getter
    public static class ReadOnlyContext<T> {

        private final String tenantId;
        private final int shardId;
        private final SessionFactory sessionFactory;
        private final Supplier<Boolean> entityPopulator;
        private final boolean skipTransaction;
        private final TransactionExecutionContext executionContext;
        private final TransactionObserver observer;

        public ReadOnlyContext(
                final String tenantId,
                final int shardId,
                final SessionFactory sessionFactory,
                final Supplier<List<T>> getter,
                final Supplier<Boolean> entityPopulator,
                final boolean skipTxn,
                final ShardInfoProvider shardInfoProvider,
                final DaoType daoType,
                final Class<?> entityClass,
                final TransactionObserver observer
        ) {
            this.tenantId = tenantId;
            this.shardId = shardId;
            this.sessionFactory = sessionFactory;
            this.entityPopulator = entityPopulator;
            this.skipTransaction = skipTxn;
            this.observer = observer;
            val shardName = shardInfoProvider.shardName(shardId);
            val opContext = ReadOnlyForRelationalDao.<T>builder()
                    .getter(getter)
                    .build();
            this.executionContext = TransactionExecutionContext.builder()
                    .commandName("execute")
                    .shardName(shardName)
                    .daoType(daoType)
                    .entityClass(entityClass)
                    .opContext(opContext)
                    .build();
        }

        public ReadOnlyContext<T> apply(final Consumer<List<T>> handler) {
            ((ReadOnlyForRelationalDao) this.executionContext.getOpContext())
                    .getOperations()
                    .add(handler);
            return this;
        }

        public <U> ReadOnlyContext<T> readAugmentParent(
                final MultiTenantRelationalDao<U> relationalDao,
                final QueryFilterSpec<U> queryFilterSpec,
                final int first,
                final int numResults,
                final BiConsumer<T, List<U>> consumer) {
            return readAugmentParent(relationalDao, queryFilterSpec, first, numResults, consumer, p -> true);
        }

        /**
         * Reads and augments a parent entity using a relational DAO, applying a filter and consumer
         * function.
         * <p>
         * This method reads and potentially augments a parent entity using a provided relational DAO
         * and queryFilterSpec within the current context. queryFilterSpec can be passed as
         * {@link AssociationMappingSpec}, {@link DetachedCriteria} or {@link QuerySpec}. It applies a
         * filter to the parent entity and, if the filter condition is met, executes a query to retrieve
         * related child entities. The retrieved child entities are then passed to a consumer function
         * for further processing </p>
         *
         * @param <U>             The type of child entities.
         * @param relationalDao   A RelationalDao representing the DAO for retrieving child entities.
         * @param queryFilterSpec A QuerySpec specifying the criteria for selecting child entities.
         * @param first           The index of the first result to retrieve (pagination).
         * @param numResults      The index of the first result to retrieve (pagination).
         * @param consumer        A BiConsumer for processing the parent entity and its child entities.
         * @param filter          A Predicate for filtering parent entities to decide whether to process
         *                        them.
         * @return A ReadOnlyContext representing the current context.
         * @throws RuntimeException If any exception occurs during the execution of the query or
         *                          processing of the parent and child entities.
         */
        private <U> ReadOnlyContext<T> readAugmentParent(
                final MultiTenantRelationalDao<U> relationalDao,
                final QueryFilterSpec<U> queryFilterSpec,
                final int first,
                final int numResults,
                final BiConsumer<T, List<U>> consumer,
                final Predicate<T> filter) {
            return apply(parents -> {
                parents.forEach(parent -> {
                    if (!filter.test(parent)) {
                        return;
                    }
                    try {
                        // Querying based on associations
                        if (queryFilterSpec.associationMappingSpecs != null) {
                            QuerySpec<U, U> calculatedQuerySpec = buildQuerySpecWithAssociationSpec(parent,
                                    queryFilterSpec.associationMappingSpecs);
                            consumer.accept(parent,
                                    relationalDao.select(tenantId, this, calculatedQuerySpec, first, numResults));
                        }
                        // Querying based on querySpec
                        else if (queryFilterSpec.querySpec != null) {
                            consumer.accept(parent,
                                    relationalDao.select(tenantId, this, queryFilterSpec.querySpec, first,
                                            numResults));
                        }
                        // Querying based on crieria
                        else if (queryFilterSpec.criteria != null) {
                            consumer.accept(parent,
                                    relationalDao.select(tenantId, this, queryFilterSpec.criteria, first,
                                            numResults));
                        } else {
                            throw new UnsupportedOperationException("Missing queryFilterSpec provided.");
                        }
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                });
            });
        }

        /**
         * <p> This method first tries to executeImpl() operations. If the resulting entity is null,
         * this method tries to generate the populate the entity in database by calling
         * {@code entityPopulator} If {@code entityPopulator} returns true, it is expected that entity
         * is indeed populated in the database and hence {@code executeImpl()} is called again
         *
         * @return An optional containing the retrieved entity, or an empty optional if not found.
         */
        public Optional<List<T>> execute() {
            var result = executeImpl();
            if (null == result
                    && null != entityPopulator
                    && Boolean.TRUE.equals(
                    entityPopulator.get())) {//Try to populate entity (maybe from cold store etc)
                result = executeImpl();
            }
            return Optional.ofNullable(result);
        }

        /**
         * <p>This method orchestrates the execution of a read operation within a transactional
         * context. It ensures that transaction handling, including starting and ending the transaction,
         * is managed properly. The read operation is performed using the provided {@code getter}
         * function to retrieve list of data based on the specified {@code queryFilterSpec}. Optional
         * operations, if provided, are applied to every element from result before returning it.
         *
         * @return The result of the read operation after applying optional operations.
         * @throws RuntimeException if an error occurs during the read operation or if there are
         *                          transactional issues.
         */
        private List<T> executeImpl() {
            return observer.execute(executionContext, () -> {
                TransactionHandler transactionHandler = new TransactionHandler(sessionFactory, true,
                        this.skipTransaction);
                transactionHandler.beforeStart();
                try {
                    val opContext = ((ReadOnlyForRelationalDao<T>) executionContext.getOpContext());
                    return opContext.apply(transactionHandler.getSession());
                } catch (Exception e) {
                    transactionHandler.onError();
                    throw e;
                } finally {
                    transactionHandler.afterEnd();
                }
            });
        }

        private <U> QuerySpec<U, U> buildQuerySpecWithAssociationSpec(final T parent,
                                                                      final List<AssociationMappingSpec> queryFilterSpecs) {
            return (queryRoot, query, criteriaBuilder) -> {
                val restrictions = queryFilterSpecs.stream()
                        .map(spec -> {
                            val childKey = spec.getChildMappingKey();
                            val parentValue = extractParentValue(parent, spec.getParentMappingKey());
                            return equalityFilter(criteriaBuilder, queryRoot, childKey, parentValue);
                        })
                        .toArray(javax.persistence.criteria.Predicate[]::new);
                query.where(restrictions);
            };
        }

        private String extractParentValue(final T parent,
                                          final String key) {
            try {
                val isPropertyPresent = PropertyUtils.isReadable(parent, key);
                Preconditions.checkArgument(isPropertyPresent, "Missing property in bean");
                return BeanUtils.getProperty(parent, key);
            } catch (Exception e) {
                throw new RuntimeException("Error while reading association parent value", e);
            }
        }
    }
}
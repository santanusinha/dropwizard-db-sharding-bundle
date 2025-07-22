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
import com.google.common.collect.Maps;
import io.appform.dropwizard.sharding.ShardInfoProvider;
import io.appform.dropwizard.sharding.config.ShardingBundleOptions;
import io.appform.dropwizard.sharding.dao.operations.Count;
import io.appform.dropwizard.sharding.dao.operations.CountByQuerySpec;
import io.appform.dropwizard.sharding.dao.operations.Get;
import io.appform.dropwizard.sharding.dao.operations.OpContext;
import io.appform.dropwizard.sharding.dao.operations.RunInSession;
import io.appform.dropwizard.sharding.dao.operations.RunWithCriteria;
import io.appform.dropwizard.sharding.dao.operations.RunWithQuerySpec;
import io.appform.dropwizard.sharding.dao.operations.Save;
import io.appform.dropwizard.sharding.dao.operations.Select;
import io.appform.dropwizard.sharding.dao.operations.SelectParam;
import io.appform.dropwizard.sharding.dao.operations.UpdateByQuery;
import io.appform.dropwizard.sharding.dao.operations.lookupdao.CreateOrUpdateByLookupKey;
import io.appform.dropwizard.sharding.dao.operations.lookupdao.DeleteByLookupKey;
import io.appform.dropwizard.sharding.dao.operations.lookupdao.GetAndUpdateByLookupKey;
import io.appform.dropwizard.sharding.dao.operations.lookupdao.GetByLookupKey;
import io.appform.dropwizard.sharding.dao.operations.lookupdao.readonlycontext.ReadOnlyForLookupDao;
import io.appform.dropwizard.sharding.execution.DaoType;
import io.appform.dropwizard.sharding.execution.TransactionExecutionContext;
import io.appform.dropwizard.sharding.execution.TransactionExecutor;
import io.appform.dropwizard.sharding.observers.TransactionObserver;
import io.appform.dropwizard.sharding.query.QuerySpec;
import io.appform.dropwizard.sharding.scroll.FieldComparator;
import io.appform.dropwizard.sharding.scroll.ScrollPointer;
import io.appform.dropwizard.sharding.scroll.ScrollResult;
import io.appform.dropwizard.sharding.scroll.ScrollResultItem;
import io.appform.dropwizard.sharding.sharding.LookupKey;
import io.appform.dropwizard.sharding.sharding.ShardManager;
import io.appform.dropwizard.sharding.sharding.impl.ConsistentHashBucketIdExtractor;
import io.appform.dropwizard.sharding.utils.InternalUtils;
import io.appform.dropwizard.sharding.utils.ShardCalculator;
import io.appform.dropwizard.sharding.utils.TransactionHandler;
import lombok.Getter;
import lombok.NonNull;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import lombok.val;
import org.apache.commons.lang3.ClassUtils;
import org.apache.commons.lang3.reflect.FieldUtils;
import org.hibernate.Criteria;
import org.hibernate.LockMode;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.criterion.DetachedCriteria;
import org.hibernate.criterion.Order;
import org.hibernate.criterion.Projections;
import org.hibernate.criterion.Restrictions;
import org.hibernate.query.Query;

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
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static io.appform.dropwizard.sharding.query.QueryUtils.equalityFilter;

/**
 * A dao to manage lookup and top level elements in the system. Can save and retrieve an object
 * (tree) from any shard.
 * <b>Note:</b>
 * - The element must have only one String key for lookup. - The key needs to be annotated with
 * {@link LookupKey} The entity can be retrieved from any shard using the key.
 */
@Slf4j
public class MultiTenantLookupDao<T> implements ShardedDao<T> {

    private final Map<String, List<LookupDaoPriv>> daos = Maps.newHashMap();
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
     * Constructs a LookupDao instance for querying and managing entities across multiple shards.
     * <p>
     * This constructor initializes a LookupDao instance for working with entities of the specified
     * class distributed across multiple shards. It requires a list of session factories, a shard
     * calculator, sharding options, a shard information provider, and a transaction observer.
     *
     * @param sessionFactories   A list of SessionFactory instances for database access across
     *                           shards.
     * @param entityClass        The Class representing the type of entities managed by this
     *                           LookupDao.
     * @param shardManagers      A map of ShardManager to instantiate ShardCalculator.
     * @param shardingOptions    ShardingBundleOptions specifying additional sharding configuration
     *                           options.
     * @param shardInfoProviders A ShardInfoProvider for retrieving shard information.
     * @param observer           A TransactionObserver for monitoring transaction events.
     * @throws IllegalArgumentException If the entity class does not have exactly one field marked as
     *                                  LookupKey, if the key field is not accessible, or if it is not
     *                                  of type String.
     */
    public MultiTenantLookupDao(
            Map<String, List<SessionFactory>> sessionFactories,
            Class<T> entityClass,
            Map<String, ShardManager> shardManagers,
            Map<String, ShardingBundleOptions> shardingOptions,
            final Map<String, ShardInfoProvider> shardInfoProviders,
            final TransactionObserver observer) {
        sessionFactories.forEach((tenantId, factories) -> {
            daos.put(tenantId, factories.stream().map(LookupDaoPriv::new).collect(Collectors.toList()));
        });
        this.entityClass = entityClass;
        this.shardCalculator = new ShardCalculator<>(shardManagers, new ConsistentHashBucketIdExtractor<>(shardManagers));
        this.shardingOptions = shardingOptions;
        this.shardInfoProviders = shardInfoProviders;
        this.observer = observer;
        shardInfoProviders.forEach((tenantId, shardInfoProvider) -> {
            this.transactionExecutor.put(tenantId,
                    new TransactionExecutor(shardInfoProvider, DaoType.LOOKUP, entityClass, observer));
        });
        Field[] fields = FieldUtils.getFieldsWithAnnotation(entityClass, LookupKey.class);
        Preconditions.checkArgument(fields.length != 0, "At least one field needs to be sharding key");
        Preconditions.checkArgument(fields.length == 1, "Only one field can be sharding key");
        keyField = fields[0];
        if (!keyField.isAccessible()) {
            try {
                keyField.setAccessible(true);
            } catch (SecurityException e) {
                log.error(
                        "Error making key field accessible please use a public method and mark that as LookupKey",
                        e);
                throw new IllegalArgumentException("Invalid class, DAO cannot be created.", e);
            }
        }
        Preconditions.checkArgument(ClassUtils.isAssignable(keyField.getType(), String.class),
                "Key field must be a string");
    }

    /**
     * Get an object on the basis of key (value of field annotated with {@link LookupKey}) from any
     * shard.
     * <b>Note:</b> Lazy loading will not work once the object is returned.
     * If you need lazy loading functionality use the alternate {@link #get(String, String, Function)}
     * method.
     *
     * @param key The value of the key field to look for.
     * @return The entity
     * @throws Exception if backing dao throws
     */
    public Optional<T> get(String tenantId, String key) throws Exception {
        return Optional.ofNullable(get(tenantId, key, x -> x, t -> t));
    }

    public Optional<T> get(String tenantId, String key, UnaryOperator<Criteria> criteriaUpdater)
            throws Exception {
        return Optional.ofNullable(get(tenantId, key, criteriaUpdater, t -> t));
    }

    /**
     * Get an object on the basis of key (value of field annotated with {@link LookupKey}) from any
     * shard and applies the provided function/lambda to it. The return from the handler becomes the
     * return to the get function.
     * <b>Note:</b> The transaction is open when handler is applied. So lazy loading will work inside
     * the handler. Once get returns, lazy loading will nt owrok.
     *
     * @param tenantId Tenant id
     * @param key      The value of the key field to look for.
     * @param handler  Handler function/lambda that receives the retrieved object.
     * @return Whatever is returned by the handler function
     * @throws Exception if backing dao throws
     */
    public <U> U get(String tenantId, String key, Function<T, U> handler) throws Exception {
        Preconditions.checkArgument(daos.containsKey(tenantId), "Unknown tenant: " + tenantId);
        int shardId = shardCalculator.shardId(tenantId, key);
        LookupDaoPriv dao = daos.get(tenantId).get(shardId);
        val opContext = GetByLookupKey.<T, U>builder()
                .id(key)
                .getter(dao::get)
                .afterGet(handler)
                .build();
        return transactionExecutor.get(tenantId).execute(dao.sessionFactory, true, "get", opContext,
                shardId);
    }

    @SuppressWarnings("java:S112")
    public <U> U get(String tenantId, String key, UnaryOperator<Criteria> criteriaUpdater,
                     Function<T, U> handler)
            throws Exception {
        Preconditions.checkArgument(daos.containsKey(tenantId), "Unknown tenant: " + tenantId);
        int shardId = shardCalculator.shardId(tenantId, key);
        LookupDaoPriv dao = daos.get(tenantId).get(shardId);
        val opContext = GetByLookupKey.<T, U>builder()
                .id(key)
                .getter(dao::get)
                .criteriaUpdater(criteriaUpdater)
                .afterGet(handler)
                .build();
        return transactionExecutor.get(tenantId)
                .execute(dao.sessionFactory, true, "get", opContext, shardId);
    }

    /**
     * Check if object with specified key exists in any shard.
     *
     * @param tenantId Tenant id
     * @param key      id of the element to look for
     * @return true/false depending on if it's found or not.
     * @throws Exception if backing dao throws
     */
    public boolean exists(String tenantId, String key) throws Exception {
        return get(tenantId, key).isPresent();
    }

    /**
     * Saves an entity on proper shard based on hash of the value in the key field in the object. The
     * updated entity is returned. If Cascade is specified, this can be used to save an object tree
     * based on the shard of the top entity that has the key field.
     * <b>Note:</b> Lazy loading will not work on the augmented entity. Use the alternate
     * {@link #save(String, Object, Function)} for that.
     *
     * @param tenantId Tenant id
     * @param entity   Entity to save
     * @return Entity
     * @throws Exception if backing dao throws
     */
    public Optional<T> save(String tenantId, T entity) throws Exception {
        return Optional.ofNullable(save(tenantId, entity, t -> t));
    }

    /**
     * Save an object on the basis of key (value of field annotated with {@link LookupKey}) to target
     * shard and applies the provided function/lambda to it. The return from the handler becomes the
     * return to the get function.
     * <b>Note:</b> Handler is executed in the same transactional context as the save operation.
     * So any updates made to the object in this context will also get persisted.
     *
     * @param tenantId Tenant id
     * @param entity   The value of the key field to look for.
     * @param handler  Handler function/lambda that receives the retrieved object.
     * @return The entity
     * @throws Exception if backing dao throws
     */
    public <U> U save(String tenantId, T entity, Function<T, U> handler) throws Exception {
        Preconditions.checkArgument(daos.containsKey(tenantId), "Unknown tenant: " + tenantId);
        final String key = keyField.get(entity).toString();
        int shardId = shardCalculator.shardId(tenantId, key);
        log.debug("Saving entity of type {} with key {} to shard {}", entityClass.getSimpleName(), key,
                shardId);
        LookupDaoPriv dao = daos.get(tenantId).get(shardId);
        val opContext = Save.<T, U>builder()
                .saver(dao::save)
                .entity(entity)
                .afterSave(handler)
                .build();
        return transactionExecutor.get(tenantId)
                .execute(dao.sessionFactory, false, "save", opContext, shardId);
    }

    public Optional<T> createOrUpdate(String tenantId,
                                      String id,
                                      UnaryOperator<T> updater,
                                      Supplier<T> entityGenerator) {
        Preconditions.checkArgument(daos.containsKey(tenantId), "Unknown tenant: " + tenantId);
        val shardId = shardCalculator.shardId(tenantId, id);
        val dao = daos.get(tenantId).get(shardId);
        val opContext = CreateOrUpdateByLookupKey.<T>builder()
                .id(id)
                .getLockedForWrite(dao::getLockedForWrite)
                .entityGenerator(entityGenerator)
                .saver(dao::save)
                .mutator(updater)
                .updater(dao::update)
                .getter(dao::get)
                .build();

        return Optional.of(transactionExecutor.get(tenantId).execute(dao.sessionFactory,
                false,
                "createOrUpdate",
                opContext, shardId));
    }

    /**
     * Updates an entity. For this update, first a lock is taken on database on selected row (using
     * <i>for update</i> semantics) and {@code updater} is applied on the retrieved entity. It is
     * prudent to not perform any time-consuming activity inside {@code updater} to prevent long
     * lasting locks on database
     *
     * @param tenantId Tenant id
     * @param id       The ID of the entity to update.
     * @param updater  A function that takes an optional entity and returns the updated entity.
     * @return True if the update was successful, false otherwise.
     */
    public boolean updateInLock(String tenantId, String id, Function<Optional<T>, T> updater) {
        Preconditions.checkArgument(daos.containsKey(tenantId), "Unknown tenant: " + tenantId);
        int shardId = shardCalculator.shardId(tenantId, id);
        LookupDaoPriv dao = daos.get(tenantId).get(shardId);
        return updateImpl(tenantId, id, dao::getLockedForWrite, updater, shardId);
    }

    /**
     * Updates an entity within the shard identified by the provided {@code id} based on the
     * transformation defined by the {@code updater} function
     *
     * <p>This method is commonly used for modifying the state of an existing entity within the shard
     * by applying a transformation defined by the {@code updater} function. The {@code updater}
     * function takes an optional existing entity (if present) and returns the updated entity.
     *
     * @param tenantId Tenant id
     * @param id       The unique identifier of the entity to be updated.
     * @param updater  A function that defines the transformation to be applied to the entity. It
     *                 takes an optional existing entity as input and returns the updated entity.
     * @return {@code true} if the entity is successfully updated, {@code false} if it does not exist.
     */
    public boolean update(String tenantId, String id, Function<Optional<T>, T> updater) {
        Preconditions.checkArgument(daos.containsKey(tenantId), "Unknown tenant: " + tenantId);
        int shardId = shardCalculator.shardId(tenantId, id);
        LookupDaoPriv dao = daos.get(tenantId).get(shardId);
        return updateImpl(tenantId, id, dao::get, updater, shardId);
    }

    /**
     * Executes an update operation within the shard based on a predefined query defined in the
     * provided {@code updateOperationMeta}. This method is commonly used for performing batch updates
     * or modifications to entities matching specific criteria.
     *
     * <p>The update operation is specified by the {@code updateOperationMeta} object, which includes
     * the name of the named query to be executed and any parameters required for the query.
     *
     * @param tenantId            Tenant id
     * @param id                  The unique identifier or key associated with the shard where the
     *                            update operation will be performed.
     * @param updateOperationMeta The metadata defining the update operation, including the named
     *                            query and parameters.
     * @return The number of entities affected by the update operation.
     */
    public int updateUsingQuery(String tenantId, String id, UpdateOperationMeta updateOperationMeta) {
        Preconditions.checkArgument(daos.containsKey(tenantId), "Unknown tenant: " + tenantId);
        int shardId = shardCalculator.shardId(tenantId, id);
        LookupDaoPriv dao = daos.get(tenantId).get(shardId);
        val opContext = UpdateByQuery.builder()
                .updater(dao::update)
                .updateOperationMeta(updateOperationMeta)
                .build();
        return transactionExecutor.get(tenantId)
                .execute(dao.sessionFactory, false, "updateUsingQuery", opContext,
                        shardId);
    }

    /**
     * Updates an entity in the database based on the provided ID using a getter and updater
     * function.
     *
     * <p>This method retrieves an entity from the database using the provided getter function,
     * applies the updater function to the retrieved entity, and updates the entity in the database if
     * the updater function returns a non-null value. The update operation is performed on the shard
     * associated with the provided ID.
     *
     * @param tenantId Tenant id
     * @param id       The ID of the entity to be updated in the database.
     * @param getter   A function that retrieves the current state of the entity from the database.
     * @param mutator  A function that mutates the entity based on its current state.
     * @param shardId  The shard ID associated with the entity's ID.
     * @return True if the entity was successfully updated, false otherwise.
     * @throws RuntimeException If an error occurs during entity retrieval, update, or transaction
     *                          management.
     */
    private boolean updateImpl(String tenantId,
                               String id,
                               Function<String, T> getter,
                               Function<Optional<T>, T> mutator,
                               int shardId) {
        Preconditions.checkArgument(daos.containsKey(tenantId), "Unknown tenant: " + tenantId);
        try {
            val dao = daos.get(tenantId).get(shardId);
            val opContext = GetAndUpdateByLookupKey.<T>builder()
                    .id(id).getter(getter)
                    .mutator(mutator)
                    .updater(dao::update)
                    .build();
            return transactionExecutor.get(tenantId)
                    .<Boolean>execute(dao.sessionFactory, true, "updateImpl", opContext,
                            shardId);
        } catch (Exception e) {
            throw new RuntimeException("Error updating entity: " + id, e);
        }
    }

    /**
     * Creates and returns a locked context for executing write operations on an entity with the
     * specified ID.
     *
     * <p>This method calculates the shard ID based on the provided entity ID, retrieves the
     * LookupDaoPriv for the corresponding shard, and creates a locked context for executing write
     * operations on the entity. The entity is locked for write access within the database
     * transaction.
     *
     * @param tenantId Tenant id
     * @param id       The ID of the entity for which the locked context is created.
     * @return A new LockedContext for executing write operations on the specified entity with write
     * access.
     * @throws RuntimeException If an error occurs during entity locking or transaction management.
     */
    public LockedContext<T> lockAndGetExecutor(String tenantId, final String id) {
        Preconditions.checkArgument(daos.containsKey(tenantId), "Unknown tenant: " + tenantId);
        int shardId = shardCalculator.shardId(tenantId, id);
        LookupDaoPriv dao = daos.get(tenantId).get(shardId);
        return new LockedContext<>(tenantId, shardId, dao.sessionFactory, () -> dao.getLockedForWrite(id),
                DaoType.LOOKUP, entityClass, shardInfoProviders.get(tenantId), observer);

    }

    public ReadOnlyContext<T> readOnlyExecutor(String tenantId, String id) {
        return readOnlyExecutor(tenantId, id, x -> x);
    }

    /**
     * Creates and returns a read-only context for executing read operations on an entity with the
     * specified ID.
     *
     * <p>This method calculates the shard ID based on the provided entity ID, retrieves the
     * LookupDaoPriv for the corresponding shard, and creates a read-only context for executing read
     * operations on the entity. It does not perform entity population during read operations.
     *
     * @param tenantId        Tenant id
     * @param id              The ID of the entity for which the read-only context is created.
     * @param criteriaUpdater A method that lets clients add additional changes to the criteria before
     *                        the get
     * @return A new ReadOnlyContext for executing read operations on the specified entity.
     */
    public ReadOnlyContext<T> readOnlyExecutor(String tenantId, String id,
                                               UnaryOperator<Criteria> criteriaUpdater) {
        Preconditions.checkArgument(daos.containsKey(tenantId), "Unknown tenant: " + tenantId);
        int shardId = shardCalculator.shardId(tenantId, id);
        LookupDaoPriv dao = daos.get(tenantId).get(shardId);
        return new ReadOnlyContext<>(tenantId, shardId,
                dao.sessionFactory,
                key -> dao.getLocked(key, criteriaUpdater, LockMode.NONE),
                null,
                id,
                shardingOptions.get(tenantId).isSkipReadOnlyTransaction(),
                shardInfoProviders.get(tenantId), entityClass, observer);
    }

    public ReadOnlyContext<T> readOnlyExecutor(String tenantId, String id,
                                               Supplier<Boolean> entityPopulator) {
        return readOnlyExecutor(tenantId, id, x -> x, entityPopulator);
    }

    /**
     * Creates and returns a read-only context for executing read operations on an entity with the
     * specified ID, optionally allowing entity population.
     *
     * <p>This method calculates the shard ID based on the provided entity ID, retrieves the
     * LookupDaoPriv for the corresponding shard, and creates a read-only context for executing read
     * operations on the entity. If the ID does not exist in database, entityPopulator is used to
     * populate the entity
     *
     * @param tenantId        Tenant id
     * @param id              The ID of the entity for which the read-only context is created.
     * @param entityPopulator A supplier that determines whether entity population should be
     *                        performed.
     * @return A new ReadOnlyContext for executing read operations on the specified entity.
     */

    public ReadOnlyContext<T> readOnlyExecutor(String tenantId,
                                               String id,
                                               UnaryOperator<Criteria> criteriaUpdater,
                                               Supplier<Boolean> entityPopulator) {
        Preconditions.checkArgument(daos.containsKey(tenantId), "Unknown tenant: " + tenantId);
        int shardId = shardCalculator.shardId(tenantId, id);
        LookupDaoPriv dao = daos.get(tenantId).get(shardId);
        return new ReadOnlyContext<>(tenantId, shardId,
                dao.sessionFactory,
                key -> dao.getLocked(key, criteriaUpdater, LockMode.NONE),
                entityPopulator,
                id,
                shardingOptions.get(tenantId).isSkipReadOnlyTransaction(),
                shardInfoProviders.get(tenantId), entityClass, observer);
    }

    /**
     * Saves an entity to the database and obtains a locked context for further operations.
     *
     * <p>This method first retrieves the ID of the provided entity and determines the shard where it
     * should be saved based on the ID. It then saves the entity to the corresponding shard in the
     * database and returns a LockedContext for further operations on the saved entity.
     *
     * @param tenantId Tenant id
     * @param entity   The entity to be saved to the database.
     * @return A LockedContext that allows further operations on the saved entity within a locked
     * context.
     * @throws RuntimeException If an error occurs during entity saving or transaction management.
     */
    public LockedContext<T> saveAndGetExecutor(String tenantId, T entity) {
        String id;
        Preconditions.checkArgument(daos.containsKey(tenantId), "Unknown tenant: " + tenantId);
        try {
            id = keyField.get(entity).toString();
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }
        int shardId = shardCalculator.shardId(tenantId, id);
        LookupDaoPriv dao = daos.get(tenantId).get(shardId);
        return new LockedContext<>(tenantId, shardId, dao.sessionFactory, dao::save,
                DaoType.LOOKUP, entity,
                entityClass, shardInfoProviders.get(tenantId), observer);
    }

    /**
     * Queries using the specified criteria across all shards and returns the result.
     * <b>Note:</b> This method runs the query serially, and it's usage is not recommended.
     * Performs a scatter-gather operation by executing a query on all database shards and collecting
     * the results into a list of entities.
     *
     * @param tenantId Tenant id
     * @param criteria The DetachedCriteria object representing the query criteria to be executed on
     *                 all database shards.
     * @return A list of entities obtained by executing the query criteria on all available shards.
     */
    public List<T> scatterGather(String tenantId, DetachedCriteria criteria) {
        Preconditions.checkArgument(daos.containsKey(tenantId), "Unknown tenant: " + tenantId);
        return IntStream.range(0, daos.get(tenantId).size())
                .mapToObj(shardId -> {
                    try {
                        val dao = daos.get(tenantId).get(shardId);
                        val opContext = Select.<T, List<T>>builder()
                                .getter(dao::select)
                                .selectParam(SelectParam.<T>builder()
                                        .criteria(criteria)
                                        .build())
                                .build();
                        return transactionExecutor.get(tenantId).execute(dao.sessionFactory,
                                true, "scatterGather", opContext,
                                shardId);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }).flatMap(Collection::stream).collect(Collectors.toList());
    }

    /**
     * Performs a scatter-gather operation by executing a query on all database shards and collecting
     * the results into a list of entities.
     *
     * <p>This method executes the provided QuerySpec on all available database shards serially,
     * retrieving entities that match the query criteria from each shard. The results are then
     * collected into a single list of entities, effectively performing a scatter-gather operation.
     *
     * @param tenantId  Tenant id
     * @param querySpec The QuerySpec object representing the query criteria to be executed on all
     *                  database shards.
     * @return A list of entities obtained by executing the query on all available shards.
     * @throws RuntimeException If an error occurs while querying the database.
     */
    public List<T> scatterGather(String tenantId, final QuerySpec<T, T> querySpec) {
        return scatterGather(tenantId, querySpec, 0, Integer.MAX_VALUE);
    }

    /**
     * Performs a scatter-gather operation by executing a query on all database shards and collecting
     * the results into a list of entities with pagination support.
     *
     * <p>This method executes the provided QuerySpec on all available database shards serially,
     * retrieving entities that match the query criteria from each shard. The results are then
     * collected into a single list of entities, effectively performing a scatter-gather operation.
     *
     * @param tenantId  Tenant id
     * @param querySpec The QuerySpec object representing the query criteria to be executed on all
     *                  database shards.
     * @param start  starting index of pagination
     * @param numRows  number of records expected in resultset
     * @return A list of entities obtained by executing the query on all available shards.
     * @throws RuntimeException If an error occurs while querying the database.
     */
    public List<T> scatterGather(String tenantId, final QuerySpec<T, T> querySpec, int start,
                                 int numRows) {
        Preconditions.checkArgument(daos.containsKey(tenantId), "Unknown tenant: " + tenantId);
        return IntStream.range(0, daos.get(tenantId).size())
                .mapToObj(shardId -> {
                    try {
                        val dao = daos.get(tenantId).get(shardId);
                        OpContext<List<T>> opContext = Select.<T, List<T>>builder()
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
     * @param tenantId      Tenant id
     * @param inCriteria    The core criteria for the query
     * @param inPointer     Existing {@link ScrollPointer}, should be null at start of a scroll
     *                      session
     * @param pageSize      Page size of scroll result
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
        log.trace("Scroll Pointer: {}", inPointer);
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
     * Provides a scroll api for records across shards. This api will scroll down in ascending order
     * of the 'sortFieldName' field. Newly added records can be polled by passing the pointer
     * repeatedly. If nothing new is available, it will return an empty set of results. If the passed
     * pointer is null, it will return the first pageSize records with a pointer to be passed to get
     * the next pageSize set of records.
     * <p>
     * NOTES: - Do not modify the criteria between subsequent calls - It is important to provide a
     * sort field that is perpetually increasing - Pointer returned can be used to _only_ scroll down
     *
     * @param tenantId      Tenant id
     * @param inCriteria    The core criteria for the query
     * @param inPointer     Existing {@link ScrollPointer}, should be null at start of a scroll
     *                      session
     * @param pageSize      Page size of scroll result
     * @param sortFieldName Field to sort by. For correct sorting, the field needs to be an
     *                      ever-increasing one
     * @return A {@link ScrollResult} object that contains a {@link ScrollPointer} and a list of
     * results with max N * pageSize elements
     */
    public ScrollResult<T> scrollDown(String tenantId,
                                      final QuerySpec<T, T> inCriteria,
                                      final ScrollPointer inPointer,
                                      final int pageSize,
                                      @NonNull final String sortFieldName) {
        Preconditions.checkArgument(daos.containsKey(tenantId), "Unknown tenant: " + tenantId);
        log.trace("Scroll Pointer: {}", inPointer);
        val pointer = inPointer == null ? new ScrollPointer(ScrollPointer.Direction.DOWN) : inPointer;
        Preconditions.checkArgument(pointer.getDirection().equals(ScrollPointer.Direction.DOWN),
                "A down scroll pointer needs to be passed to this method");
        return scrollImpl(tenantId, inCriteria,
                pointer,
                pageSize,
                incomingQuerySpec -> (queryRoot, query, criteriaBuilder) -> {
                    incomingQuerySpec.apply(queryRoot, query, criteriaBuilder);
                    query.orderBy(criteriaBuilder.asc(queryRoot.get(sortFieldName)));
                },
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
     * @param tenantId      Tenant id
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

    /**
     * Provides a scroll api for records across shards. This api will scroll up in descending order of
     * the 'sortFieldName' field. As this api goes back in order, newly added records will not be
     * available in the scroll. If the passed pointer is null, it will return the last pageSize
     * records with a pointer to be passed to get the previous pageSize set of records.
     * <p>
     * NOTES: - Do not modify the criteria between subsequent calls - It is important to provide a
     * sort field that is perpetually increasing - Pointer returned can be used to _only_ scroll up
     *
     * @param tenantId      Tenant id
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
                                    final QuerySpec<T, T> inCriteria,
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
                incomingQuerySpec -> (queryRoot, query, criteriaBuilder) -> {
                    incomingQuerySpec.apply(queryRoot, query, criteriaBuilder);
                    query.orderBy(criteriaBuilder.desc(queryRoot.get(sortFieldName)));
                },
                new FieldComparator<T>(FieldUtils.getField(this.entityClass, sortFieldName, true))
                        .reversed()
                        .thenComparing(ScrollResultItem::getShardIdx),
                "scrollUp");
    }

    /**
     * Counts the number of entities that match the specified criteria on each database shard.
     *
     * <p>This method executes a count operation on all available database shards serially,
     * counting the entities that satisfy the provided criteria on each shard. The results are then
     * collected into a list, where each element corresponds to the count of matching entities on a
     * specific shard.
     *
     * @param tenantId Tenant id
     * @param criteria The DetachedCriteria object representing the criteria for counting entities.
     * @return A list of counts, where each count corresponds to the number of entities matching the
     * criteria on a specific shard.
     * @throws RuntimeException If an error occurs while querying the database.
     */
    public List<Long> count(String tenantId, DetachedCriteria criteria) {
        Preconditions.checkArgument(daos.containsKey(tenantId), "Unknown tenant: " + tenantId);
        return IntStream.range(0, daos.get(tenantId).size())
                .mapToObj(shardId -> {
                    val dao = daos.get(tenantId).get(shardId);
                    val opContext = Count.builder()
                            .counter(dao::count)
                            .criteria(criteria)
                            .build();
                    try {
                        return transactionExecutor.get(tenantId).execute(dao.sessionFactory,
                                true,
                                "count",
                                opContext,
                                shardId);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }).collect(Collectors.toList());
    }

    /**
     * Counts the number of entities that match the specified criteria on each database shard.
     *
     * <p>This method executes a count operation on all available database shards serially,
     * counting the entities that satisfy the provided criteria on each shard. The results are then
     * collected into a list, where each element corresponds to the count of matching entities on a
     * specific shard.
     *
     * @param tenantId Tenant id
     * @param criteria The DetachedCriteria object representing the criteria for counting entities.
     * @return A list of counts, where each count corresponds to the number of entities matching the
     * criteria on a specific shard.
     * @throws RuntimeException If an error occurs while querying the database.
     */
    public List<Long> count(String tenantId, QuerySpec<T, T> criteria) {
        Preconditions.checkArgument(daos.containsKey(tenantId), "Unknown tenant: " + tenantId);
        return IntStream.range(0, daos.get(tenantId).size())
                .mapToObj(shardId -> {
                    val dao = daos.get(tenantId).get(shardId);
                    val opContext = CountByQuerySpec.builder()
                            .counter(dao::count)
                            .querySpec(criteria)
                            .build();
                    try {
                        return transactionExecutor.get(tenantId).execute(dao.sessionFactory,
                                true,
                                "count",
                                opContext,
                                shardId);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }).collect(Collectors.toList());
    }

    /**
     * Run arbitrary read-only queries on all shards and return results.
     *
     * @param tenantId Tenant id
     * @param criteria The detached criteria. Typically, a grouping or counting query
     * @return A map of shard vs result-list
     */
    @SuppressWarnings("rawtypes")
    public Map<Integer, List<T>> run(String tenantId, DetachedCriteria criteria) {
        return run(tenantId, criteria, Function.identity());
    }

    /**
     * Run arbitrary read-only queries on all shards and return results.
     *
     * @param tenantId Tenant id
     * @param criteria The detached criteria. Typically, a grouping or counting query
     * @return A map of shard vs result-list
     */
    @SuppressWarnings("rawtypes")
    public Map<Integer, List<T>> run(String tenantId, QuerySpec<T, T> criteria) {
        return run(tenantId, criteria, Function.identity());
    }

    /**
     * Run read-only queries on all shards and transform them into required types
     *
     * @param tenantId   Tenant id
     * @param criteria   The detached criteria. Typically, a grouping or counting query
     * @param translator A method to transform results to required type
     * @param <U>        Return type
     * @return Translated result
     */
    @SuppressWarnings("rawtypes")
    public <U> U run(String tenantId, DetachedCriteria criteria,
                     Function<Map<Integer, List<T>>, U> translator) {
        Preconditions.checkArgument(daos.containsKey(tenantId), "Unknown tenant: " + tenantId);
        val output = IntStream.range(0, daos.get(tenantId).size())
                .boxed()
                .collect(Collectors.toMap(Function.identity(), shardId -> {
                    final LookupDaoPriv dao = daos.get(tenantId).get(shardId);
                    OpContext<List<T>> opContext = RunWithCriteria.<List<T>>builder()
                            .handler(dao::run)
                            .detachedCriteria(criteria)
                            .build();
                    return transactionExecutor.get(tenantId).execute(dao.sessionFactory,
                            true, "run",
                            opContext,
                            shardId);
                }));
        return translator.apply(output);
    }


    /**
     * Run read-only queries on all shards and transform them into required types
     *
     * @param tenantId   Tenant id
     * @param criteria   The detached criteria. Typically, a grouping or counting query
     * @param translator A method to transform results to required type
     * @param <U>        Return type
     * @return Translated result
     */
    @SuppressWarnings("rawtypes")
    public <U> U run(String tenantId, QuerySpec<T, T> criteria,
                     Function<Map<Integer, List<T>>, U> translator) {
        Preconditions.checkArgument(daos.containsKey(tenantId), "Unknown tenant: " + tenantId);
        val output = IntStream.range(0, daos.get(tenantId).size())
                .boxed()
                .collect(Collectors.toMap(Function.identity(), shardId -> {
                    final LookupDaoPriv dao = daos.get(tenantId).get(shardId);
                    OpContext<List<T>> opContext = RunWithQuerySpec.<T, List<T>>builder()
                            .handler(dao::run)
                            .querySpec(criteria)
                            .build();
                    return transactionExecutor.get(tenantId).execute(dao.sessionFactory,
                            true, "run",
                            opContext,
                            shardId);
                }));
        return translator.apply(output);
    }



    /**
     * Retrieves a list of entities associated with the specified keys from the database.
     *
     * <p>This method groups the provided keys by their corresponding database shards,
     * and then retrieves entities that match these keys from each shard serially. The results are
     * combined into a single list of entities and returned.
     *
     * @param tenantId Tenant id
     * @param keys     A list of keys for which entities should be retrieved from the database.
     * @return A list of entities obtained by querying the database for the specified keys.
     * @throws RuntimeException If an error occurs while querying the database.
     */
    public List<T> get(String tenantId, List<String> keys) {
        Preconditions.checkArgument(daos.containsKey(tenantId), "Unknown tenant: " + tenantId);
        Map<Integer, List<String>> lookupKeysGroupByShards = keys.stream()
                .collect(
                        Collectors.groupingBy(key -> shardCalculator.shardId(tenantId, key),
                                Collectors.toList()));
        return lookupKeysGroupByShards.keySet().stream().map(shardId -> {
            try {
                DetachedCriteria criteria = DetachedCriteria.forClass(entityClass)
                        .add(Restrictions.in(keyField.getName(), lookupKeysGroupByShards.get(shardId)));
                val opContext = Get.<List<T>, List<T>>builder()
                        .criteria(criteria)
                        .getter(daos.get(tenantId).get(shardId)::select)
                        .build();
                return transactionExecutor.get(tenantId)
                        .execute(daos.get(tenantId).get(shardId).sessionFactory, true, "get",
                                opContext, shardId);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }).flatMap(Collection::stream).collect(Collectors.toList());
    }

    /**
     * Executes a function within a database session on the shard corresponding to the provided ID.
     *
     * <p>This method acquires a database session for the shard associated with the specified ID
     * and executes the provided handler function within that session. It ensures that the session is
     * properly managed, including transaction handling, and returns the result of the handler
     * function.
     *
     * @param <U>     The type of the result returned by the handler function.
     * @param id      The ID used to determine the shard where the session will be acquired.
     * @param handler A function that takes a database session and returns a result of type U.
     * @return The result of executing the handler function within the acquired database session.
     * @throws RuntimeException If an error occurs during database session management or while
     *                          executing the handler.
     */
    public <U> U runInSession(String tenantId, String id, Function<Session, U> handler) {
        Preconditions.checkArgument(daos.containsKey(tenantId), "Unknown tenant: " + tenantId);
        int shardId = shardCalculator.shardId(tenantId, id);
        LookupDaoPriv dao = daos.get(tenantId).get(shardId);
        val opContext = RunInSession.<U>builder()
                .handler(handler)
                .build();
        return transactionExecutor.get(tenantId)
                .execute(dao.sessionFactory, true, "runInSession", opContext,
                        shardId);
    }

    public <U, V> V runInSession(String tenantId,
                                 BiFunction<Integer, Session, U> sessionHandler,
                                 Function<Map<Integer, U>, V> translator) {
        Preconditions.checkArgument(daos.containsKey(tenantId), "Unknown tenant: " + tenantId);
        val output = IntStream.range(0, daos.get(tenantId).size())
                .boxed()
                .collect(Collectors.toMap(Function.identity(), shardId -> {
                    final LookupDaoPriv dao = daos.get(tenantId).get(shardId);
                    RunInSession<U> opContext = RunInSession.<U>builder()
                            .handler(currSession -> sessionHandler.apply(shardId, currSession))
                            .build();
                    try {
                        return transactionExecutor.get(tenantId).execute(dao.sessionFactory,
                                true,
                                "runInSession",
                                opContext,
                                shardId);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }));
        return translator.apply(output);
    }

    /**
     * Deletes an entity with the specified ID from the database.
     *
     * <p>This method identifies the shard associated with the provided ID, then executes a delete
     * operation on the entity with the matching ID within that shard. It returns true if the delete
     * operation was successful and false otherwise.
     *
     * @param tenantId Tenant id
     * @param id       The ID of the entity to be deleted from the database.
     * @return True if the entity was successfully deleted, false otherwise.
     * @throws RuntimeException If an error occurs during the delete operation or transaction
     *                          management.
     */
    public boolean delete(String tenantId, String id) {
        Preconditions.checkArgument(daos.containsKey(tenantId), "Unknown tenant: " + tenantId);
        int shardId = shardCalculator.shardId(tenantId, id);
        val opContext = DeleteByLookupKey.builder()
                .id(id)
                .handler(daos.get(tenantId).get(shardId)::delete)
                .build();
        return transactionExecutor.get(tenantId).execute(daos.get(tenantId).get(shardId).sessionFactory,
                false,
                "delete",
                opContext,
                shardId);
    }

    /**
     * Retrieves the key field associated with the entity class.
     * <p>
     * This method returns the Field object representing the key field associated with the entity
     * class.
     *
     * @return The Field object representing the key field of the entity class.
     */
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
                    return transactionExecutor.get(tenantId).execute(dao.sessionFactory,
                                    true, methodName,
                                    opContext, currIdx)
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

    @SneakyThrows
    private ScrollResult<T> scrollImpl(String tenantId,
                                       final QuerySpec<T, T> inCriteria,
                                       final ScrollPointer pointer,
                                       final int pageSize,
                                       final UnaryOperator<QuerySpec<T, T>> criteriaMutator,
                                       final Comparator<ScrollResultItem<T>> comparator,
                                       String methodName) {
        Preconditions.checkArgument(daos.containsKey(tenantId), "Unknown tenant: " + tenantId);
        val daoIndex = new AtomicInteger();
        val results = daos.get(tenantId).stream()
                .flatMap(dao -> {
                    val currIdx = daoIndex.getAndIncrement();
                    val criteria = criteriaMutator.apply(inCriteria);
                    val opContext = Select.<T, List<T>>builder()
                            .getter(dao::select)
                            .selectParam(SelectParam.<T>builder()
                                    .querySpec(criteria)
                                    .start(pointer.getCurrOffset(currIdx))
                                    .numRows(pageSize)
                                    .build())
                            .build();
                    return transactionExecutor.get(tenantId).execute(dao.sessionFactory,
                                    true, methodName,
                                    opContext, currIdx)
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
                String tenantId,
                int shardId,
                SessionFactory sessionFactory,
                Function<String, T> getter,
                Supplier<Boolean> entityPopulator,
                String key,
                boolean skipTxn,
                final ShardInfoProvider shardInfoProvider,
                final Class<?> entityClass,
                TransactionObserver observer) {
            this.tenantId = tenantId;
            this.shardId = shardId;
            this.sessionFactory = sessionFactory;
            this.entityPopulator = entityPopulator;
            this.skipTransaction = skipTxn;
            this.observer = observer;
            val shardName = shardInfoProvider.shardName(shardId);
            val opContext = ReadOnlyForLookupDao.<T>builder()
                    .key(key)
                    .getter(getter)
                    .build();
            this.executionContext = TransactionExecutionContext.builder()
                    .commandName("execute")
                    .shardName(shardName)
                    .daoType(DaoType.LOOKUP)
                    .entityClass(entityClass)
                    .opContext(opContext)
                    .build();
        }

        /**
         * Applies a custom operation to the retrieved entity.
         *
         * <p>This method allows developers to specify a custom operation to be applied to the
         * retrieved entity. Multiple operations can be applied sequentially using method chaining.
         *
         * @param handler A function that takes the retrieved entity and applies a custom operation.
         * @return This {@code ReadOnlyContext} instance for method chaining.
         */
        public ReadOnlyContext<T> apply(Consumer<T> handler) {
            ((ReadOnlyForLookupDao) this.executionContext.getOpContext())
                    .getOperations()
                    .add(handler);
            return this;
        }

        /**
         * Read and augment parent entities based on a DetachedCriteria, retrieving a single related
         * entity
         *
         * <p>This method reads and augments parent entities based on the specified {@code criteria},
         * retrieving only a single child entity, and then applies the provided {@code consumer}
         * function to augment it with related child entity. The consumer function is applied to parent
         * entity.</p>
         *
         * @param <U>           The type of child entities.
         * @param relationalDao The relational data access object used to retrieve child entities.
         * @param criteria      The DetachedCriteria for selecting and composing parent entities.
         * @param consumer      A function that applies the child entity augmentation to the parent
         *                      entity.
         * @return This {@code ReadOnlyContext} instance to allow for method chaining.
         * @throws RuntimeException if an error occurs during the read operation or when applying the
         *                          consumer function.
         */
        public <U> ReadOnlyContext<T> readOneAugmentParent(
                MultiTenantRelationalDao<U> relationalDao,
                DetachedCriteria criteria,
                BiConsumer<T, List<U>> consumer) {
            return readAugmentParent(relationalDao, criteria, 0, 1, consumer, p -> true);
        }

        /**
         * Read and augment parent entities based on a QuerySpec, retrieving a single related entity and
         * applying operation.
         *
         * <p>This method reads and augments parent entities based on the specified {@code querySpec},
         * retrieving only a single child entity, and then applies the provided {@code consumer}
         * function to augment parent with the retrieved child entity.</p>
         *
         * @param <U>           The type of child entities.
         * @param relationalDao The relational data access object used to retrieve child entities.
         * @param querySpec     The QuerySpec for selecting and composing parent entities.
         * @param consumer      A function that applies the child entity augmentation to the parent
         *                      entity.
         * @return This {@code ReadOnlyContext} instance to allow for method chaining.
         * @throws RuntimeException if an error occurs during the read operation or when applying the
         *                          consumer function.
         */
        public <U> ReadOnlyContext<T> readOneAugmentParent(
                MultiTenantRelationalDao<U> relationalDao,
                QuerySpec<U, U> querySpec,
                BiConsumer<T, List<U>> consumer) {
            return readAugmentParent(relationalDao, querySpec, 0, 1, consumer, p -> true);
        }

        /**
         * Read and augment parent entities based on a DetachedCriteria and apply operations
         * selectively.
         *
         * <p>This method augments parent entities based on the child entities selected through
         * specified {@code criteria} The provided {@code consumer} function is then applied to augment
         * the selected parent entity with related child entities.</p>
         *
         * @param <U>           The type of child entities.
         * @param relationalDao The relational data access object used to retrieve child entities.
         * @param criteria      The DetachedCriteria for selecting and composing parent entities.
         * @param first         The index of the first parent entity to retrieve.
         * @param numResults    The maximum number of parent entities to retrieve.
         * @param consumer      A function that applies the child entity augmentation to the parent
         *                      entities.
         * @return This {@code ReadOnlyContext} instance to allow for method chaining.
         * @throws RuntimeException if an error occurs during the read operation or when applying the
         *                          consumer function.
         */
        public <U> ReadOnlyContext<T> readAugmentParent(
                MultiTenantRelationalDao<U> relationalDao,
                DetachedCriteria criteria,
                int first,
                int numResults,
                BiConsumer<T, List<U>> consumer) {
            return readAugmentParent(relationalDao, criteria, first, numResults, consumer, p -> true);
        }

        /**
         * Read and augment parent entities based on a {@link QuerySpec} and apply operations
         * selectively.
         *
         * <p>This method augments parent entity based on the child entities selected through specified
         * {@link QuerySpec} The provided {@code consumer} function is then applied to augment the
         * selected parent entity with related child entities.</p>
         *
         * @param <U>           The type of child entities.
         * @param relationalDao The relational data access object used to retrieve child entities.
         * @param querySpec     The QuerySpec for selecting and composing parent entities.
         * @param first         The index of the first parent entity to retrieve.
         * @param numResults    The maximum number of parent entities to retrieve.
         * @param consumer      A function that applies the child entity augmentation to the parent
         *                      entities.
         * @return This {@code ReadOnlyContext} instance to allow for method chaining.
         * @throws RuntimeException if an error occurs during the read operation or when applying the
         *                          consumer function.
         */
        public <U> ReadOnlyContext<T> readAugmentParent(
                MultiTenantRelationalDao<U> relationalDao,
                QuerySpec<U, U> querySpec,
                int first,
                int numResults,
                BiConsumer<T, List<U>> consumer) {
            return readAugmentParent(relationalDao, querySpec, first, numResults, consumer,
                    p -> true);
        }

        /**
         * Read and augment parent entity based on a {@link DetachedCriteria} and apply operations
         * selectively.
         *
         * <p>This method augments parent entity based on the single child entity selected through
         * specified {@link DetachedCriteria} The provided {@code consumer} function is then applied to
         * augment the selected parent entity with related child entities.</p> The filter function
         * selectively applies the consumer function to the chosen parent entity.
         *
         * @param <U>           The type of child entities.
         * @param relationalDao The relational data access object used to retrieve child entities.
         * @param criteria      The DetachedCriteria for selecting parent entities.
         * @param consumer      A function that applies the child entity augmentation to the parent
         *                      entity.
         * @param filter        A predicate function to filter the parent entity on which the consumer
         *                      function is applied.
         * @return This {@code ReadOnlyContext} instance to allow for method chaining.
         * @throws RuntimeException if an error occurs during the read operation or when applying the
         *                          consumer function. {@code readOneAugmentParent} method that accepts
         *                          a {@code QuerySpec} for better query composition and type-safety.
         */
        public <U> ReadOnlyContext<T> readOneAugmentParent(
                MultiTenantRelationalDao<U> relationalDao,
                DetachedCriteria criteria,
                BiConsumer<T, List<U>> consumer,
                Predicate<T> filter) {
            return readAugmentParent(relationalDao, criteria, 0, 1, consumer, filter);
        }

        /**
         * Read and augment parent entity based on a {@link QuerySpec} and apply operations
         * selectively.
         *
         * <p>This method augments parent entity based on the single child entity selected through
         * specified {@link QuerySpec} The provided {@code consumer} function is then applied to augment
         * the selected parent entity with related child entities.</p> The filter function selectively
         * applies the consumer function to the chosen parent entity.
         *
         * @param <U>           The type of child entities.
         * @param relationalDao The relational data access object used to retrieve child entities.
         * @param querySpec     The query specification for selecting parent entities.
         * @param consumer      A function that applies the child entity augmentation to the parent
         *                      entity.
         * @param filter        A predicate function to filter the parent entity on which the consumer
         *                      function is applied.
         * @return This {@code ReadOnlyContext} instance to allow for method chaining.
         * @throws RuntimeException if an error occurs during the read operation or when applying the
         *                          consumer function.
         */
        public <U> ReadOnlyContext<T> readOneAugmentParent(
                MultiTenantRelationalDao<U> relationalDao,
                QuerySpec<U, U> querySpec,
                BiConsumer<T, List<U>> consumer,
                Predicate<T> filter) {
            return readAugmentParent(relationalDao, querySpec, 0, 1, consumer, filter);
        }

        public <U> ReadOnlyContext<T> readAugmentParent(
                MultiTenantRelationalDao<U> relationalDao,
                DetachedCriteria criteria,
                int first,
                int numResults,
                BiConsumer<T, List<U>> consumer,
                Predicate<T> filter) {
            return apply(parent -> {
                if (filter.test(parent)) {
                    try {
                        consumer.accept(parent,
                                relationalDao.select(this, criteria, first, numResults));
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }
            });
        }


        /**
         * Reads and augments a parent entity using a relational DAO, applying a filter and consumer
         * function.
         * <p>
         * This method reads and potentially augments a parent entity using a provided relational DAO
         * and query specification within the current context. It applies a filter to the parent entity
         * and, if the filter condition is met, executes a query to retrieve related child entities. The
         * retrieved child entities are then passed to a consumer function for further processing </p>
         *
         * @param relationalDao A RelationalDao representing the DAO for retrieving child entities.
         * @param querySpec     A QuerySpec specifying the criteria for selecting child entities.
         * @param first         The index of the first result to retrieve (pagination).
         * @param numResults    The number of child entities to retrieve (pagination).
         * @param consumer      A BiConsumer for processing the parent entity and its child entities.
         * @param filter        A Predicate for filtering parent entities to decide whether to process
         *                      them.
         * @return A ReadOnlyContext representing the current context.
         * @throws RuntimeException If any exception occurs during the execution of the query or
         *                          processing of the parent and child entities.
         */
        public <U> ReadOnlyContext<T> readAugmentParent(
                MultiTenantRelationalDao<U> relationalDao,
                QuerySpec<U, U> querySpec,
                int first,
                int numResults,
                BiConsumer<T, List<U>> consumer,
                Predicate<T> filter) {
            return apply(parent -> {
                if (filter.test(parent)) {
                    try {
                        consumer.accept(parent,
                                relationalDao.select(this, querySpec, first, numResults));
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }
            });
        }

        /**
         * Executes the read-only operation within the shard, retrieves the entity, applies any custom
         * operations, and returns the result.
         *
         * <p> This method first tries to executeImpl() operations. If the resulting entity is null,
         * this method tries to generate the populate the entity in database by calling
         * {@code entityPopulator} If {@code entityPopulator} returns true, it is expected that entity
         * is indeed populated in the database and hence {@code executeImpl()} is called again
         *
         * @return An optional containing the retrieved entity, or an empty optional if not found.
         */
        public Optional<T> execute() {
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
         * Execute a read operation within a transactional context and apply optional operations.
         *
         * <p>This method orchestrates the execution of a read operation within a transactional
         * context. It ensures that transaction handling, including starting and ending the transaction,
         * is managed properly. The read operation is performed using the provided {@code getter}
         * function to retrieve data based on the specified {@code key}. Optional operations, if
         * provided, are applied to the result before returning it.
         *
         * @return The result of the read operation after applying optional operations.
         * @throws RuntimeException if an error occurs during the read operation or if there are
         *                          transactional issues.
         */
        private T executeImpl() {
            return observer.execute(executionContext, () -> {
                TransactionHandler transactionHandler = new TransactionHandler(sessionFactory,
                        true,
                        this.skipTransaction);
                transactionHandler.beforeStart();
                try {
                    val opContext = ((ReadOnlyForLookupDao<T>) executionContext.getOpContext());
                    return opContext.apply(transactionHandler.getSession());
                } catch (Exception e) {
                    transactionHandler.onError();
                    throw e;
                } finally {
                    transactionHandler.afterEnd();
                }
            });
        }
    }

    /**
     * The {@code LookupDaoPriv} class is a private implementation of a data access object (DAO)
     * responsible for performing database operations related to a specific entity type {@code T}. It
     * extends {@link AbstractDAO} to leverage common database access functionality provided by the
     * parent class.
     *
     * <p>Instances of this class are typically created within a broader context and encapsulate
     * database access operations specific to a particular entity type. These operations include
     * retrieval, modification, querying, and deletion of records associated with the entity.
     *
     * <p>It uses a Hibernate {@link SessionFactory} to manage database sessions and perform
     * operations within the scope of a session.
     */
    private final class LookupDaoPriv extends AbstractDAO<T> {

        /**
         * The Hibernate {@code SessionFactory} used for database operations.
         */
        private final SessionFactory sessionFactory;

        /**
         * Constructs a new {@code LookupDaoPriv} instance with the provided Hibernate
         * {@link SessionFactory}. This constructor initializes the DAO with the session factory, which
         * will be used for managing database operations.
         *
         * @param sessionFactory The Hibernate {@code SessionFactory} for database access.
         */
        public LookupDaoPriv(SessionFactory sessionFactory) {
            super(sessionFactory);
            this.sessionFactory = sessionFactory;
        }

        /**
         * Retrieves an entity from the shard based on the provided lookup key. The entity is retrieved
         * without any locking applied.
         *
         * @param lookupKey The unique lookup key identifying the entity.
         * @return The retrieved entity, or null if the entity is not found.
         */
        T get(String lookupKey) {
            return getLocked(lookupKey, x -> x, LockMode.READ);
        }

        T get(String lookupKey, UnaryOperator<Criteria> criteriaUpdater) {
            return getLocked(lookupKey, criteriaUpdater, LockMode.READ);
        }

        /**
         * Get an element from the shard.
         *
         * @param lookupKey       Id of the object
         * @param criteriaUpdater Function to update criteria to add additional params
         * @return Extracted element or null if not found.
         */
        T getLocked(String lookupKey, UnaryOperator<Criteria> criteriaUpdater, LockMode lockMode) {
            Criteria criteria = criteriaUpdater.apply(currentSession()
                    .createCriteria(entityClass)
                    .add(Restrictions.eq(keyField.getName(), lookupKey))
                    .setLockMode(lockMode));
            return uniqueResult(criteria);
        }

        /**
         * Retrieves an entity from the shard with a pessimistic write lock applied. This method is
         * typically used for write operations that require exclusive access to the entity.
         *
         * @param lookupKey The unique lookup key identifying the entity.
         * @return The retrieved entity, or null if the entity is not found.
         */
        T getLockedForWrite(String lookupKey) {
            return getLocked(lookupKey, LockModeType.PESSIMISTIC_WRITE);
        }

        /**
         * Retrieves an entity from the shard with the specified lock mode applied. The entity is locked
         * with the specified lock mode to control concurrent access.
         *
         * @param lookupKey The unique lookup key identifying the entity.
         * @param lockMode  The type of lock to be applied (e.g., NONE, PESSIMISTIC_WRITE).
         * @return The retrieved entity, or null if the entity is not found.
         * @throws org.hibernate.NonUniqueResultException if database returns more than 1 rows for
         *                                                {@code lookupKey}
         */
        T getLocked(String lookupKey, LockModeType lockMode) {
            val session = currentSession();
            val builder = session.getCriteriaBuilder();
            val criteria = builder.createQuery(entityClass);
            val root = criteria.from(entityClass);
            criteria.where(equalityFilter(builder, root, keyField.getName(), lookupKey));
            return uniqueResult(session.createQuery(criteria).setLockMode(lockMode));
        }

        /**
         * Saves an entity to the shard. The entity is persisted in the database, and any generated
         * fields are returned as part of the augmented entity.
         *
         * @param entity The entity to be saved to the shard.
         * @return The augmented entity with generated fields populated.
         */
        T save(T entity) {
            return persist(entity);
        }

        /**
         * Updates the state of an entity in the shard. The entity is first detached from the current
         * session to ensure that updates are performed. The updated entity is then associated with the
         * session for synchronization.
         *
         * @param entity The entity to be updated in the shard.
         */
        void update(T entity) {
            currentSession().evict(entity); //Detach .. otherwise update is a no-op
            currentSession().update(entity);
        }

        /**
         * Runs a query inside the shard based on the provided {@code DetachedCriteria} and returns a
         * list of matching entities.
         *
         * @param criteria The selection criteria to be applied to the query.
         * @return A list of matching entities or an empty list if none are found.
         */
        List<T> select(DetachedCriteria criteria) {
            return list(criteria.getExecutableCriteria(currentSession()));
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

        /**
         * Run a query inside this shard and return the matching list.
         *
         * @param criteria selection criteria to be applied.
         * @return List of elements or empty list if none found
         */
        @SuppressWarnings("rawtypes")
        List run(QuerySpec<T, T> criteria) {
            return createQuery(currentSession(), entityClass, criteria)
                    .list();
        }

        List<T> select(SelectParam selectParam) {
            if (selectParam.criteria != null) {
                val criteria = selectParam.criteria.getExecutableCriteria(currentSession());
                if (null != selectParam.getStart()) {
                    criteria.setFirstResult(selectParam.start);
                }
                if (null != selectParam.getNumRows()) {
                    criteria.setMaxResults(selectParam.numRows);
                }
                return list(criteria);
            }
            val query = InternalUtils.createQuery(currentSession(), entityClass, selectParam.querySpec);
            if (null != selectParam.getStart()) {
                query.setFirstResult(selectParam.start);
            }
            if (null != selectParam.getNumRows()) {
                query.setMaxResults(selectParam.numRows);
            }
            return list(query);
        }

        /**
         * Runs a query inside the shard based on the provided {@code QuerySpec} and returns a list of
         * matching entities.
         *
         * @param querySpec The query specification that defines the criteria and conditions.
         * @return A list of matching entities or an empty list if none are found.
         */
        List<T> select(final QuerySpec<T, T> querySpec) {
            val session = currentSession();
            val builder = session.getCriteriaBuilder();
            val criteria = builder.createQuery(entityClass);
            val root = criteria.from(entityClass);
            querySpec.apply(root, criteria, builder);
            return list(session.createQuery(criteria));
        }

        /**
         * Counts the number of entities that match the provided {@code DetachedCriteria}. The count is
         * based on the selection criteria specified in the query.
         *
         * @param criteria The selection criteria to be applied for counting.
         * @return The number of matching entities.
         */
        long count(DetachedCriteria criteria) {
            return (long) criteria.getExecutableCriteria(currentSession())
                    .setProjection(Projections.rowCount())
                    .uniqueResult();
        }


        /**
         * Counts the number of entities that match the provided {@code DetachedCriteria}. The count is
         * based on the selection criteria specified in the query.
         *
         * @param criteria The selection criteria to be applied for counting.
         * @return The number of matching entities.
         */
        long count(QuerySpec<T, Long> criteria) {
            val session = currentSession();
            CriteriaBuilder criteriaBuilder = session.getCriteriaBuilder();
            CriteriaQuery<Long> criteriaQuery = criteriaBuilder.createQuery(Long.class);
            Root<T> root = criteriaQuery.from(entityClass);
            criteriaQuery.select(criteriaBuilder.count(root));
            criteria.apply(root, criteriaQuery, criteriaBuilder);
            Query<Long> query = session.createQuery(criteriaQuery);
            return query.getSingleResult();
        }

        /**
         * Deletes an entity from the shard based on the provided ID. The entity is retrieved and locked
         * with a pessimistic write lock before deletion to ensure exclusive access.
         *
         * @param id The unique ID of the entity to be deleted.
         * @return {@code true} if the entity is successfully deleted, {@code false} if it does not
         * exist.
         */
        boolean delete(String id) {
            return Optional.ofNullable(getLocked(id, LockModeType.PESSIMISTIC_WRITE))
                    .map(object -> {
                        currentSession().delete(object);
                        return true;
                    })
                    .orElse(false);

        }


        /**
         * Executes an update operation within the shard based on the provided
         * {@code UpdateOperationMeta} metadata. This method is used for performing batch updates or
         * modifications to entities matching specific criteria.
         *
         * <p>The update operation is defined by a named query associated with the shard, as specified
         * in the {@code UpdateOperationMeta} object. Any parameters required for the query are provided
         * in the metadata, and the query is executed within the current database session.
         *
         * @param updateOperationMeta The metadata defining the update operation, including the named
         *                            query and parameters.
         * @return The number of entities affected by the update operation.
         */
        public int update(final UpdateOperationMeta updateOperationMeta) {
            val query = currentSession().createNamedQuery(updateOperationMeta.getQueryName());
            updateOperationMeta.getParams().forEach(query::setParameter);
            return query.executeUpdate();
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
    }
}

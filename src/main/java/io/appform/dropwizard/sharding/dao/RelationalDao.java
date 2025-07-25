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
import io.appform.dropwizard.sharding.scroll.ScrollPointer;
import io.appform.dropwizard.sharding.scroll.ScrollResult;
import io.appform.dropwizard.sharding.utils.ShardCalculator;
import lombok.Builder;
import lombok.Getter;
import lombok.NonNull;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.Criteria;
import org.hibernate.Session;
import org.hibernate.criterion.DetachedCriteria;

import java.lang.reflect.Field;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;

/**
 * A dao used to work with entities related to a parent shard. The parent may or maynot be physically present.
 * A murmur 128 hash of the string parent key is used to route the save and retrieve calls from the proper shard.
 */
@Slf4j
@SuppressWarnings({"unchecked", "UnusedReturnValue"})
public class RelationalDao<T> implements ShardedDao<T> {

    private final String tenantId;

    @Getter
    private final MultiTenantRelationalDao<T> delegate;

    /**
     * Constructs a RelationalDao instance for managing entities across multiple shards.
     * This constructor initializes a RelationalDao instance for working with entities of the specified class
     * distributed across multiple shards. The entity class must designate one field as
     * the primary key using the `@Id` annotation.
     *
     * @throws IllegalArgumentException If the entity class does not have exactly one field designated as @Id,
     *                                  if the designated key field is not accessible, or if it is not of type String.
     */
    public RelationalDao(final String tenantId,
                         final MultiTenantRelationalDao<T> delegate) {
        this.tenantId = tenantId;
        this.delegate = delegate;
    }

    /**
     * Retrieves an entity associated with a specific key from the database and returns it wrapped in an Optional.
     * This method allows you to retrieve an entity associated with a parent key and a specific key from the database.
     * It uses the superclass's `get` method for the retrieval operation and returns the retrieved entity wrapped in
     * an Optional.
     * If the entity is found in the database, it is returned within the Optional; otherwise, an empty Optional is
     * returned.
     *
     * @param parentKey A string representing the parent key that determines the shard for updating the entity.
     * @param key       The specific key or identifier of the entity to retrieve.
     * @return An Optional containing the retrieved entity if found, or an empty Optional if the entity is not found.
     * @throws Exception If an error occurs during the retrieval process.
     */
    public Optional<T> get(String parentKey, Object key) throws Exception {
        return Optional.ofNullable(get(parentKey, key, t -> t));
    }

    public <U> U get(String parentKey, Object key, Function<T, U> function) {
        return delegate.get(tenantId, parentKey, key, function);
    }

    /**
     * Saves an entity to the database and returns the saved entity wrapped in an Optional.
     * This method allows you to save an entity associated with a parent key to the database using the specified
     * parent key.
     * It uses the superclass's `save` method for the saving operation and returns the saved entity wrapped in an
     * Optional.
     * If the save operation is successful, the saved entity is returned; otherwise, an empty Optional is returned.
     *
     * @param parentKey A string representing the parent key that determines the shard for updating the entity.
     * @param entity    The entity to be saved.
     * @return An Optional containing the saved entity if the save operation is successful, or an empty Optional if
     * the save operation fails.
     * @throws Exception If an error occurs during the save operation.
     */
    public Optional<T> save(String parentKey, T entity) throws Exception {
        return Optional.ofNullable(save(parentKey, entity, t -> t));
    }

    public <U> U save(String parentKey, T entity, Function<T, U> handler) {
        return delegate.save(tenantId, parentKey, entity, handler);
    }

    /**
     * Saves a collection of entities associated to the database and returns a boolean indicating the success of the
     * operation.
     * <p>
     * This method allows you to save a collection of entities associated with a parent key to the database using the
     * specified parent key.
     * It uses the superclass's `saveAll` method for the bulk saving operation and returns `true` if the operation is
     * successful;
     * otherwise, it returns `false`.
     *
     * @param parentKey A string representing the parent key that determines the shard for updating the entity.
     * @param entities  The collection of entities to be saved.
     * @return `true` if the bulk save operation is successful, or `false` if it fails.
     */
    public boolean saveAll(String parentKey, Collection<T> entities) {
        return delegate.saveAll(tenantId, parentKey, entities);
    }

    public Optional<T> createOrUpdate(
            final String parentKey,
            final DetachedCriteria selectionCriteria,
            final UnaryOperator<T> updater,
            final Supplier<T> entityGenerator) {
        return delegate.createOrUpdate(tenantId, parentKey, selectionCriteria, updater, entityGenerator);
    }


    /**
     * Creates or updates a single entity within a specific shard based on a query and update logic.
     * <p>
     * This method performs a create or update operation on a single entity within a specific shard,
     * as determined by the provided LockedContext and shard ID. It uses the provided query to check
     * for the existence of an entity, and based on the result:
     * - If no entity is found, it generates a new entity using the entity generator and saves it.
     * - If an entity is found, it applies the provided updater function to update the entity.
     *
     * @param context         A LockedContext object containing information about the shard and session factory.
     * @param querySpec       A QuerySpec object specifying the criteria for selecting an entity.
     * @param updater         A function that takes an old entity, applies updates, and returns a new entity.
     * @param entityGenerator A supplier function for generating a new entity if none exists.
     * @param <U>             The type of result associated with the LockedContext.
     * @return true if the entity was successfully created or updated, false otherwise.
     * @throws RuntimeException If any exception occurs during the create/update operation or if criteria
     *                          are not met during the process.
     */
    <U> boolean createOrUpdate(
            LockedContext<U> context,
            QuerySpec<T, T> querySpec,
            UnaryOperator<T> updater,
            U parent,
            Supplier<T> entityGenerator) {
        return delegate.createOrUpdate(context, querySpec, updater, parent, e -> entityGenerator.get());
    }

    public <U> void save(LockedContext<U> context, T entity) {
        delegate.save(context, entity);
    }

    <U> void save(LockedContext<U> context, T entity, Function<T, T> handler) {
        delegate.save(context, entity, handler);
    }

    /**
     * Updates an entity within a locked context using a specific ID and an updater function.
     * <p>
     * This method updates an entity within a locked context using the provided ID and an updater function.
     * It is designed to work within the context of a locked transaction. The method delegates the update operation to
     * the DAO associated with the locked context and returns a boolean indicating the success of the update operation.
     *
     * @param context The locked context within which the entity is updated.
     * @param id      The ID of the entity to be updated.
     * @param updater A function that takes the current entity and returns the updated entity.
     * @return `true` if the entity is successfully updated, or `false` if the update operation fails.
     */
    <U> boolean update(LockedContext<U> context, Object id, Function<T, T> updater) {
        return delegate.update(context, id, updater);
    }

    /**
     * Updates entities matching the specified criteria within a locked context using an updater function.
     * <p>
     * This method updates entities within a locked context based on the provided criteria and an updater function.
     * It allows you to specify a DetachedCriteria object to filter the entities to be updated. The method iterates
     * through the matched entities, applies the updater function to each entity, and performs the update operation.
     * The update process continues as long as the `updateNext` supplier returns `true` and there are more matching
     * entities.
     *
     * @param context    The locked context within which entities are updated.
     * @param criteria   A DetachedCriteria object representing the criteria for filtering entities to update.
     * @param updater    A function that takes an entity and returns the updated entity.
     * @param updateNext A BooleanSupplier that determines whether to continue updating the next entity in the result
     *                   set.
     * @return `true` if at least one entity is successfully updated, or `false` if no entities are updated or the
     * update process fails.
     * @throws RuntimeException If an error occurs during the update process.
     */
    <U> boolean update(
            LockedContext<U> context,
            DetachedCriteria criteria,
            UnaryOperator<T> updater,
            BooleanSupplier updateNext) {
        return delegate.update(context, criteria, updater, updateNext);
    }

    /**
     * Updates entities within a specific shard based on a query, an update function, and scrolling through results.
     * <p>
     * This method performs an update operation on a set of entities within a specific shard, as determined
     * by the provided context and shard ID. It uses the provided query criteria to select entities and
     * applies the provided updater function to update each entity. The scrolling mechanism allows for
     * processing a large number of results efficiently.
     *
     * @param context    A LockedContext<U> object containing shard information and a session factory.
     * @param querySpec  A QuerySpec object specifying the query criteria.
     * @param updater    A function that takes an old entity, applies updates, and returns a new entity.
     * @param updateNext A BooleanSupplier that controls whether to continue updating the next entity.
     * @return true if all entities were successfully updated, false otherwise.
     * @throws RuntimeException If any exception occurs during the update operation with scrolling
     *                          or if criteria are not met during the process.
     */
    <U> boolean update(
            LockedContext<U> context,
            QuerySpec<T, T> querySpec,
            UnaryOperator<T> updater,
            BooleanSupplier updateNext) {
        return delegate.update(context, querySpec, updater, updateNext);
    }

    <U> List<T> select(
            LookupDao.ReadOnlyContext<U> context,
            DetachedCriteria criteria,
            int start,
            int numResults) {
        return delegate.select(context.getDelegate(), criteria, start, numResults);
    }


    /**
     * Executes a database query within a specific shard, retrieving a list of query results.
     * <p>
     * This method performs a database query on a specific shard, as determined by the provided
     * context and shard ID. It retrieves a specified number of results starting from a given index
     * based on the provided query criteria. The query results are returned as a list of entities.
     *
     * @param context    A LookupDao.ReadOnlyContext object containing shard information and a session factory.
     * @param querySpec  A QuerySpec object specifying the query criteria and projection.
     * @param start      The starting index for the query results (pagination).
     * @param numResults The number of results to retrieve from the query (pagination).
     * @return A List of query results of type T.
     */
    <U> List<T> select(
            LookupDao.ReadOnlyContext<U> context,
            QuerySpec<T, T> querySpec,
            int start,
            int numResults) {
        return delegate.select(context.getDelegate(), querySpec, start, numResults);
    }

    /**
     * Provides a scroll api for records across shards. This api will scroll down in ascending order of the
     * 'sortFieldName' field. Newly added records can be polled by passing the pointer repeatedly. If nothing new is
     * available, it will return an empty set of results.
     * If the passed pointer is null, it will return the first pageSize records with a pointer to be passed to get the
     * next pageSize set of records.
     * <p>
     * NOTES:
     * - Do not modify the criteria between subsequent calls
     * - It is important to provide a sort field that is perpetually increasing
     * - Pointer returned can be used to _only_ scroll down
     *
     * @param inCriteria    The core criteria for the query
     * @param inPointer     Existing {@link ScrollPointer}, should be null at start of a scroll session
     * @param pageSize      Count of records per shard
     * @param sortFieldName Field to sort by. For correct sorting, the field needs to be an ever-increasing one
     * @return A {@link ScrollResult} object that contains a {@link ScrollPointer} and a list of results with
     * max N * pageSize elements
     */
    public ScrollResult<T> scrollDown(
            final DetachedCriteria inCriteria,
            final ScrollPointer inPointer,
            final int pageSize,
            @NonNull final String sortFieldName) {
        return delegate.scrollDown(tenantId, inCriteria, inPointer, pageSize, sortFieldName);
    }

    /**
     * Provides a scroll api for records across shards. This api will scroll down in ascending order of the
     * 'sortFieldName' field. Newly added records can be polled by passing the pointer repeatedly. If nothing new is
     * available, it will return an empty set of results.
     * If the passed pointer is null, it will return the first pageSize records with a pointer to be passed to get the
     * next pageSize set of records.
     * <p>
     * NOTES:
     * - Do not modify the criteria between subsequent calls
     * - It is important to provide a sort field that is perpetually increasing
     * - Pointer returned can be used to _only_ scroll down
     *
     * @param inCriteria    The core criteria for the query
     * @param inPointer     Existing {@link ScrollPointer}, should be null at start of a scroll session
     * @param pageSize      Count of records per shard
     * @param sortFieldName Field to sort by. For correct sorting, the field needs to be an ever-increasing one
     * @return A {@link ScrollResult} object that contains a {@link ScrollPointer} and a list of results with
     * max N * pageSize elements
     */
    public ScrollResult<T> scrollDown(
            final QuerySpec<T, T> inCriteria,
            final ScrollPointer inPointer,
            final int pageSize,
            @NonNull final String sortFieldName) {
        return delegate.scrollDown(tenantId, inCriteria, inPointer, pageSize, sortFieldName);
    }

    /**
     * Provides a scroll api for records across shards. This api will scroll up in descending order of the
     * 'sortFieldName' field.
     * As this api goes back in order, newly added records will not be available in the scroll.
     * If the passed pointer is null, it will return the last pageSize records with a pointer to be passed to get the
     * previous pageSize set of records.
     * <p>
     * NOTES:
     * - Do not modify the criteria between subsequent calls
     * - It is important to provide a sort field that is perpetually increasing
     * - Pointer returned can be used to _only_ scroll up
     *
     * @param inCriteria    The core criteria for the query
     * @param inPointer     Existing {@link ScrollPointer}, should be null at start of a scroll session
     * @param pageSize      Count of records per shard
     * @param sortFieldName Field to sort by. For correct sorting, the field needs to be an ever-increasing one
     * @return A {@link ScrollResult} object that contains a {@link ScrollPointer} and a list of results with
     * max N * pageSize elements
     */
    @SneakyThrows
    public ScrollResult<T> scrollUp(
            final DetachedCriteria inCriteria,
            final ScrollPointer inPointer,
            final int pageSize,
            @NonNull final String sortFieldName) {
        return delegate.scrollUp(tenantId, inCriteria, inPointer, pageSize, sortFieldName);
    }

    /**
     * Provides a scroll api for records across shards. This api will scroll up in descending order of the
     * 'sortFieldName' field.
     * As this api goes back in order, newly added records will not be available in the scroll.
     * If the passed pointer is null, it will return the last pageSize records with a pointer to be passed to get the
     * previous pageSize set of records.
     * <p>
     * NOTES:
     * - Do not modify the criteria between subsequent calls
     * - It is important to provide a sort field that is perpetually increasing
     * - Pointer returned can be used to _only_ scroll up
     *
     * @param inCriteria    The core criteria for the query
     * @param inPointer     Existing {@link ScrollPointer}, should be null at start of a scroll session
     * @param pageSize      Count of records per shard
     * @param sortFieldName Field to sort by. For correct sorting, the field needs to be an ever-increasing one
     * @return A {@link ScrollResult} object that contains a {@link ScrollPointer} and a list of results with
     * max N * pageSize elements
     */
    @SneakyThrows
    public ScrollResult<T> scrollUp(
            final QuerySpec<T, T> inCriteria,
            final ScrollPointer inPointer,
            final int pageSize,
            @NonNull final String sortFieldName) {
        return delegate.scrollUp(tenantId, inCriteria, inPointer, pageSize, sortFieldName);
    }

    <U> List<T> select(RelationalDao.ReadOnlyContext<U> context, DetachedCriteria criteria, int first, int numResults) {
        return delegate.select(context.getDelegate(), criteria, first, numResults);
    }

    <U> List<T> select(RelationalDao.ReadOnlyContext<U> context, QuerySpec<T, T> querySpec, int first, int numResults) {
        return delegate.select(context.getDelegate(), querySpec, first, numResults);
    }

    public boolean update(String parentKey, Object id, Function<T, T> updater) {
        return delegate.update(tenantId, parentKey, id, updater);
    }

    /**
     * Run arbitrary read-only queries on all shards and return results.
     *
     * @param criteria The detached criteria. Typically, a grouping or counting query
     * @return A map of shard vs result-list
     */
    @SuppressWarnings("rawtypes")
    public Map<Integer, List> run(DetachedCriteria criteria) {
        return delegate.run(tenantId, criteria);
    }

    /**
     * Run arbitrary read-only queries on all shards and return results.
     *
     * @param criteria The detached criteria. Typically, a grouping or counting query
     * @return A map of shard vs result-list
     */
    @SuppressWarnings("rawtypes")
    public Map<Integer, List> run(QuerySpec<T, T> criteria) {
        return delegate.run(tenantId, criteria);
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
    public <U> U run(DetachedCriteria criteria, Function<Map<Integer, List>, U> translator) {
        return delegate.run(tenantId, criteria, translator);
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
    public <U> U run(QuerySpec<T, T> criteria, Function<Map<Integer, List>, U> translator) {
        return delegate.run(tenantId, criteria, translator);
    }

    public <U> U runInSession(String id, Function<Session, U> handler) {
        return delegate.runInSession(tenantId, id, handler);
    }

    public boolean update(String parentKey, DetachedCriteria criteria, Function<T, T> updater) {
        return delegate.update(tenantId, parentKey, criteria, updater);
    }

    /**
     * Updates a single entity within a specific shard based on query criteria and an update function.
     * <p>
     * This method performs the operation of updating an entity within a specific shard, as determined
     * by the provided parent key and shard calculator. It uses the provided query criteria to select
     * the entity to be updated. If the entity is found, the provided updater function is applied to
     * update the entity, and the updated entity is saved.
     *
     * @param parentKey A string representing the parent key that determines the shard for updating
     *                  the entity.
     * @param querySpec A QuerySpec object specifying the criteria for selecting the entity to update.
     * @param updater   A function that takes the old entity, applies updates, and returns the new entity.
     * @return true if the entity was successfully updated, false otherwise.
     * @throws RuntimeException If any exception occurs during the update operation or if criteria are
     *                          not met during the process.
     */
    public boolean update(String parentKey, QuerySpec<T, T> querySpec, Function<T, T> updater) {
        return delegate.update(tenantId, parentKey, querySpec, updater);
    }


    public int updateUsingQuery(String parentKey, UpdateOperationMeta updateOperationMeta) {
        return delegate.updateUsingQuery(tenantId, parentKey, updateOperationMeta);
    }

    public <U> int updateUsingQuery(LockedContext<U> lockedContext, UpdateOperationMeta updateOperationMeta) {
        return delegate.updateUsingQuery(lockedContext, updateOperationMeta);
    }

    public LockedContext<T> lockAndGetExecutor(String parentKey, DetachedCriteria criteria) {
        return delegate.lockAndGetExecutor(tenantId, parentKey, criteria);
    }

    /**
     * Acquires a write lock on entities matching the provided query criteria within a specific shard
     * and returns a LockedContext for further operations.
     * <p>
     * This method performs the operation of acquiring a write lock on entities within a specific shard,
     * as determined by the provided parent key and shard calculator. It uses the provided query criteria
     * to select the entities to be locked. It then constructs and returns a LockedContext object that
     * encapsulates the shard information and allows for subsequent operations on the locked entities.
     *
     * @param parentKey A string representing the parent key that determines the shard for
     *                  acquiring the write lock.
     * @param querySpec A QuerySpec object specifying the criteria for selecting entities to lock.
     * @return A LockedContext object containing shard information and the locked entities,
     * enabling further operations on the locked entities within the specified shard.
     */
    public LockedContext<T> lockAndGetExecutor(String parentKey, QuerySpec<T, T> querySpec) {
        return delegate.lockAndGetExecutor(tenantId, parentKey, querySpec);
    }

    /**
     * Saves an entity within a specific shard and returns a LockedContext for further operations.
     * <p>
     * This method performs the operation of saving an entity within a specific shard, as determined by
     * the provided parent key and shard calculator. It then constructs and returns a LockedContext
     * object that encapsulates the shard information and allows for subsequent operations on the
     * saved entity.
     *
     * @param parentKey A string representing the parent key that determines the shard for
     *                  saving the entity.
     * @param entity    The entity of type T to be saved.
     * @return A LockedContext object containing shard information and the saved entity,
     * enabling further operations on the entity within the specified shard.
     */
    public LockedContext<T> saveAndGetExecutor(String parentKey, T entity) {
        return delegate.saveAndGetExecutor(tenantId, parentKey, entity);
    }

    <U> boolean createOrUpdate(
            LockedContext<U> context,
            DetachedCriteria criteria,
            UnaryOperator<T> updater,
            U parent,
            Function<U, T> entityGenerator) {
        return delegate.createOrUpdate(context, criteria, updater, parent, entityGenerator);
    }

    <U> boolean createOrUpdate(
            LockedContext<U> context,
            QuerySpec<T, T> querySpec,
            UnaryOperator<T> updater,
            U parent,
            Function<U, T> entityGenerator) {
        return delegate.createOrUpdate(context, querySpec, updater, parent, entityGenerator);
    }

    public boolean updateAll(
            String parentKey,
            int start,
            int numRows,
            DetachedCriteria criteria,
            Function<T, T> updater) {
        return delegate.updateAll(tenantId, parentKey, start, numRows, criteria, updater);
    }

    /**
     * Updates a batch of entities within a specific shard based on a query and an update function.
     * <p>
     * This method performs an update operation on a batch of entities within a specific shard,
     * as determined by the provided parent key and shard calculator. It retrieves a specified
     * number of entities that match the criteria defined in the provided QuerySpec object, applies
     * the provided updater function to each entity, and updates the entities in the database.
     *
     * @param parentKey  A string representing the parent key that determines the shard for
     *                   the update operation.
     * @param start      The starting index for selecting entities to update (pagination).
     * @param numResults The number of entities to retrieve and update (pagination).
     * @param querySpec  A QuerySpec object specifying the criteria for selecting entities to update.
     * @param updater    A function that takes an old entity, applies updates, and returns a new entity.
     * @return true if all entities were successfully updated, false otherwise.
     * @throws RuntimeException If any exception occurs during the update operation or if
     *                          criteria are not met, it is wrapped in a RuntimeException and
     *                          propagated.
     */
    public boolean updateAll(
            String parentKey,
            int start,
            int numResults,
            QuerySpec<T, T> querySpec,
            Function<T, T> updater) {
        return delegate.updateAll(tenantId, parentKey, start, numResults, querySpec, updater);
    }

    public List<T> select(String parentKey, DetachedCriteria criteria, int start, int numResults) throws Exception {
        return delegate.select(tenantId, parentKey, criteria, start, numResults, t -> t);
    }

    /**
     * Executes a database query within a specific shard, retrieving a list of query results.
     * <p>
     * This method performs a database query on a specific shard, as determined by the provided
     * parent key and shard calculator. It retrieves a specified number of results starting from
     * a given index and returns them as a list. The query results are processed using a default
     * identity function.
     *
     * @param parentKey  A string representing the parent key that determines the shard for
     *                   the query.
     * @param querySpec  A QuerySpec object specifying the query criteria and projection.
     * @param start      The starting index for the query results (pagination).
     * @param numResults The number of results to retrieve from the query (pagination).
     * @return A List of query results of type T.
     * @throws Exception If any exception occurs during the query execution, it is propagated.
     */
    public List<T> select(String parentKey, QuerySpec<T, T> querySpec, int start, int numResults) throws Exception {
        return delegate.select(tenantId, parentKey, querySpec, start, numResults, t -> t);
    }

    public <U> U select(
            String parentKey,
            DetachedCriteria criteria,
            int start,
            int numResults,
            Function<List<T>, U> handler) throws Exception {
        return delegate.select(tenantId, parentKey, criteria, start, numResults, handler);
    }

    /**
     * Executes a database query within a specific shard, retrieving and processing the results.
     * <p>
     * This method performs a database query on a specific shard, as determined by the provided
     * parent key and shard calculator. It retrieves a specified number of results starting from
     * a given index, processes the results using the provided handler function, and returns the
     * result of the handler function.
     *
     * @param parentKey  A string representing the parent key that determines the shard for
     *                   the query.
     * @param querySpec  A QuerySpec object specifying the query criteria and projection.
     * @param start      The starting index for the query results (pagination).
     * @param numResults The number of results to retrieve from the query (pagination).
     * @param handler    A function that processes the list of query results and returns a result
     *                   of type U.
     * @param <U>        The type of result to be returned by the handler function.
     * @return The result of applying the handler function to the query results.
     * @throws Exception If any exception occurs during the query execution or result
     *                   processing, it is propagated.
     */
    public <U> U select(
            String parentKey,
            QuerySpec<T, T> querySpec,
            int start,
            int numResults,
            Function<List<T>, U> handler) throws Exception {
        return delegate.select(tenantId, parentKey, querySpec, start, numResults, handler);
    }

    public long count(String parentKey, DetachedCriteria criteria) {
        return delegate.count(tenantId, parentKey, criteria);
    }

    /**
     * Counts the number of records matching a specified query in a given shard
     * <p>
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
        return delegate.count(tenantId, parentKey, querySpec);
    }


    public boolean exists(String parentKey, Object key) {
        return delegate.exists(tenantId, parentKey, key);
    }

    /**
     * Queries using the specified criteria across all shards and returns the counts of rows satisfying
     * the criteria.
     * <b>Note:</b> This method runs the query serially and it's usage is not recommended.
     *
     * @param criteria The select criteria
     * @return List of counts in each shard
     */
    public List<Long> countScatterGather(DetachedCriteria criteria) {
        return delegate.countScatterGather(tenantId, criteria);
    }

    public List<Long> countScatterGather(QuerySpec<T, Long> criteria) {
        return delegate.countScatterGather(tenantId, criteria);
    }

    public List<T> scatterGather(DetachedCriteria criteria, int start, int numRows) {
        return delegate.scatterGather(tenantId, criteria, start, numRows);
    }

    /**
     * Executes a scatter-gather operation across multiple Data Access Objects (DAOs) in a serial manner
     *
     * @param querySpec A QuerySpec object specifying the query to execute.
     * @param start     The starting index for the query results (pagination).
     * @param numRows   The number of rows to retrieve in the query results (pagination).
     * @return A List of type T containing the aggregated query results from all shards.
     * @throws RuntimeException If any exception occurs during the execution of queries on
     *                          individual shards, it is wrapped in a RuntimeException and
     *                          propagated.
     */
    public List<T> scatterGather(QuerySpec<T, T> querySpec, int start, int numRows) {
        return delegate.scatterGather(tenantId, querySpec, start, numRows);
    }

    protected Field getKeyField() {
        return delegate.getKeyField();
    }

    public ReadOnlyContext<T> readOnlyExecutor(final String parentKey,
                                               final Object key) {
        return new ReadOnlyContext<>(delegate.readOnlyExecutor(tenantId, parentKey, key));
    }

    public ReadOnlyContext<T> readOnlyExecutor(final String parentKey,
                                               final Object key,
                                               final UnaryOperator<Criteria> criteriaUpdater) {
        return readOnlyExecutor(parentKey, key, criteriaUpdater, () -> false);
    }

    /**
     * Creates and returns a read-only context for executing read operations on an entities for provided {@code querySpec}
     *
     * <p>This method calculates the shard ID based on the provided {@code parentKey}, retrieves the SelectParamPriv
     * for the corresponding shard, and creates a read-only context for executing read operations on the entities.
     *
     * @param parentKey       parentKey of the entity will be used to decide shard.
     * @param key             used to provide parent key to be pulld
     * @param criteriaUpdater Function to update criteria to add additional params
     * @param entityPopulator A supplier that determines whether entity population should be performed.
     * @return A new ReadOnlyContext for executing read operations on the selected entity.
     */
    public ReadOnlyContext<T> readOnlyExecutor(final String parentKey,
                                               final Object key,
                                               final UnaryOperator<Criteria> criteriaUpdater,
                                               final Supplier<Boolean> entityPopulator) {
        return new ReadOnlyContext<>(
                delegate.readOnlyExecutor(tenantId, parentKey, key, criteriaUpdater, entityPopulator)
        );
    }

    public ReadOnlyContext<T> readOnlyExecutor(final String parentKey,
                                               final DetachedCriteria criteria,
                                               final int first,
                                               final int numResults) {
        return readOnlyExecutor(parentKey, criteria, first, numResults, () -> false);
    }

    public ReadOnlyContext<T> readOnlyExecutor(final String parentKey,
                                               final QuerySpec<T, T> querySpec,
                                               final int first,
                                               final int numResults) {
        return readOnlyExecutor(parentKey, querySpec, first, numResults, () -> false);
    }

    /**
     * Creates and returns a read-only context for executing read operations on an entities for provided {@code querySpec}
     *
     * <p>This method calculates the shard ID based on the provided {@code parentKey}, retrieves the SelectParamPriv
     * for the corresponding shard, and creates a read-only context for executing read operations on the entities.
     *
     * @param parentKey       parentKey of the entity will be used to decide shard.
     * @param criteria        used to provide query details to fetch parent entities
     * @param first           The index of the first parent entity to retrieve.
     * @param numResults      The maximum number of parent entities to retrieve.
     * @param entityPopulator A supplier that determines whether entity population should be performed.
     * @return A new ReadOnlyContext for executing read operations on the selected entities.
     */
    public ReadOnlyContext<T> readOnlyExecutor(final String parentKey,
                                               final DetachedCriteria criteria,
                                               final int first,
                                               final int numResults,
                                               final Supplier<Boolean> entityPopulator) {
        return new ReadOnlyContext<>(
                delegate.readOnlyExecutor(tenantId, parentKey, criteria, first, numResults, entityPopulator)
        );
    }

    /**
     * Creates and returns a read-only context for executing read operations on an entities for provided {@code querySpec}
     *
     * <p>This method calculates the shard ID based on the provided {@code parentKey}, retrieves the SelectParamPriv
     * for the corresponding shard, and creates a read-only context for executing read operations on the entities.
     *
     * @param parentKey       parentKey of the entity will be used to decide shard.
     * @param querySpec       used to provide query details to fetch parent entities
     * @param first           The index of the first parent entity to retrieve.
     * @param numResults      The maximum number of parent entities to retrieve.
     * @param entityPopulator A supplier that determines whether entity population should be performed.
     * @return A new ReadOnlyContext for executing read operations on the selected entities.
     */
    public ReadOnlyContext<T> readOnlyExecutor(final String parentKey,
                                               final QuerySpec<T, T> querySpec,
                                               final int first,
                                               final int numResults,
                                               final Supplier<Boolean> entityPopulator) {
        return new ReadOnlyContext<>(
                delegate.readOnlyExecutor(tenantId, parentKey, querySpec, first, numResults, entityPopulator)
        );
    }

    @Override
    public ShardCalculator<String> getShardCalculator() {
        return delegate.getShardCalculator();
    }

    /**
     * Class to get detail about mapping association between parent and child entity
     */
    @Builder
    @Getter
    public static class AssociationMappingSpec {
        private String parentMappingKey;
        private String childMappingKey;

        public MultiTenantRelationalDao.AssociationMappingSpec toMultiTenantAssociationMappingSpec() {
            return MultiTenantRelationalDao.AssociationMappingSpec.builder()
                    .childMappingKey(childMappingKey)
                    .parentMappingKey(parentMappingKey)
                    .build();
        }
    }

    /**
     * This is wrapper class to provide details for fetching child entities
     * <ul>
     *   <li>associationMappingSpecs : child and parent column mapping details can be given here,
     *      which are used to take equality join with parent table</li>
     *   <li>criteria : querying child using {@link org.hibernate.criterion.DetachedCriteria}</li>
     *   <li>querySpec : querying child using {@link io.appform.dropwizard.sharding.query.QuerySpec}.</li>
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

        public MultiTenantRelationalDao.QueryFilterSpec<T> toMultiTenantSpec() {
            return MultiTenantRelationalDao.QueryFilterSpec.<T>builder()
                    .associationMappingSpecs(toAssociationMappings(associationMappingSpecs))
                    .criteria(criteria)
                    .querySpec(querySpec)
                    .build();

        }

        private List<MultiTenantRelationalDao.AssociationMappingSpec> toAssociationMappings(
                final List<AssociationMappingSpec> associationMappingSpecs) {
            if (associationMappingSpecs == null) {
                return null;
            }

            return associationMappingSpecs.stream()
                    .map(associationMappingSpec -> associationMappingSpec.toMultiTenantAssociationMappingSpec())
                    .collect(Collectors.toList());
        }
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

        private final MultiTenantRelationalDao.ReadOnlyContext<T> delegate;

        public ReadOnlyContext(final MultiTenantRelationalDao.ReadOnlyContext<T> delegate) {
            this.delegate = delegate;
        }

        public ReadOnlyContext<T> apply(final Consumer<List<T>> handler) {
            delegate.apply(handler);
            return this;
        }

        public <U> ReadOnlyContext<T> readAugmentParent(
                final RelationalDao<U> relationalDao,
                final QueryFilterSpec<U> queryFilterSpec,
                final int first,
                final int numResults,
                final BiConsumer<T, List<U>> consumer) {
            delegate.readAugmentParent(relationalDao.getDelegate(), queryFilterSpec.toMultiTenantSpec(), first, numResults, consumer);
            return this;
        }

        /**
         * <p> This method first tries to executeImpl() operations. If the resulting entity is null,
         * this method tries to generate the populate the entity in database by calling {@code entityPopulator}
         * If {@code entityPopulator} returns true, it is expected that entity is indeed populated in the database
         * and hence {@code executeImpl()} is called again
         *
         * @return An optional containing the retrieved entity, or an empty optional if not found.
         */
        public Optional<List<T>> execute() {
            return delegate.execute();
        }
    }
}
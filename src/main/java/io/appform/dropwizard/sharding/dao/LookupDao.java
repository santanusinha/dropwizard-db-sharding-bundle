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

import io.appform.dropwizard.sharding.DBShardingBundleBase;
import io.appform.dropwizard.sharding.ShardInfoProvider;
import io.appform.dropwizard.sharding.config.ShardingBundleOptions;
import io.appform.dropwizard.sharding.observers.TransactionObserver;
import io.appform.dropwizard.sharding.query.QuerySpec;
import io.appform.dropwizard.sharding.scroll.ScrollPointer;
import io.appform.dropwizard.sharding.scroll.ScrollResult;
import io.appform.dropwizard.sharding.sharding.LookupKey;
import io.appform.dropwizard.sharding.utils.ShardCalculator;
import lombok.Getter;
import lombok.NonNull;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.Criteria;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.criterion.DetachedCriteria;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/**
 * A dao to manage lookup and top level elements in the system. Can save and retrieve an object (tree) from any shard.
 * <b>Note:</b>
 * - The element must have only one String key for lookup.
 * - The key needs to be annotated with {@link LookupKey}
 * The entity can be retrieved from any shard using the key.
 */
@Slf4j
public class LookupDao<T> implements ShardedDao<T> {

    private final String dbNamespace;

    private final MultiTenantLookupDao<T> delegate;

    public LookupDao(final String dbNamespace,
                     final MultiTenantLookupDao<T> delegate) {
        this.dbNamespace = dbNamespace;
        this.delegate = delegate;
    }

    /**
     * Constructs a LookupDao instance for querying and managing entities across multiple shards.
     * <p>
     * This constructor initializes a LookupDao instance for working with entities of the specified class
     * distributed across multiple shards. It requires a list of session factories, a shard calculator,
     * sharding options, a shard information provider, and a transaction observer.
     *
     * @param sessionFactories  A list of SessionFactory instances for database access across shards.
     * @param entityClass       The Class representing the type of entities managed by this LookupDao.
     * @param shardCalculator   A ShardCalculator instance used to determine the shard for each operation.
     * @param shardingOptions   ShardingBundleOptions specifying additional sharding configuration options.
     * @param shardInfoProvider A ShardInfoProvider for retrieving shard information.
     * @param observer          A TransactionObserver for monitoring transaction events.
     * @throws IllegalArgumentException If the entity class does not have exactly one field marked as LookupKey,
     *                                  if the key field is not accessible, or if it is not of type String.
     */
    public LookupDao(
            List<SessionFactory> sessionFactories,
            Class<T> entityClass,
            ShardCalculator<String> shardCalculator,
            ShardingBundleOptions shardingOptions,
            final ShardInfoProvider shardInfoProvider,
            final TransactionObserver observer) {
        this.dbNamespace = DBShardingBundleBase.DEFAULT_NAMESPACE;
        this.delegate = new MultiTenantLookupDao<>(
                Map.of(dbNamespace, sessionFactories),
                entityClass,
                shardCalculator,
                Map.of(dbNamespace, shardingOptions),
                Map.of(dbNamespace, shardInfoProvider),
                observer
        );
    }

    /**
     * Get an object on the basis of key (value of field annotated with {@link LookupKey}) from any shard.
     * <b>Note:</b> Lazy loading will not work once the object is returned.
     * If you need lazy loading functionality use the alternate {@link #get(String, Function)} method.
     *
     * @param key The value of the key field to look for.
     * @return The entity
     * @throws Exception if backing dao throws
     */
    public Optional<T> get(String key) throws Exception {
        return Optional.ofNullable(get(key, x -> x, t -> t));
    }

    public Optional<T> get(String key, UnaryOperator<Criteria> criteriaUpdater) throws Exception {
        return Optional.ofNullable(get(key, criteriaUpdater, t -> t));
    }

    /**
     * Get an object on the basis of key (value of field annotated with {@link LookupKey}) from any shard
     * and applies the provided function/lambda to it. The return from the handler becomes the return to the get
     * function.
     * <b>Note:</b> The transaction is open when handler is applied. So lazy loading will work inside the handler.
     * Once get returns, lazy loading will nt owrok.
     *
     * @param key     The value of the key field to look for.
     * @param handler Handler function/lambda that receives the retrieved object.
     * @return Whatever is returned by the handler function
     * @throws Exception if backing dao throws
     */
    public <U> U get(String key, Function<T, U> handler) throws Exception {
        return delegate.get(dbNamespace, key, handler);
    }

    @SuppressWarnings("java:S112")
    public <U> U get(String key, UnaryOperator<Criteria> criteriaUpdater, Function<T, U> handler)
            throws Exception {
        return delegate.get(dbNamespace, key, criteriaUpdater, handler);
    }

    /**
     * Check if object with specified key exists in any shard.
     *
     * @param key id of the element to look for
     * @return true/false depending on if it's found or not.
     * @throws Exception if backing dao throws
     */
    public boolean exists(String key) throws Exception {
        return get(key).isPresent();
    }

    /**
     * Saves an entity on proper shard based on hash of the value in the key field in the object.
     * The updated entity is returned. If Cascade is specified, this can be used
     * to save an object tree based on the shard of the top entity that has the key field.
     * <b>Note:</b> Lazy loading will not work on the augmented entity. Use the alternate
     * {@link #save(Object, Function)} for that.
     *
     * @param entity Entity to save
     * @return Entity
     * @throws Exception if backing dao throws
     */
    public Optional<T> save(T entity) throws Exception {
        return Optional.ofNullable(save(entity, t -> t));
    }

    /**
     * Save an object on the basis of key (value of field annotated with {@link LookupKey}) to target shard
     * and applies the provided function/lambda to it. The return from the handler becomes the return to the get
     * function.
     * <b>Note:</b> Handler is executed in the same transactional context as the save operation.
     * So any updates made to the object in this context will also get persisted.
     *
     * @param entity  The value of the key field to look for.
     * @param handler Handler function/lambda that receives the retrieved object.
     * @return The entity
     * @throws Exception if backing dao throws
     */
    public <U> U save(T entity, Function<T, U> handler) throws Exception {
        return delegate.save(dbNamespace, entity, handler);
    }

    public Optional<T> createOrUpdate(
            String id,
            UnaryOperator<T> updater,
            Supplier<T> entityGenerator) {
        return delegate.createOrUpdate(dbNamespace, id, updater, entityGenerator);
    }


    /**
     * Updates an entity. For this update, first a lock is taken on database on selected row (using <i>for update</i>
     * semantics)
     * and {@code updater} is applied on the retrieved entity. It is prudent to not perform any time-consuming
     * activity inside
     * {@code updater} to prevent long lasting locks on database
     *
     * @param id      The ID of the entity to update.
     * @param updater A function that takes an optional entity and returns the updated entity.
     * @return True if the update was successful, false otherwise.
     */
    public boolean updateInLock(String id, Function<Optional<T>, T> updater) {
        return delegate.updateInLock(dbNamespace, id, updater);
    }

    /**
     * Updates an entity within the shard identified by the provided {@code id} based on the
     * transformation defined by the {@code updater} function
     *
     * <p>This method is commonly used for modifying the state of an existing entity within the shard
     * by applying a transformation defined by the {@code updater} function. The {@code updater} function
     * takes an optional existing entity (if present) and returns the updated entity.
     *
     * @param id      The unique identifier of the entity to be updated.
     * @param updater A function that defines the transformation to be applied to the entity.
     *                It takes an optional existing entity as input and returns the updated entity.
     * @return {@code true} if the entity is successfully updated, {@code false} if it does not exist.
     */
    public boolean update(String id, Function<Optional<T>, T> updater) {
        return delegate.update(dbNamespace, id, updater);
    }

    /**
     * Executes an update operation within the shard based on a predefined query defined in the
     * provided {@code updateOperationMeta}. This method is commonly used for performing batch
     * updates or modifications to entities matching specific criteria.
     *
     * <p>The update operation is specified by the {@code updateOperationMeta} object, which includes
     * the name of the named query to be executed and any parameters required for the query.
     *
     * @param id                  The unique identifier or key associated with the shard where the
     *                            update operation will be performed.
     * @param updateOperationMeta The metadata defining the update operation, including the named
     *                            query and parameters.
     * @return The number of entities affected by the update operation.
     */
    public int updateUsingQuery(String id, UpdateOperationMeta updateOperationMeta) {
        return delegate.updateUsingQuery(dbNamespace, id, updateOperationMeta);
    }


    /**
     * Creates and returns a locked context for executing write operations on an entity with the specified ID.
     *
     * <p>This method calculates the shard ID based on the provided entity ID, retrieves the LookupDaoPriv
     * for the corresponding shard, and creates a locked context for executing write operations on the entity.
     * The entity is locked for write access within the database transaction.
     *
     * @param id The ID of the entity for which the locked context is created.
     * @return A new LockedContext for executing write operations on the specified entity with write access.
     * @throws java.lang.RuntimeException If an error occurs during entity locking or transaction management.
     */
    public LockedContext<T> lockAndGetExecutor(final String id) {
        return delegate.lockAndGetExecutor(dbNamespace, id);
    }

    public ReadOnlyContext<T> readOnlyExecutor(String id) {
        return readOnlyExecutor(id, x -> x);
    }

    /**
     * Creates and returns a read-only context for executing read operations on an entity with the specified ID.
     *
     * <p>This method calculates the shard ID based on the provided entity ID, retrieves the LookupDaoPriv
     * for the corresponding shard, and creates a read-only context for executing read operations on the entity.
     * It does not perform entity population during read operations.
     *
     * @param id              The ID of the entity for which the read-only context is created.
     * @param criteriaUpdater A method that lets clients add additional changes to the criteria before the get
     * @return A new ReadOnlyContext for executing read operations on the specified entity.
     */
    public ReadOnlyContext<T> readOnlyExecutor(String id, UnaryOperator<Criteria> criteriaUpdater) {
        return new ReadOnlyContext<>(delegate.readOnlyExecutor(dbNamespace, id, criteriaUpdater));
    }

    public ReadOnlyContext<T> readOnlyExecutor(String id, Supplier<Boolean> entityPopulator) {
        return readOnlyExecutor(id, x -> x, entityPopulator);
    }

    /**
     * Creates and returns a read-only context for executing read operations on an entity with the specified ID,
     * optionally allowing entity population.
     *
     * <p>This method calculates the shard ID based on the provided entity ID, retrieves the LookupDaoPriv
     * for the corresponding shard, and creates a read-only context for executing read operations on the entity.
     * If the ID does not exist in database, entityPopulator is used to populate the entity
     *
     * @param id              The ID of the entity for which the read-only context is created.
     * @param entityPopulator A supplier that determines whether entity population should be performed.
     * @return A new ReadOnlyContext for executing read operations on the specified entity.
     */

    public ReadOnlyContext<T> readOnlyExecutor(
            String id,
            UnaryOperator<Criteria> criteriaUpdater,
            Supplier<Boolean> entityPopulator) {
        return new ReadOnlyContext<>(delegate.readOnlyExecutor(dbNamespace, id, criteriaUpdater, entityPopulator));
    }


    /**
     * Saves an entity to the database and obtains a locked context for further operations.
     *
     * <p>This method first retrieves the ID of the provided entity and determines the shard where it should
     * be saved based on the ID. It then saves the entity to the corresponding shard in the database and returns
     * a LockedContext for further operations on the saved entity.
     *
     * @param entity The entity to be saved to the database.
     * @return A LockedContext that allows further operations on the saved entity within a locked context.
     * @throws java.lang.RuntimeException If an error occurs during entity saving or transaction management.
     */
    public LockedContext<T> saveAndGetExecutor(T entity) {
        return delegate.saveAndGetExecutor(dbNamespace, entity);
    }

    /**
     * Queries using the specified criteria across all shards and returns the result.
     * <b>Note:</b> This method runs the query serially, and it's usage is not recommended.
     * Performs a scatter-gather operation by executing a query on all database shards
     * and collecting the results into a list of entities.
     *
     * @param criteria The DetachedCriteria object representing the query criteria to be executed
     *                 on all database shards.
     * @return A list of entities obtained by executing the query criteria on all available shards.
     */
    public List<T> scatterGather(DetachedCriteria criteria) {
        return delegate.scatterGather(dbNamespace, criteria);
    }

    /**
     * Performs a scatter-gather operation by executing a query on all database shards
     * and collecting the results into a list of entities.
     *
     * <p>This method executes the provided QuerySpec on all available database shards serially,
     * retrieving entities that match the query criteria from each shard. The results are then collected
     * into a single list of entities, effectively performing a scatter-gather operation.
     *
     * @param querySpec The QuerySpec object representing the query criteria to be executed
     *                  on all database shards.
     * @return A list of entities obtained by executing the query on all available shards.
     * @throws java.lang.RuntimeException If an error occurs while querying the database.
     */
    public List<T> scatterGather(final QuerySpec<T, T> querySpec) {
        return delegate.scatterGather(dbNamespace, querySpec);
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
     * @param pageSize      Page size of scroll result
     * @param sortFieldName Field to sort by. For correct sorting, the field needs to be an ever-increasing one
     * @return A {@link ScrollResult} object that contains a {@link ScrollPointer} and a list of results with
     * max N * pageSize elements
     */
    public ScrollResult<T> scrollDown(
            final DetachedCriteria inCriteria,
            final ScrollPointer inPointer,
            final int pageSize,
            @NonNull final String sortFieldName) {
        return delegate.scrollDown(dbNamespace, inCriteria, inPointer, pageSize, sortFieldName);
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
        return delegate.scrollUp(dbNamespace, inCriteria, inPointer, pageSize, sortFieldName);
    }

    /**
     * Counts the number of entities that match the specified criteria on each database shard.
     *
     * <p>This method executes a count operation on all available database shards serially,
     * counting the entities that satisfy the provided criteria on each shard. The results are then
     * collected into a list, where each element corresponds to the count of matching entities on
     * a specific shard.
     *
     * @param criteria The DetachedCriteria object representing the criteria for counting entities.
     * @return A list of counts, where each count corresponds to the number of entities matching
     * the criteria on a specific shard.
     * @throws java.lang.RuntimeException If an error occurs while querying the database.
     */
    public List<Long> count(DetachedCriteria criteria) {
        return delegate.count(dbNamespace, criteria);
    }

    /**
     * Run arbitrary read-only queries on all shards and return results.
     *
     * @param criteria The detached criteria. Typically, a grouping or counting query
     * @return A map of shard vs result-list
     */
    @SuppressWarnings("rawtypes")
    public Map<Integer, List<T>> run(DetachedCriteria criteria) {
        return delegate.run(dbNamespace, criteria);
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
    public <U> U run(DetachedCriteria criteria, Function<Map<Integer, List<T>>, U> translator) {
        return delegate.run(dbNamespace, criteria, translator);
    }

    /**
     * Retrieves a list of entities associated with the specified keys from the database.
     *
     * <p>This method groups the provided keys by their corresponding database shards,
     * and then retrieves entities that match these keys from each shard serially.
     * The results are combined into a single list of entities and returned.
     *
     * @param keys A list of keys for which entities should be retrieved from the database.
     * @return A list of entities obtained by querying the database for the specified keys.
     * @throws java.lang.RuntimeException If an error occurs while querying the database.
     */
    public List<T> get(List<String> keys) {
        return delegate.get(dbNamespace, keys);
    }


    /**
     * Executes a function within a database session on the shard corresponding to the provided ID.
     *
     * <p>This method acquires a database session for the shard associated with the specified ID
     * and executes the provided handler function within that session. It ensures that the session is
     * properly managed, including transaction handling, and returns the result of the handler function.
     *
     * @param <U>     The type of the result returned by the handler function.
     * @param id      The ID used to determine the shard where the session will be acquired.
     * @param handler A function that takes a database session and returns a result of type U.
     * @return The result of executing the handler function within the acquired database session.
     * @throws java.lang.RuntimeException If an error occurs during database session management or while executing
     *                                    the handler.
     */
    public <U> U runInSession(String id, Function<Session, U> handler) {
        return delegate.runInSession(dbNamespace, id, handler);
    }

    public <U, V> V runInSession(
            BiFunction<Integer, Session, U> sessionHandler,
            Function<Map<Integer, U>, V> translator) {
        return delegate.runInSession(dbNamespace, sessionHandler, translator);
    }

    /**
     * Deletes an entity with the specified ID from the database.
     *
     * <p>This method identifies the shard associated with the provided ID, then executes a delete operation
     * on the entity with the matching ID within that shard. It returns true if the delete operation was successful
     * and false otherwise.
     *
     * @param id The ID of the entity to be deleted from the database.
     * @return True if the entity was successfully deleted, false otherwise.
     * @throws java.lang.RuntimeException If an error occurs during the delete operation or transaction management.
     */
    public boolean delete(String id) {
        return delegate.delete(dbNamespace, id);
    }

    /**
     * Retrieves the key field associated with the entity class.
     * <p>
     * This method returns the Field object representing the key field associated with the entity class.
     *
     * @return The Field object representing the key field of the entity class.
     */
    protected Field getKeyField() {
        return delegate.getKeyField();
    }

    @Override
    public ShardCalculator<String> getShardCalculator() {
        return delegate.getShardCalculator();
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

        private final MultiTenantLookupDao.ReadOnlyContext<T> delegate;

        public ReadOnlyContext(
                final MultiTenantLookupDao.ReadOnlyContext<T> delegate) {
            this.delegate = delegate;
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
            delegate.apply(handler);
            return this;
        }

        /**
         * Read and augment parent entities based on a DetachedCriteria, retrieving a single related entity
         *
         * <p>This method reads and augments parent entities based on the specified {@code criteria}, retrieving only a
         * single child entity, and then applies the provided {@code consumer} function to augment it with related child
         * entity. The consumer function is applied to parent entity.</p>
         *
         * @param <U>           The type of child entities.
         * @param relationalDao The relational data access object used to retrieve child entities.
         * @param criteria      The DetachedCriteria for selecting and composing parent entities.
         * @param consumer      A function that applies the child entity augmentation to the parent entity.
         * @return This {@code ReadOnlyContext} instance to allow for method chaining.
         * @throws RuntimeException if an error occurs during the read operation or when applying the consumer function.
         */
        public <U> ReadOnlyContext<T> readOneAugmentParent(
                RelationalDao<U> relationalDao,
                DetachedCriteria criteria,
                BiConsumer<T, List<U>> consumer) {
            delegate.readOneAugmentParent(relationalDao.getDelegate(), criteria, consumer);
            return this;
        }

        /**
         * Read and augment parent entities based on a QuerySpec, retrieving a single related entity and applying
         * operation.
         *
         * <p>This method reads and augments parent entities based on the specified {@code querySpec}, retrieving only a
         * single child entity, and then applies the provided {@code consumer} function to augment parent with the
         * retrieved
         * child entity.</p>
         *
         * @param <U>           The type of child entities.
         * @param relationalDao The relational data access object used to retrieve child entities.
         * @param querySpec     The QuerySpec for selecting and composing parent entities.
         * @param consumer      A function that applies the child entity augmentation to the parent entity.
         * @return This {@code ReadOnlyContext} instance to allow for method chaining.
         * @throws RuntimeException if an error occurs during the read operation or when applying the consumer function.
         */
        public <U> ReadOnlyContext<T> readOneAugmentParent(
                RelationalDao<U> relationalDao,
                QuerySpec<U, U> querySpec,
                BiConsumer<T, List<U>> consumer) {
            delegate.readOneAugmentParent(relationalDao.getDelegate(), querySpec, consumer);
            return this;
        }

        /**
         * Read and augment parent entities based on a DetachedCriteria and apply operations selectively.
         *
         * <p>This method augments parent entities based on the child entities selected through specified {@code
         * criteria}
         * The provided {@code consumer} function is then applied to augment the selected parent
         * entity with related child entities.</p>
         *
         * @param <U>           The type of child entities.
         * @param relationalDao The relational data access object used to retrieve child entities.
         * @param criteria      The DetachedCriteria for selecting and composing parent entities.
         * @param first         The index of the first parent entity to retrieve.
         * @param numResults    The maximum number of parent entities to retrieve.
         * @param consumer      A function that applies the child entity augmentation to the parent entities.
         * @return This {@code ReadOnlyContext} instance to allow for method chaining.
         * @throws RuntimeException if an error occurs during the read operation or when applying the consumer function.
         */
        public <U> ReadOnlyContext<T> readAugmentParent(
                RelationalDao<U> relationalDao,
                DetachedCriteria criteria,
                int first,
                int numResults,
                BiConsumer<T, List<U>> consumer) {
            delegate.readAugmentParent(relationalDao.getDelegate(), criteria, first, numResults, consumer);
            return this;
        }

        /**
         * Read and augment parent entities based on a {@link io.appform.dropwizard.sharding.query.QuerySpec} and
         * apply operations selectively.
         *
         * <p>This method augments parent entity based on the child entities selected through specified
         * {@link io.appform.dropwizard.sharding.query.QuerySpec}
         * The provided {@code consumer} function is then applied to augment the selected parent entity with related
         * child entities.</p>
         *
         * @param <U>           The type of child entities.
         * @param relationalDao The relational data access object used to retrieve child entities.
         * @param querySpec     The QuerySpec for selecting and composing parent entities.
         * @param first         The index of the first parent entity to retrieve.
         * @param numResults    The maximum number of parent entities to retrieve.
         * @param consumer      A function that applies the child entity augmentation to the parent entities.
         * @return This {@code ReadOnlyContext} instance to allow for method chaining.
         * @throws RuntimeException if an error occurs during the read operation or when applying the consumer function.
         */
        public <U> ReadOnlyContext<T> readAugmentParent(
                RelationalDao<U> relationalDao,
                QuerySpec<U, U> querySpec,
                int first,
                int numResults,
                BiConsumer<T, List<U>> consumer) {
            delegate.readAugmentParent(relationalDao.getDelegate(), querySpec, first, numResults, consumer);
            return this;
        }

        /**
         * Read and augment parent entity based on a {@link org.hibernate.criterion.DetachedCriteria} and apply
         * operations selectively.
         *
         * <p>This method augments parent entity based on the single child entity selected through specified
         * {@link org.hibernate.criterion.DetachedCriteria}
         * The provided {@code consumer} function is then applied to augment the selected parent entity with related
         * child entities.</p>
         * The filter function selectively applies the consumer function to the chosen parent entity.
         *
         * @param <U>           The type of child entities.
         * @param relationalDao The relational data access object used to retrieve child entities.
         * @param criteria      The DetachedCriteria for selecting parent entities.
         * @param consumer      A function that applies the child entity augmentation to the parent entity.
         * @param filter        A predicate function to filter the parent entity on which the consumer function is
         *                      applied.
         * @return This {@code ReadOnlyContext} instance to allow for method chaining.
         * @throws RuntimeException if an error occurs during the read operation or when applying the consumer function.
         *                          {@code readOneAugmentParent} method that accepts a {@code QuerySpec} for better
         *                          query composition and
         *                          type-safety.
         */
        public <U> ReadOnlyContext<T> readOneAugmentParent(
                RelationalDao<U> relationalDao,
                DetachedCriteria criteria,
                BiConsumer<T, List<U>> consumer,
                Predicate<T> filter) {
            delegate.readOneAugmentParent(relationalDao.getDelegate(), criteria, consumer, filter);
            return this;
        }

        /**
         * Read and augment parent entity based on a {@link io.appform.dropwizard.sharding.query.QuerySpec} and apply
         * operations selectively.
         *
         * <p>This method augments parent entity based on the single child entity selected through specified
         * {@link io.appform.dropwizard.sharding.query.QuerySpec}
         * The provided {@code consumer} function is then applied to augment the selected parent entity with related
         * child entities.</p>
         * The filter function selectively applies the consumer function to the chosen parent entity.
         *
         * @param <U>           The type of child entities.
         * @param relationalDao The relational data access object used to retrieve child entities.
         * @param querySpec     The query specification for selecting parent entities.
         * @param consumer      A function that applies the child entity augmentation to the parent entity.
         * @param filter        A predicate function to filter the parent entity on which the consumer function is
         *                      applied.
         * @return This {@code ReadOnlyContext} instance to allow for method chaining.
         * @throws RuntimeException if an error occurs during the read operation or when applying the consumer function.
         */
        public <U> ReadOnlyContext<T> readOneAugmentParent(
                RelationalDao<U> relationalDao,
                QuerySpec<U, U> querySpec,
                BiConsumer<T, List<U>> consumer,
                Predicate<T> filter) {
            delegate.readOneAugmentParent(relationalDao.getDelegate(), querySpec, consumer, filter);
            return this;
        }

        public <U> ReadOnlyContext<T> readAugmentParent(
                RelationalDao<U> relationalDao,
                DetachedCriteria criteria,
                int first,
                int numResults,
                BiConsumer<T, List<U>> consumer,
                Predicate<T> filter) {
            delegate.readAugmentParent(relationalDao.getDelegate(), criteria, first, numResults, consumer, filter);
            return this;
        }


        /**
         * Reads and augments a parent entity using a relational DAO, applying a filter and consumer function.
         * <p>
         * This method reads and potentially augments a parent entity using a provided relational DAO
         * and query specification within the current context. It applies a filter to the parent entity
         * and, if the filter condition is met, executes a query to retrieve related child entities.
         * The retrieved child entities are then passed to a consumer function for further processing </p>
         *
         * @param relationalDao A RelationalDao representing the DAO for retrieving child entities.
         * @param querySpec     A QuerySpec specifying the criteria for selecting child entities.
         * @param first         The index of the first result to retrieve (pagination).
         * @param numResults    The number of child entities to retrieve (pagination).
         * @param consumer      A BiConsumer for processing the parent entity and its child entities.
         * @param filter        A Predicate for filtering parent entities to decide whether to process them.
         * @return A ReadOnlyContext representing the current context.
         * @throws RuntimeException If any exception occurs during the execution of the query or processing
         *                          of the parent and child entities.
         */
        public <U> ReadOnlyContext<T> readAugmentParent(
                RelationalDao<U> relationalDao,
                QuerySpec<U, U> querySpec,
                int first,
                int numResults,
                BiConsumer<T, List<U>> consumer,
                Predicate<T> filter) {
            delegate.readAugmentParent(relationalDao.getDelegate(), querySpec, first, numResults, consumer, filter);
            return this;
        }

        /**
         * Executes the read-only operation within the shard, retrieves the entity, applies any custom
         * operations, and returns the result.
         *
         * <p> This method first tries to executeImpl() operations. If the resulting entity is null,
         * this method tries to generate the populate the entity in database by calling {@code entityPopulator}
         * If {@code entityPopulator} returns true, it is expected that entity is indeed populated in the database
         * and hence {@code executeImpl()} is called again
         *
         * @return An optional containing the retrieved entity, or an empty optional if not found.
         */
        public Optional<T> execute() {
            return delegate.execute();
        }
    }
}

package io.appform.dropwizard.sharding.dao;

import io.appform.dropwizard.sharding.ShardInfoProvider;
import io.appform.dropwizard.sharding.dao.operations.OpContext;
import io.appform.dropwizard.sharding.dao.operations.lockedcontext.LockAndExecute;
import io.appform.dropwizard.sharding.execution.DaoType;
import io.appform.dropwizard.sharding.execution.TransactionExecutionContext;
import io.appform.dropwizard.sharding.observers.TransactionObserver;
import io.appform.dropwizard.sharding.utils.TransactionHandler;
import lombok.Getter;
import lombok.val;
import org.hibernate.SessionFactory;
import org.hibernate.criterion.DetachedCriteria;

import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/**
 * Executes operations against a list of entities after all entities have been locked.
 *
 * @param <T> entity type
 */
@Getter
public class BulkLockedContext<T> implements LockedTransactionContext {

    private final String tenantId;
    private final int shardId;
    private final SessionFactory sessionFactory;
    private final TransactionExecutionContext executionContext;
    private final TransactionObserver observer;

    public BulkLockedContext(
            String tenantId,
            int shardId,
            SessionFactory sessionFactory,
            Supplier<List<T>> getter,
            Class<T> entityClass,
            ShardInfoProvider shardInfoProvider,
            TransactionObserver observer) {
        this.tenantId = tenantId;
        this.shardId = shardId;
        this.sessionFactory = sessionFactory;
        this.observer = observer;
        val opContext = LockAndExecute.<List<T>>buildForRead()
                .getter(getter)
                .build();
        this.executionContext = buildExecutionContext(shardInfoProvider, entityClass, opContext);
    }

    public BulkLockedContext<T> filterEach(
            Predicate<T> predicate,
            Function<T, RuntimeException> failureException) {
        return apply(entities -> entities.forEach(entity -> {
            if (!predicate.test(entity)) {
                throw Objects.requireNonNull(failureException.apply(entity));
            }
        }));
    }

    public BulkLockedContext<T> mutateEach(LockedContext.Mutator<T> mutator) {
        return apply(entities -> entities.forEach(mutator::mutator));
    }

    public <U> BulkLockedContext<T> lockAndMutate(
            LookupDao<U> lookupDao,
            String key,
            LockedContext.Mutator<U> mutator) {
        return apply(entities -> lookupDao.lockAndMutate(this, key, mutator));
    }

    public <U> BulkLockedContext<T> save(
            RelationalDao<U> relationalDao,
            Function<List<T>, U> entityGenerator) {
        return apply(entities -> relationalDao.save(this, entityGenerator.apply(entities)));
    }

    public <U> BulkLockedContext<T> update(
            RelationalDao<U> relationalDao,
            DetachedCriteria criteria,
            UnaryOperator<U> updater,
            BooleanSupplier updateNext) {
        return apply(entities -> relationalDao.update(this, criteria, entity -> {
            U updatedEntity = updater.apply(entity);
            boolean initiallyLocked = entities.stream()
                    .anyMatch(lockedEntity -> lockedEntity == (Object) entity);
            if (updatedEntity != null && updatedEntity != entity && initiallyLocked) {
                throw new IllegalArgumentException(
                        "Cannot replace an entity initially locked by BulkLockedContext");
            }
            return updatedEntity;
        }, updateNext));
    }

    public BulkLockedContext<T> apply(Consumer<List<T>> handler) {
        ((LockAndExecute<List<T>>) executionContext.getOpContext()).getOperations().add(handler);
        return this;
    }

    public List<T> execute() {
        return observer.execute(executionContext, () -> {
            TransactionHandler transactionHandler = new TransactionHandler(sessionFactory, false);
            transactionHandler.beforeStart();
            try {
                val opContext = (LockAndExecute<List<T>>) executionContext.getOpContext();
                return opContext.apply(transactionHandler.getSession());
            } catch (Exception e) {
                transactionHandler.onError();
                throw e;
            } finally {
                transactionHandler.afterEnd();
            }
        });
    }

    private TransactionExecutionContext buildExecutionContext(
            ShardInfoProvider shardInfoProvider,
            Class<T> entityClass,
            OpContext opContext) {
        return TransactionExecutionContext.builder()
                .commandName("execute")
                .shardName(shardInfoProvider.shardName(shardId))
                .entityClass(entityClass)
                .daoType(DaoType.RELATIONAL)
                .opContext(opContext)
                .build();
    }
}

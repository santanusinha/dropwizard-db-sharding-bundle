package io.appform.dropwizard.sharding.dao;

import com.google.common.collect.Lists;
import io.appform.dropwizard.sharding.ShardInfoProvider;
import io.appform.dropwizard.sharding.execution.TransactionExecutionContext;
import io.appform.dropwizard.sharding.observers.TransactionObserver;
import io.appform.dropwizard.sharding.utils.TransactionHandler;
import lombok.Getter;
import org.hibernate.SessionFactory;
import org.hibernate.criterion.DetachedCriteria;

import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

@Getter
public class LockedContext<T> {
    @FunctionalInterface
    public interface Mutator<T> {
        void mutator(T parent);
    }

    enum Mode {READ, INSERT}

    private final int shardId;
    private final String parent;
    private final SessionFactory sessionFactory;
    private final List<Function<T, Void>> operations = Lists.newArrayList();
    private Supplier<T> getter;
    private Function<T, T> saver;
    private T entity;
    private final Mode mode;
    private final TransactionExecutionContext executionContext;
    private final TransactionObserver observer;

    public LockedContext(
            int shardId,
            String parent,
            SessionFactory sessionFactory,
            Supplier<T> getter,
            Class<T> entityClass,
            ShardInfoProvider shardInfoProvider,
            TransactionObserver observer) {
        this.shardId = shardId;
        this.parent = parent;
        this.sessionFactory = sessionFactory;
        this.getter = getter;
        this.observer = observer;
        this.mode = Mode.READ;
        this.executionContext = buildExecutionContext(shardInfoProvider, entityClass);
    }

    public LockedContext(
            int shardId,
            String parent,
            SessionFactory sessionFactory,
            Function<T, T> saver,
            T entity,
            Class<T> entityClass,
            ShardInfoProvider shardInfoProvider,
            TransactionObserver observer) {
        this.shardId = shardId;
        this.parent = parent;
        this.sessionFactory = sessionFactory;
        this.saver = saver;
        this.entity = entity;
        this.observer = observer;
        this.mode = Mode.INSERT;
        this.executionContext = buildExecutionContext(shardInfoProvider, entityClass);
    }

    public LockedContext<T> mutate(Mutator<T> mutator) {
        return apply(parent -> {
            mutator.mutator(parent);
            return null;
        });
    }

    public LockedContext<T> apply(Function<T, Void> handler) {
        this.operations.add(handler);
        return this;
    }

    public <U> LockedContext<T> save(RelationalDao<U> relationalDao, Function<T, U> entityGenerator) {
        return apply(parent -> {
            try {
                U entity = entityGenerator.apply(parent);
                relationalDao.save(this, entity);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            return null;
        });
    }

    public <U> LockedContext<T> saveAll(RelationalDao<U> relationalDao, Function<T, List<U>> entityGenerator) {
        return apply(parent -> {
            try {
                List<U> entities = entityGenerator.apply(parent);
                for (U entity : entities) {
                    relationalDao.save(this, entity);
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            return null;
        });
    }

    public <U> LockedContext<T> save(RelationalDao<U> relationalDao, U entity, Function<U, U> handler) {
        return apply(parent -> {
            try {
                relationalDao.save(this, entity, handler);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            return null;
        });
    }

    public <U> LockedContext<T> updateUsingQuery(
            RelationalDao<U> relationalDao,
            UpdateOperationMeta updateOperationMeta) {
        return apply(parent -> {
            try {
                relationalDao.updateUsingQuery(this, updateOperationMeta);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            return null;
        });
    }

    public <U> LockedContext<T> update(RelationalDao<U> relationalDao, Object id, Function<U, U> handler) {
        return apply(parent -> {
            try {
                relationalDao.update(this, id, handler);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            return null;
        });
    }

    public <U> LockedContext<T> createOrUpdate(
            RelationalDao<U> relationalDao,
            DetachedCriteria criteria,
            Function<U, U> updater,
            Supplier<U> entityGenerator) {
        return apply(parent -> {
            try {
                relationalDao.createOrUpdate(this, criteria, updater, entityGenerator);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            return null;
        });
    }

    public <U> LockedContext<T> update(
            RelationalDao<U> relationalDao,
            DetachedCriteria criteria,
            Function<U, U> updater,
            BooleanSupplier updateNext) {
        return apply(parent -> {
            try {
                relationalDao.update(this, criteria, updater, updateNext);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            return null;
        });
    }

    public LockedContext<T> filter(Predicate<T> predicate) {
        return filter(predicate, new IllegalArgumentException("Predicate check failed"));
    }

    public LockedContext<T> filter(Predicate<T> predicate, RuntimeException failureException) {
        return apply(parent -> {
            boolean result = predicate.test(parent);
            if (!result) {
                throw failureException;
            }
            return null;
        });
    }

    public T execute() {
        return observer.execute(executionContext, () -> {
            TransactionHandler transactionHandler = new TransactionHandler(sessionFactory, false);
            transactionHandler.beforeStart();
            try {
                T result = generateEntity();
                operations
                        .forEach(operation -> operation.apply(result));
                return result;
            } catch (Exception e) {
                transactionHandler.onError();
                throw e;
            } finally {
                transactionHandler.afterEnd();
            }
        });
    }

    private TransactionExecutionContext buildExecutionContext(final ShardInfoProvider shardInfoProvider,
                                                              final Class<T> entityClass) {
        return TransactionExecutionContext.builder()
                .shardName(shardInfoProvider.shardName(shardId))
                .lockedContextMode(mode.name())
                .entityClass(entityClass)
                .daoClass(getClass())
                .opType("execute")
                .build();
    }

    private T generateEntity() {
        T result = null;
        switch (mode) {
            case READ:
                result = getter.get();
                if (result == null) {
                    throw new RuntimeException("Entity doesn't exist");
                }
                break;
            case INSERT:
                result = saver.apply(entity);
                break;
            default:
                break;

        }
        return result;
    }
}
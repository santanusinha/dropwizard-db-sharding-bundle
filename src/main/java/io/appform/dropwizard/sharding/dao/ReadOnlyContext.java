package io.appform.dropwizard.sharding.dao;

import com.google.common.collect.Lists;
import io.appform.dropwizard.sharding.utils.TransactionHandler;
import lombok.Getter;
import lombok.val;
import lombok.var;
import org.hibernate.SessionFactory;
import org.hibernate.criterion.DetachedCriteria;

import java.util.List;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

@Getter
public class ReadOnlyContext<T> {
    private final int shardId;
    private final SessionFactory sessionFactory;
    private Supplier<T> getter;
    private final Supplier<Boolean> entityPopulator;
    private final List<Function<T, Void>> operations = Lists.newArrayList();
    private final boolean skipTransaction;

    public ReadOnlyContext(
            int shardId,
            SessionFactory sessionFactory,
            Supplier<T> getter,
            Supplier<Boolean> entityPopulator) {
        this.shardId = shardId;
        this.sessionFactory = sessionFactory;
        this.getter = getter;
        this.entityPopulator = entityPopulator;
        val skipFlag = System.getProperty("lookup.ro.skipTxn");
        this.skipTransaction = null != skipFlag && (skipFlag.isEmpty() || Boolean.parseBoolean(skipFlag));
    }


    public ReadOnlyContext<T> apply(Function<T, Void> handler) {
        this.operations.add(handler);
        return this;
    }


    public <U> ReadOnlyContext<T> readOneAugmentParent(
            RelationalDao<U> relationalDao,
            DetachedCriteria criteria,
            BiConsumer<T, List<U>> consumer) {
        return readAugmentParent(relationalDao, criteria, 0, 1, consumer, p -> true);
    }

    public <U> ReadOnlyContext<T> readAugmentParent(
            RelationalDao<U> relationalDao,
            DetachedCriteria criteria,
            int first,
            int numResults,
            BiConsumer<T, List<U>> consumer) {
        return readAugmentParent(relationalDao, criteria, first, numResults, consumer, p -> true);
    }

    public <U> ReadOnlyContext<T> readOneAugmentParent(
            RelationalDao<U> relationalDao,
            DetachedCriteria criteria,
            BiConsumer<T, List<U>> consumer,
            Predicate<T> filter) {
        return readAugmentParent(relationalDao, criteria, 0, 1, consumer, filter);
    }

    public <U> ReadOnlyContext<T> readAugmentParent(
            RelationalDao<U> relationalDao,
            DetachedCriteria criteria,
            int first,
            int numResults,
            BiConsumer<T, List<U>> consumer,
            Predicate<T> filter) {
        return apply(parent -> {
            if (filter.test(parent)) {
                try {
                    consumer.accept(parent, relationalDao.select(this, criteria, first, numResults));
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
            return null;
        });
    }

    public Optional<T> execute() {
        var result = executeImpl();
        if (null == result
                && null != entityPopulator
                && Boolean.TRUE.equals(entityPopulator.get())) {//Try to populate entity (maybe from cold store etc)
            result = executeImpl();
        }
        return Optional.ofNullable(result);
    }

    private T executeImpl() {
        TransactionHandler transactionHandler = new TransactionHandler(sessionFactory, true, this.skipTransaction);
        transactionHandler.beforeStart();
        try {
            T result = getter.get();
            if (null == result) {
                return null;
            }
            operations.forEach(operation -> operation.apply(result));
            return result;
        } catch (Exception e) {
            transactionHandler.onError();
            throw e;
        } finally {
            transactionHandler.afterEnd();
        }
    }
}
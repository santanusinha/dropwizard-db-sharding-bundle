package io.appform.dropwizard.sharding.scroll;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import io.appform.dropwizard.sharding.dao.operations.Select;
import io.appform.dropwizard.sharding.dao.operations.SelectParam;
import io.appform.dropwizard.sharding.execution.TransactionExecutor;
import io.appform.dropwizard.sharding.query.QuerySpec;
import lombok.NonNull;
import lombok.SneakyThrows;
import lombok.val;
import org.apache.commons.lang3.reflect.FieldUtils;
import org.hibernate.SessionFactory;

import javax.persistence.criteria.CriteriaBuilder;
import javax.persistence.criteria.Root;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Encapsulates the QuerySpec-based scroll algorithm across shards.
 * <p>
 * Constructed per-tenant by MultiTenantRelationalDao / MultiTenantLookupDao,
 * capturing shard-specific dependencies at construction time (following the
 * LockedContext pattern).
 * <p>
 * Differs from hibernate6: composes ordering into a new QuerySpec lambda
 * instead of using criteriaMutator.apply(InternalUtils.cloneObject(inCriteria))
 * since QuerySpec cannot be cloned (not Serializable). The ordering is applied
 * via CriteriaQuery.orderBy() inside the composed lambda.
 */
public class ScrollExecutor<T> {

    private final List<SessionFactory> sessionFactories;
    private final List<Function<SelectParam, List<T>>> selectors;
    private final TransactionExecutor transactionExecutor;
    private final Class<T> entityClass;

    public ScrollExecutor(
            List<SessionFactory> sessionFactories,
            List<Function<SelectParam, List<T>>> selectors,
            TransactionExecutor transactionExecutor,
            Class<T> entityClass) {
        this.sessionFactories = sessionFactories;
        this.selectors = selectors;
        this.transactionExecutor = transactionExecutor;
        this.entityClass = entityClass;
    }

    public ScrollResult<T> scrollDown(
            final QuerySpec<T, T> inQuerySpec,
            final ScrollPointer inPointer,
            final int pageSize,
            @NonNull final String sortFieldName) {
        val pointer = inPointer == null
                ? new ScrollPointer(ScrollPointer.Direction.DOWN)
                : inPointer;
        Preconditions.checkArgument(
                pointer.getDirection().equals(ScrollPointer.Direction.DOWN),
                "A down scroll pointer needs to be passed to this method");
        return scroll(inQuerySpec, pointer, pageSize,
                (root, cb) -> cb.asc(root.get(sortFieldName)),
                new FieldComparator<T>(FieldUtils.getField(entityClass, sortFieldName, true))
                        .thenComparing(ScrollResultItem::getShardIdx),
                "scrollDown");
    }

    @SneakyThrows
    public ScrollResult<T> scrollUp(
            final QuerySpec<T, T> inQuerySpec,
            final ScrollPointer inPointer,
            final int pageSize,
            @NonNull final String sortFieldName) {
        val pointer = null == inPointer
                ? new ScrollPointer(ScrollPointer.Direction.UP)
                : inPointer;
        Preconditions.checkArgument(
                pointer.getDirection().equals(ScrollPointer.Direction.UP),
                "An up scroll pointer needs to be passed to this method");
        return scroll(inQuerySpec, pointer, pageSize,
                (root, cb) -> cb.desc(root.get(sortFieldName)),
                new FieldComparator<T>(FieldUtils.getField(entityClass, sortFieldName, true))
                        .reversed()
                        .thenComparing(ScrollResultItem::getShardIdx),
                "scrollUp");
    }

    @SneakyThrows
    private ScrollResult<T> scroll(
            final QuerySpec<T, T> inQuerySpec,
            final ScrollPointer pointer,
            final int pageSize,
            final BiFunction<Root<T>, CriteriaBuilder, javax.persistence.criteria.Order> orderFactory,
            final Comparator<ScrollResultItem<T>> comparator,
            String methodName) {
        val daoIndex = new AtomicInteger();
        val results = sessionFactories.stream()
                .flatMap(sessionFactory -> {
                    val currIdx = daoIndex.getAndIncrement();
                    final QuerySpec<T, T> orderedSpec = (root, query, cb) -> {
                        inQuerySpec.apply(root, query, cb);
                        query.orderBy(orderFactory.apply(root, cb));
                    };
                    val opContext = Select.<T, List<T>>builder()
                            .getter(selectors.get(currIdx))
                            .selectParam(SelectParam.<T>builder()
                                    .querySpec(orderedSpec)
                                    .start(pointer.getCurrOffset(currIdx))
                                    .numRows(pageSize)
                                    .build())
                            .build();
                    return transactionExecutor
                            .execute(sessionFactory, true,
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
}

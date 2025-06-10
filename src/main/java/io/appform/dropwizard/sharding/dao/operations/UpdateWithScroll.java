package io.appform.dropwizard.sharding.dao.operations;

import lombok.Builder;
import lombok.Data;
import lombok.NonNull;
import org.hibernate.ScrollableResults;
import org.hibernate.Session;

import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.UnaryOperator;

/**
 * Get entities by criteria over scroll. Iterate over each result, mutate and persist to DB all
 * within same session. Scroll is iteratively performed until, there is no more results or
 * updateNext BooleanSupplier returns false.
 *
 * @param <T> Type of entity being updated.
 */
@Data
@Builder
public class UpdateWithScroll<T> extends OpContext<Boolean> {

    @NonNull
    private ScrollParam<T> scrollParam;
    @NonNull
    private Function<ScrollParam<T>, ScrollableResults> scroll;
    @NonNull
    private UnaryOperator<T> mutator;
    private BiConsumer<T, T> updater;
    private BooleanSupplier updateNext;

    @Override
    public Boolean apply(Session session) {
        ScrollableResults scrollableResults = scroll.apply(scrollParam);
        boolean updateNextObject = true;
        try (scrollableResults) {
            while (scrollableResults.next() && updateNextObject) {
                final T entity = (T) scrollableResults.get(0);
                if (null == entity) {
                    return false;
                }
                final T newEntity = mutator.apply(entity);
                if (null == newEntity) {
                    return false;
                }
                updater.accept(entity, newEntity);
                updateNextObject = updateNext.getAsBoolean();
            }
        }
        return true;
    }

    @Override
    public OpType getOpType() {
        return OpType.UPDATE_WITH_SCROLL;
    }

    @Override
    public <R> R visit(OpContextVisitor<R> visitor) {
        return visitor.visit(this);
    }
}

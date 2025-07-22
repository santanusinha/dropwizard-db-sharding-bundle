package io.appform.dropwizard.sharding.dao.operations;

import io.appform.dropwizard.sharding.query.QuerySpec;
import lombok.Builder;
import lombok.Data;
import lombok.NonNull;
import org.hibernate.Session;

import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * Get an entity from DB, mutate it and persist it back to DB. All in same hibernate
 * session.
 *
 * @param <T> Type of entity to get and update.
 */
@Data
@Builder
public class GetAndUpdateByQuerySpec<T> extends OpContext<Boolean> {

    @NonNull
    private QuerySpec<T, T> criteria;
    @NonNull
    private Function<QuerySpec<T, T>, T> getter;
    @Builder.Default
    private Function<T, T> mutator = t -> t;
    private BiConsumer<T, T> updater;

    @Override
    public Boolean apply(Session session) {
        T entity = getter.apply(criteria);
        if (null == entity) {
            return false;
        }
        T newEntity = mutator.apply(entity);
        if (null == newEntity) {
            return false;
        }
        updater.accept(entity, newEntity);
        return true;
    }

    @Override
    public OpType getOpType() {
        return OpType.GET_AND_UPDATE_BY_QUERY_SPEC;
    }

    @Override
    public <R> R visit(OpContextVisitor<R> visitor) {
        return visitor.visit(this);
    }
}

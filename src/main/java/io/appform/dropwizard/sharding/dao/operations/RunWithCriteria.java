package io.appform.dropwizard.sharding.dao.operations;

import io.appform.dropwizard.sharding.query.QuerySpec;
import lombok.Builder;
import lombok.Data;
import lombok.NonNull;
import org.hibernate.Session;

import java.util.function.Function;

/**
 * Run a query with given criteria inside this shard and returns resulting list.
 *
 * @param <T> Return type on performing the operation.
 */
@Data
@Builder
public class RunWithCriteria<T, R> extends OpContext<T> {

    @NonNull
    private QuerySpec<T, R> detachedCriteria;
    @NonNull
    private Function<QuerySpec<T, R>, T> handler;

    @Override
    public T apply(Session session) {
        return handler.apply(detachedCriteria);
    }

    @Override
    public OpType getOpType() {
        return OpType.RUN_WITH_CRITERIA;
    }

    @Override
    public <R> R visit(OpContextVisitor<R> visitor) {
        return visitor.visit(this);
    }
}

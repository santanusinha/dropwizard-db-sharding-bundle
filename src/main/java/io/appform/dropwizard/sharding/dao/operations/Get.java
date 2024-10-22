package io.appform.dropwizard.sharding.dao.operations;

import io.appform.dropwizard.sharding.query.QuerySpec;
import lombok.Builder;
import lombok.Data;
import lombok.NonNull;
import org.hibernate.Session;

import java.util.function.Function;

/**
 * Get an entity with given criteria. Apply afterGet function and return the final response.
 *
 * @param <T> Type of entity to get.
 * @param <R> Type of response after applying afterGet function.
 */
@Data
@Builder
public class Get<T, R> extends OpContext<R> {

    @NonNull
    private QuerySpec<T, T> criteria;
    @NonNull
    private Function<QuerySpec<T, T>, T> getter;
    @Builder.Default
    private Function<T, R> afterGet = t -> (R) t;

    @Override
    public R apply(Session session) {
        return afterGet.apply(getter.apply(criteria));
    }

    @Override
    public OpType getOpType() {
        return OpType.GET;
    }

    @Override
    public <R1> R1 visit(OpContextVisitor<R1> visitor) {
        return visitor.visit(this);
    }
}

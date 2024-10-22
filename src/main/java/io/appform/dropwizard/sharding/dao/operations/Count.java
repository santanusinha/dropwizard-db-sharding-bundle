package io.appform.dropwizard.sharding.dao.operations;

import io.appform.dropwizard.sharding.query.QuerySpec;
import lombok.Builder;
import lombok.Data;
import lombok.NonNull;
import org.hibernate.Session;

import java.util.function.Function;

/**
 * Returns count of records matching given criteria for a shard.
 */
@Data
@Builder
public class Count<T> extends OpContext<Long> {

    @NonNull
    private QuerySpec<T, Long> criteria;

    @NonNull
    private Function<QuerySpec<T, Long>, Long> counter;

    @Override
    public Long apply(Session session) {
        return counter.apply(criteria);
    }

    @Override
    public OpType getOpType() {
        return OpType.COUNT;
    }

    @Override
    public <R> R visit(OpContextVisitor<R> visitor) {
        return visitor.visit(this);
    }
}

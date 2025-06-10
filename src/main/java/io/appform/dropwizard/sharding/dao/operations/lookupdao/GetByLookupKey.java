package io.appform.dropwizard.sharding.dao.operations.lookupdao;

import io.appform.dropwizard.sharding.dao.operations.OpContext;
import io.appform.dropwizard.sharding.dao.operations.OpType;
import lombok.Builder;
import lombok.Data;
import lombok.NonNull;
import org.hibernate.Criteria;
import org.hibernate.Session;

import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.UnaryOperator;

@Data
@Builder
public class GetByLookupKey<T, R> extends OpContext<R> {

    @NonNull
    private String id;
    @NonNull
    private BiFunction<String, UnaryOperator<Criteria>, T> getter;
    @Builder.Default
    private UnaryOperator<Criteria> criteriaUpdater = t -> t;
    @Builder.Default
    private Function<T, R> afterGet = t -> (R) t;


    @Override
    public R apply(Session session) {
        T result = getter.apply(id, criteriaUpdater);
        return afterGet.apply(result);
    }

    @Override
    public OpType getOpType() {
        return OpType.GET_BY_LOOKUP_KEY;
    }

    @Override
    public <R1> R1 visit(OpContextVisitor<R1> visitor) {
        return visitor.visit(this);
    }
}

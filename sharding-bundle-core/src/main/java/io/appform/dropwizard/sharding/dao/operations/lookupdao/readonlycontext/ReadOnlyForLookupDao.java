package io.appform.dropwizard.sharding.dao.operations.lookupdao.readonlycontext;

import com.google.common.collect.Lists;
import io.appform.dropwizard.sharding.dao.operations.OpContext;
import io.appform.dropwizard.sharding.dao.operations.OpType;
import lombok.Builder;
import lombok.Data;
import lombok.NonNull;
import org.hibernate.Session;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Acquires lock on an entity and perform all read operations given. Getter implementation provided
 * is responsible for acquiring locks. This opcontext does not acquire locks in its impl. This executes the given list
 * of operations, all within a same DB txn.
 *
 * @param <T> Entity type on which operations are to be executed.
 */
@Data
@Builder
public class ReadOnlyForLookupDao<T> extends OpContext<T> {

    @NonNull
    private String key;
    @Builder.Default
    private List<Consumer<T>> operations = Lists.newArrayList();
    @NonNull
    private Function<String, T> getter;


    @Override
    public T apply(Session session) {
        T result = getter.apply(key);
        if (null != result) {
            operations.forEach(operation -> operation.accept(result));
        }
        return result;
    }

    @Override
    public OpType getOpType() {
        return OpType.READ_ONLY_FOR_LOOKUP_DAO;
    }

    @Override
    public <R> R visit(OpContextVisitor<R> visitor) {
        return visitor.visit(this);
    }
}

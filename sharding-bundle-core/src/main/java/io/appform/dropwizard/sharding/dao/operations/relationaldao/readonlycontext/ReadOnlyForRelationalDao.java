package io.appform.dropwizard.sharding.dao.operations.relationaldao.readonlycontext;

import com.google.common.collect.Lists;
import io.appform.dropwizard.sharding.dao.operations.OpContext;
import io.appform.dropwizard.sharding.dao.operations.OpType;
import lombok.Builder;
import lombok.Data;
import lombok.NonNull;
import org.hibernate.Session;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Acquires lock on list of entities and perform all read operations given. Getter implementation provided
 * is responsible for acquiring locks. This opcontext does not acquire locks in its impl. This executes the given list
 * of operations, all within a same DB txn.
 *
 * @param <T> Entity type on which operations are to be executed.
 */
@Data
@Builder
public class ReadOnlyForRelationalDao<T> extends OpContext<List<T>> {
    @NonNull
    private final Supplier<List<T>> getter;
    @Builder.Default
    private final List<Consumer<List<T>>> operations = Lists.newArrayList();

    @Override
    public OpType getOpType() {
        return OpType.READ_ONLY_FOR_RELATIONAL_DAO;
    }

    @Override
    public <P> P visit(final OpContextVisitor<P> visitor) {
        return visitor.visit(this);
    }

    @Override
    public List<T> apply(final Session session) {
        List<T> result = getter.get();
        if (null == result || result.isEmpty()) {
            return null;
        }
        operations.forEach(operation -> operation.accept(result));
        return result;
    }
}

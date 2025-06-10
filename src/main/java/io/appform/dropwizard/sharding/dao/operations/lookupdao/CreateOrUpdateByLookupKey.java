package io.appform.dropwizard.sharding.dao.operations.lookupdao;

import io.appform.dropwizard.sharding.dao.operations.OpContext;
import io.appform.dropwizard.sharding.dao.operations.OpType;
import lombok.Builder;
import lombok.Data;
import lombok.NonNull;
import lombok.val;
import org.hibernate.Session;

import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/**
 * Acquire lock on an entity by lookup key. If entity present, performs mutation and updates it.
 * else creates the entity.
 *
 * @param <T> Type of entity on which operation being performed.
 */
@Data
@Builder
public class CreateOrUpdateByLookupKey<T> extends OpContext<T> {

    @NonNull
    private String id;
    @NonNull
    private UnaryOperator<T> mutator;
    @NonNull
    private Supplier<T> entityGenerator;
    @NonNull
    private Function<String, T> getLockedForWrite;
    @NonNull
    private Function<String, T> getter;

    @NonNull
    private Function<T, T> saver;
    // Consumes entity value and persists it to DB.
    @NonNull
    private Consumer<T> updater;

    @Override
    public T apply(Session session) {
        T result = getLockedForWrite.apply(id);
        if (null == result) {
            val newEntity = entityGenerator.get();
            if (null != newEntity) {
                return saver.apply(newEntity);
            }
            return null;
        }
        val updated = mutator.apply(result);
        if (null != updated) {
            updater.accept(updated);
        }
        return getter.apply(id);
    }

    @Override
    public OpType getOpType() {
        return OpType.CREATE_OR_UPDATE_BY_LOOKUP_KEY;
    }

    @Override
    public <R> R visit(OpContextVisitor<R> visitor) {
        return visitor.visit(this);
    }
}

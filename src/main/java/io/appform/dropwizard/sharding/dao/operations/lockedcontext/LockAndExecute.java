package io.appform.dropwizard.sharding.dao.operations.lockedcontext;

import io.appform.dropwizard.sharding.dao.LockedContext.Mode;
import io.appform.dropwizard.sharding.dao.operations.OpContext;
import io.appform.dropwizard.sharding.dao.operations.OpType;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import lombok.Builder;
import lombok.Data;
import lombok.NonNull;
import org.hibernate.Session;

import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Acquires lock on an entity and perform all the operations given. On INSERT mode, creates an
 * entity and executes the given list of operations, all within a same DB txn. ON READ mode, gets an
 * existing entity (the getter implementation should acquire a lock on this entity. This opcontext
 * is not responsible for acquiring locks) and executes the given list of operations, all within a
 * same DB txn.
 *
 * @param <T> Entity type on which operations are to be executed.
 */
@Data
public class LockAndExecute<T> extends OpContext<T> {

    private final Queue<Consumer<T>> operations = new ConcurrentLinkedQueue<>();
    @NonNull
    private final Mode mode;
    private Supplier<T> getter;
    private Function<T, T> saver;
    private T entity;

    @Builder(builderMethodName = "buildForRead", builderClassName = "ReadModeBuilder")
    public LockAndExecute(@NonNull Supplier<T> getter) {
        this.mode = Mode.READ;
        this.getter = getter;
    }

    @Builder(builderMethodName = "buildForInsert", builderClassName = "InsertModeBuilder")
    public LockAndExecute(@NonNull T entity, @NonNull Function<T, T> saver) {
        this.mode = Mode.INSERT;
        this.entity = entity;
        this.saver = saver;
    }

    @Override
    public T apply(Session session) {
        T result = generateEntity();
        while (!operations.isEmpty()) {
            Consumer<T> operation = operations.poll();
            operation.accept(result);
        }

        return result;
    }

    private T generateEntity() {
        T result = null;
        switch (mode) {
            case READ:
                result = getter.get();
                if (result == null) {
                    throw new RuntimeException("Entity doesn't exist");
                }
                break;
            case INSERT:
                result = saver.apply(entity);
                break;
            default:
                break;

        }
        return result;
    }

    @Override
    public OpType getOpType() {
        return OpType.LOCK_AND_EXECUTE;
    }

    @Override
    public <R> R visit(OpContextVisitor<R> visitor) {
        return visitor.visit(this);
    }
}

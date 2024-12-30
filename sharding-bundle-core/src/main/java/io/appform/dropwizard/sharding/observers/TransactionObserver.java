package io.appform.dropwizard.sharding.observers;

import io.appform.dropwizard.sharding.execution.TransactionExecutionContext;
import lombok.extern.slf4j.Slf4j;

import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 *
 */
@Slf4j
public abstract class TransactionObserver {

    private TransactionObserver next;


    protected TransactionObserver(TransactionObserver next) {
        this.next = next;
    }

    public abstract <T> T execute(final TransactionExecutionContext context, Supplier<T> supplier);

    public final TransactionObserver setNext(final TransactionObserver next) {
        this.next = next;
        return this;
    }

    public final void visit(Consumer<TransactionObserver> visitor) {
        visitor.accept(this);
        if (next != null) {
            next.visit(visitor);
        }
    }

    protected final <T> T proceed(final TransactionExecutionContext context, final Supplier<T> supplier) {
        if (null == next) {
            return supplier.get();
        }
        return next.execute(context, supplier);
    }
}

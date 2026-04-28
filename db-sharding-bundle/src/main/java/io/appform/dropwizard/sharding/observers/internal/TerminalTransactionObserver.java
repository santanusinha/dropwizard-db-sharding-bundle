package io.appform.dropwizard.sharding.observers.internal;

import io.appform.dropwizard.sharding.execution.TransactionExecutionContext;
import io.appform.dropwizard.sharding.observers.TransactionObserver;

import java.util.function.Supplier;

/**
 *
 */
public final class TerminalTransactionObserver extends TransactionObserver {
    public TerminalTransactionObserver() {
        super(null);
    }

    @Override
    public <T> T execute(TransactionExecutionContext context, Supplier<T> supplier) {
        return proceed(context, supplier);
    }
}

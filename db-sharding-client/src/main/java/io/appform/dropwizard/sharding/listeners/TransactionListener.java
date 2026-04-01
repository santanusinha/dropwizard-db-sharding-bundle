package io.appform.dropwizard.sharding.listeners;

import io.appform.dropwizard.sharding.execution.TransactionExecutionContext;

public interface TransactionListener {

    void beforeExecute(final TransactionExecutionContext listenerContext);

    void afterExecute(final TransactionExecutionContext listenerContext);

    void afterException(final TransactionExecutionContext listenerContext, final Throwable e);
}

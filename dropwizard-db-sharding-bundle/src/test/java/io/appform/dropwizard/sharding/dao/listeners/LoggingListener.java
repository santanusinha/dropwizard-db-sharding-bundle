package io.appform.dropwizard.sharding.dao.listeners;

import io.appform.dropwizard.sharding.execution.TransactionExecutionContext;
import io.appform.dropwizard.sharding.listeners.TransactionListener;
import lombok.extern.slf4j.Slf4j;

/**
 *
 */
@Slf4j
public class LoggingListener implements TransactionListener {
    @Override
    public void beforeExecute(TransactionExecutionContext listenerContext) {
        log.info("Starting transaction with context: {}", listenerContext);
    }

    @Override
    public void afterExecute(TransactionExecutionContext listenerContext) {
        log.info("Completed transaction with context: {}", listenerContext);

    }

    @Override
    public void afterException(TransactionExecutionContext listenerContext, Throwable e) {
        log.error("Starting transaction with context: " + listenerContext, e);
    }
}

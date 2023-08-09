package io.appform.dropwizard.sharding.listeners;

import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

@Slf4j
public class TransactionListenerExecutor {

    private final List<TransactionListenerFactory> listenerFactories;

    public TransactionListenerExecutor(final List<TransactionListenerFactory> listenerFactories) {
        this.listenerFactories = listenerFactories;
    }

    public List<TransactionListener> createShardedDaoListeners(final TransactionListenerContext listenerContext) {
        return listenerFactories.stream().map(listenerFactory ->
                        listenerFactory.createShardedDaoListener(listenerContext).orElse(null))
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    public List<TransactionListener> createReadOnlyContextListeners(final TransactionListenerContext listenerContext) {
        return listenerFactories.stream().map(listenerFactory ->
                        listenerFactory.createReadOnlyContextListener(listenerContext).orElse(null))
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    public List<TransactionListener> createLockedContextListeners(final TransactionListenerContext listenerContext) {
        return listenerFactories.stream().map(listenerFactory ->
                        listenerFactory.createLockedContextListener(listenerContext).orElse(null))
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    public void beforeExecute(final List<TransactionListener> transactionListeners,
                              final TransactionListenerContext listenerContext) {
        if (transactionListeners == null) {
            return;
        }
        transactionListeners.forEach(transactionListener -> {
            try {
                transactionListener.beforeExecute(listenerContext);
            } catch (Throwable e) {
                log.error("Error running before execute of listener: " + transactionListener.getClass(), e);
            }
        });
    }

    public void afterExecute(final List<TransactionListener> transactionListeners,
                             final TransactionListenerContext listenerContext) {
        if (transactionListeners == null) {
            return;
        }
        transactionListeners.forEach(transactionListener -> {
            try {
                transactionListener.afterExecute(listenerContext);
            } catch (Throwable e) {
                log.error("Error running after execute of listener: " + transactionListener.getClass(), e);
            }
        });
    }

    public void afterException(final List<TransactionListener> transactionListeners,
                               final TransactionListenerContext listenerContext,
                               final Throwable throwable) {
        if (transactionListeners == null) {
            return;
        }
        transactionListeners.forEach(transactionListener -> {
            try {
                transactionListener.afterException(listenerContext, throwable);
            } catch (Throwable e) {
                log.error("Error running after exception of listener: " + transactionListener.getClass(), e);
            }
        });
    }
}

package io.appform.dropwizard.sharding.listeners;

import java.util.Optional;

/**
 * Factory for creating instances of transaction listeners.
 * Listener instances can be same or different for the combination of parameters in createListener()
 */
public interface TransactionListenerFactory {

    Optional<TransactionListener> createShardedDaoListener(final TransactionListenerContext listenerContext);

    Optional<TransactionListener> createLockedContextListener(final TransactionListenerContext listenerContext);

    Optional<TransactionListener> createReadOnlyContextListener(final TransactionListenerContext listenerContext);

}

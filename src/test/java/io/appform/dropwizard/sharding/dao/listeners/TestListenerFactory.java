package io.appform.dropwizard.sharding.dao.listeners;

import io.appform.dropwizard.sharding.listeners.TransactionListener;
import io.appform.dropwizard.sharding.listeners.TransactionListenerContext;
import io.appform.dropwizard.sharding.listeners.TransactionListenerFactory;

import java.util.Optional;

public class TestListenerFactory implements TransactionListenerFactory {

    @Override
    public Optional<TransactionListener> createShardedDaoListener(TransactionListenerContext listenerContext) {
        return Optional.of(new TestListener(listenerContext));
    }

    @Override
    public Optional<TransactionListener> createLockedContextListener(TransactionListenerContext listenerContext) {
        return Optional.empty();
    }

    @Override
    public Optional<TransactionListener> createReadOnlyContextListener(TransactionListenerContext listenerContext) {
        return Optional.empty();
    }
}

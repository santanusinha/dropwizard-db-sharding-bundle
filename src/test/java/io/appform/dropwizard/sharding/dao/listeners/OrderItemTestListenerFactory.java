package io.appform.dropwizard.sharding.dao.listeners;

import io.appform.dropwizard.sharding.dao.testdata.entities.OrderItem;
import io.appform.dropwizard.sharding.listeners.TransactionListener;
import io.appform.dropwizard.sharding.listeners.TransactionListenerContext;
import io.appform.dropwizard.sharding.listeners.TransactionListenerFactory;

import java.util.Optional;

public class OrderItemTestListenerFactory implements TransactionListenerFactory {

    @Override
    public Optional<TransactionListener> createShardedDaoListener(TransactionListenerContext listenerContext) {
        if (listenerContext.getDaoClass() == OrderItem.class) {
            return Optional.of(new TestListener(listenerContext));
        }
        return Optional.empty();
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

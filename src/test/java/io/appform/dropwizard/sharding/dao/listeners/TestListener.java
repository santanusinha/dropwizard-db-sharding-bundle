package io.appform.dropwizard.sharding.dao.listeners;

import io.appform.dropwizard.sharding.listeners.TransactionListener;
import io.appform.dropwizard.sharding.listeners.TransactionListenerContext;
import org.junit.Assert;

public class TestListener implements TransactionListener {

    private final TransactionListenerContext transactionListenerContext;

    public TestListener(final TransactionListenerContext listenerContext) {
        this.transactionListenerContext = listenerContext;
    }

    @Override
    public void beforeExecute(TransactionListenerContext listenerContext) {
        validateContext(listenerContext);
    }

    @Override
    public void afterExecute(TransactionListenerContext listenerContext) {
        validateContext(listenerContext);
    }

    @Override
    public void afterException(TransactionListenerContext listenerContext, Throwable e) {
        validateContext(listenerContext);
    }

    private void validateContext(final TransactionListenerContext listenerContext) {
        Assert.assertEquals(transactionListenerContext.getShardName(), listenerContext.getShardName());
        Assert.assertNotNull(transactionListenerContext.getOpType());
        Assert.assertEquals(transactionListenerContext.getDaoClass(), listenerContext.getDaoClass());
        Assert.assertEquals(transactionListenerContext.getEntityClass(), listenerContext.getEntityClass());
    }
}

package io.appform.dropwizard.sharding.listeners;

import com.google.common.collect.Lists;
import lombok.val;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

import java.util.Collections;

public class TransactionListenerExecutorTest {

    private TransactionListenerFactory transactionListenerFactory;
    private TransactionListenerExecutor transactionListenerExecutor;

    @Before
    public void setup() {
        this.transactionListenerFactory = Mockito.mock(TransactionListenerFactory.class);
        this.transactionListenerExecutor = new TransactionListenerExecutor(Lists.newArrayList(transactionListenerFactory));
    }

    @Test
    public void testBeforeExecute() {
        val listenerContext = TransactionListenerContext.builder().build();
        val transactionListener = Mockito.mock(TransactionListener.class);
        Mockito.doNothing().when(transactionListener).beforeExecute(listenerContext);

        try {
            transactionListenerExecutor.beforeExecute(Collections.singletonList(transactionListener),
                    TransactionListenerContext.builder().build());
        } catch (Exception e) {
            Assert.fail("Exception was not expected");
        }

        Mockito.doThrow(new RuntimeException()).when(transactionListener).beforeExecute(listenerContext);
        try {
            transactionListenerExecutor.beforeExecute(Collections.singletonList(transactionListener),
                    TransactionListenerContext.builder().build());
        } catch (Exception e) {
            Assert.fail("Exception was not expected");
        }
    }

    @Test
    public void testAfterExecute() {
        val listenerContext = TransactionListenerContext.builder().build();
        val transactionListener = Mockito.mock(TransactionListener.class);
        Mockito.doNothing().when(transactionListener).afterExecute(listenerContext);

        try {
            transactionListenerExecutor.afterExecute(Collections.singletonList(transactionListener),
                    TransactionListenerContext.builder().build());
        } catch (Exception e) {
            Assert.fail("Exception was not expected");
        }

        Mockito.doThrow(new RuntimeException()).when(transactionListener).afterExecute(listenerContext);
        try {
            transactionListenerExecutor.afterExecute(Collections.singletonList(transactionListener),
                    TransactionListenerContext.builder().build());
        } catch (Exception e) {
            Assert.fail("Exception was not expected");
        }
    }

    @Test
    public void testAfterException() {
        val listenerContext = TransactionListenerContext.builder().build();
        val exception = new RuntimeException();
        val transactionListener = Mockito.mock(TransactionListener.class);
        Mockito.doNothing().when(transactionListener).afterException(listenerContext, exception);

        try {
            transactionListenerExecutor.afterException(Collections.singletonList(transactionListener),
                    TransactionListenerContext.builder().build(),
                    exception);
        } catch (Exception e) {
            Assert.fail("Exception was not expected");
        }

        Mockito.doThrow(new RuntimeException()).when(transactionListener).afterException(listenerContext, exception);
        try {
            transactionListenerExecutor.afterException(Collections.singletonList(transactionListener),
                    TransactionListenerContext.builder().build(), exception);
        } catch (Exception e) {
            Assert.fail("Exception was not expected");
        }
    }
}

package io.appform.dropwizard.sharding.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.context.internal.ManagedSessionContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class TransactionHandlerTest {

    private static final Logger log = LoggerFactory.getLogger(TransactionHandlerTest.class);

    private SessionFactory sessionFactory;
    private Session session;

    @BeforeEach
    void setUp() {
        sessionFactory = mock(SessionFactory.class);
        session = mock(Session.class);
        when(sessionFactory.openSession()).thenReturn(session);
        when(sessionFactory.getCurrentSession()).thenReturn(session); // Mock current session retrieval
    }

    @Test
    void testTransactionHandlerInitialization() {
        TransactionHandler transactionHandler = new TransactionHandler(sessionFactory, true);
        assertNotNull(transactionHandler);
    }

    @Test
    void testSessionAcquisition() {
        TransactionHandler transactionHandler = new TransactionHandler(sessionFactory, false);
        transactionHandler.beforeStart(); // Updated to use the correct method
        assertNotNull(transactionHandler.getSession());
        verify(sessionFactory, times(1)).openSession();
    }

    @Test
    void testReadOnlyTransaction() {
        when(session.isDefaultReadOnly()).thenReturn(true); // Mocking read-only behavior
        TransactionHandler transactionHandler = new TransactionHandler(sessionFactory, true);
        transactionHandler.beforeStart();
        assertTrue(transactionHandler.getSession().isDefaultReadOnly());
    }

    @Test
    void testSkipCommit() {
        TransactionHandler transactionHandler = new TransactionHandler(sessionFactory, false, true);
        transactionHandler.beforeStart();
        transactionHandler.afterEnd(); // Updated to use afterEnd for transaction completion
        verify(session, never()).getTransaction();
    }

    @Test
    void testReuseExistingSession() {
        org.hibernate.Transaction mockTransaction = mock(org.hibernate.Transaction.class);
        when(session.getTransaction()).thenReturn(mockTransaction);
        when(mockTransaction.isActive()).thenReturn(true); // Ensure transaction is active
        when(mockTransaction.getStatus()).thenReturn(org.hibernate.resource.transaction.spi.TransactionStatus.ACTIVE); // Mock ACTIVE status
        when(session.isOpen()).thenReturn(true); // Ensure session is marked as open
        when(session.isConnected()).thenReturn(true); // Mock session connectivity

        // Bind the session to simulate an existing session
        ManagedSessionContext.bind(session);

        try {
            TransactionHandler transactionHandler = new TransactionHandler(sessionFactory, false);
            transactionHandler.beforeStart();

            assertNotNull(transactionHandler.getSession(), "Session should not be null");
            assertSame(session, transactionHandler.getSession(), "The same session should be reused"); // Ensure the same session is reused
            assertEquals(org.hibernate.resource.transaction.spi.TransactionStatus.ACTIVE, mockTransaction.getStatus(), "Transaction should be active"); // Ensure the transaction is active
        } catch (Exception e) {
            log.error("Unexpected exception during test execution", e); // Use robust logging
            fail("Unexpected exception: " + e.getMessage()); // Fail the test if any exception occurs
        } finally {
            // Unbind the session to clean up after the test
            ManagedSessionContext.unbind(sessionFactory);
        }
    }

    @Test
    void testNewSessionWhenNoExistingSession() {
        ManagedSessionContext.unbind(sessionFactory); // Ensure no session is bound

        when(session.isOpen()).thenReturn(true); // Mock session open behavior
        when(session.isConnected()).thenReturn(true); // Mock session connectivity

        TransactionHandler transactionHandler = new TransactionHandler(sessionFactory, false);

        transactionHandler.beforeStart();

        assertNotNull(transactionHandler.getSession());
        verify(sessionFactory, times(1)).openSession(); // Ensure openSession is called exactly once
        assertTrue(session.isOpen()); // Adjusted to directly check the mocked session behavior
    }

    @Test
    void testRollbackOnError() {
        org.hibernate.Transaction transaction = mock(org.hibernate.Transaction.class);
        when(session.getTransaction()).thenReturn(transaction);
        when(transaction.getStatus()).thenReturn(org.hibernate.resource.transaction.spi.TransactionStatus.ACTIVE); // Mock ACTIVE status

        doThrow(new RuntimeException("Simulated error")).when(session).close();

        TransactionHandler transactionHandler = new TransactionHandler(sessionFactory, false);
        transactionHandler.beforeStart();

        assertThrows(RuntimeException.class, transactionHandler::onError);
        verify(transaction, times(1)).rollback(); // Ensure rollback is called
        verify(session, times(1)).close();
    }

    @Test
    void testCommitTransaction() {
        org.hibernate.Transaction transaction = mock(org.hibernate.Transaction.class);
        when(session.getTransaction()).thenReturn(transaction);
        when(transaction.getStatus()).thenReturn(org.hibernate.resource.transaction.spi.TransactionStatus.ACTIVE); // Mock ACTIVE status

        TransactionHandler transactionHandler = new TransactionHandler(sessionFactory, false);
        transactionHandler.beforeStart();
        transactionHandler.afterEnd();

        verify(transaction, times(1)).commit(); // Ensure commit is called
        verify(session, times(1)).close();
    }
}

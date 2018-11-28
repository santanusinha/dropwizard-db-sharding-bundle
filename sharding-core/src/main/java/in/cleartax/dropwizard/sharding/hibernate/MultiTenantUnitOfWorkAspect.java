package in.cleartax.dropwizard.sharding.hibernate;

import io.dropwizard.hibernate.HibernateBundle;
import io.dropwizard.hibernate.UnitOfWork;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.Transaction;
import org.hibernate.context.internal.ManagedSessionContext;

import javax.annotation.Nullable;
import java.util.Map;
import java.util.Stack;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.util.Objects.requireNonNull;

/**
 * Created on 14/11/18
 */
public class MultiTenantUnitOfWorkAspect {
    private static ThreadLocal<Stack<Session>> CONTEXT_OPEN_SESSIONS = ThreadLocal.withInitial(Stack::new);

    private final Map<String, SessionFactory> sessionFactories;
    // Context variables
    @Nullable
    private UnitOfWork unitOfWork;
    @Nullable
    private Session session;
    @Nullable
    private SessionFactory sessionFactory;

    public MultiTenantUnitOfWorkAspect(Map<String, SessionFactory> sessionFactories) {
        this.sessionFactories = sessionFactories;
    }

    public void beforeStart(@Nullable UnitOfWork unitOfWork) {
        if (unitOfWork == null) {
            return;
        }
        this.unitOfWork = unitOfWork;

        sessionFactory = sessionFactories.get(unitOfWork.value());
        if (sessionFactory == null) {
            // If the user didn't specify the name of a session factory,
            // and we have only one registered, we can assume that it's the right one.
            if (unitOfWork.value().equals(HibernateBundle.DEFAULT_NAME) && sessionFactories.size() == 1) {
                sessionFactory = sessionFactories.values().iterator().next();
            } else {
                throw new IllegalArgumentException("Unregistered Hibernate bundle: '" + unitOfWork.value() + "'");
            }
        }
        session = sessionFactory.openSession();
        assert session != null;
        try {
            configureSession();
            bind(session);
            beginTransaction(unitOfWork, session);
        } catch (Throwable th) {
            session.close();
            session = null;
            unbind(sessionFactory);
            throw th;
        }
    }

    public void afterEnd() {
        if (unitOfWork == null || session == null) {
            return;
        }

        try {
            commitTransaction(unitOfWork, session);
        } catch (Exception e) {
            rollbackTransaction(unitOfWork, session);
            throw e;
        }
        // We should not close the session to let the lazy loading work during serializing a response to the client.
        // If the response successfully serialized, then the session will be closed by the `onFinish` method
    }

    public void onError() {
        if (unitOfWork == null || session == null) {
            return;
        }

        try {
            rollbackTransaction(unitOfWork, session);
        } finally {
            onFinish();
        }
    }

    public void onFinish() {
        try {
            if (session != null) {
                session.close();
            }
        } finally {
            session = null;
            unbind(sessionFactory);
        }
    }

    protected void configureSession() {
        checkNotNull(unitOfWork);
        checkNotNull(session);
        session.setDefaultReadOnly(unitOfWork.readOnly());
        session.setCacheMode(unitOfWork.cacheMode());
        session.setHibernateFlushMode(unitOfWork.flushMode());
    }

    private void beginTransaction(UnitOfWork unitOfWork, Session session) {
        if (!unitOfWork.transactional()) {
            return;
        }
        session.beginTransaction();
    }

    private void rollbackTransaction(UnitOfWork unitOfWork, Session session) {
        if (!unitOfWork.transactional()) {
            return;
        }
        final Transaction txn = session.getTransaction();
        if (txn != null && txn.getStatus().canRollback()) {
            txn.rollback();
        }
    }

    private void commitTransaction(UnitOfWork unitOfWork, Session session) {
        if (!unitOfWork.transactional()) {
            return;
        }
        final Transaction txn = session.getTransaction();
        if (txn != null && txn.getStatus().canRollback()) {
            txn.commit();
        }
    }

    protected Session getSession() {
        return requireNonNull(session);
    }

    protected SessionFactory getSessionFactory() {
        return requireNonNull(sessionFactory);
    }

    private void bind(Session session) {
        CONTEXT_OPEN_SESSIONS.get().push(session);
        ManagedSessionContext.bind(session);
    }

    private void unbind(SessionFactory sessionFactory) {
        ManagedSessionContext.unbind(sessionFactory);
        // This defensive check is needed as in case of exception onFinish gets called multiple times.
        if (!CONTEXT_OPEN_SESSIONS.get().isEmpty()) {
            CONTEXT_OPEN_SESSIONS.get().pop();
        }
        if (!CONTEXT_OPEN_SESSIONS.get().isEmpty()) {
            ManagedSessionContext.bind(CONTEXT_OPEN_SESSIONS.get().peek());
        }
    }
}

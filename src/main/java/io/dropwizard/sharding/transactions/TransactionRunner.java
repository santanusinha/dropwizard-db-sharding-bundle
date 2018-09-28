package io.dropwizard.sharding.transactions;

import io.dropwizard.hibernate.UnitOfWork;
import io.dropwizard.hibernate.UnitOfWorkAspect;
import io.dropwizard.hibernate.UnitOfWorkAwareProxyFactory;
import lombok.AllArgsConstructor;
import org.hibernate.SessionFactory;
import org.hibernate.context.internal.ManagedSessionContext;

import java.lang.reflect.InvocationTargetException;

/**
 * Created on 23/09/18
 */

@AllArgsConstructor
public abstract class TransactionRunner {
    private UnitOfWorkAwareProxyFactory proxyFactory;
    private SessionFactory sessionFactory;

    public Object start(boolean reUseSession, UnitOfWork unitOfWork) throws Throwable {
        if (reUseSession) {
            if (ManagedSessionContext.hasBind(sessionFactory)) {
                return run();
            }
        }

        UnitOfWorkAspect aspect = proxyFactory.newAspect();
        try {
            aspect.beforeStart(unitOfWork);
            Object result = run();
            aspect.afterEnd();
            return result;
        } catch (InvocationTargetException e) {
            aspect.onError();
            throw e.getCause();
        } catch (Exception e) {
            aspect.onError();
            throw e;
        } finally {
            aspect.onFinish();
        }
    }

    public abstract Object run() throws Throwable;
}

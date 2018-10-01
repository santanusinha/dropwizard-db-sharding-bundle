package io.dropwizard.sharding.transactions;

import io.dropwizard.hibernate.UnitOfWork;
import io.dropwizard.hibernate.UnitOfWorkAspect;
import io.dropwizard.sharding.hibernate.ConstTenantIdentifierResolver;
import io.dropwizard.sharding.hibernate.DelegatingTenantResolver;
import io.dropwizard.sharding.hibernate.MultiTenantUnitOfWorkAwareProxyFactory;
import lombok.AllArgsConstructor;
import org.hibernate.SessionFactory;
import org.hibernate.context.internal.ManagedSessionContext;

/**
 * Created on 23/09/18
 */

@AllArgsConstructor
public abstract class TransactionRunner {
    private MultiTenantUnitOfWorkAwareProxyFactory proxyFactory;
    private SessionFactory sessionFactory;
    private ConstTenantIdentifierResolver tenantIdentifierResolver;

    public Object start(boolean reUseSession, UnitOfWork unitOfWork) throws Throwable {
        if (reUseSession) {
            if (ManagedSessionContext.hasBind(sessionFactory)) {
                return run();
            }
        }
        DelegatingTenantResolver.getInstance().setDelegate(tenantIdentifierResolver);
        UnitOfWorkAspect aspect = proxyFactory.newAspect();
        Exception ex = null;
        Object result = null;
        try {
            aspect.beforeStart(unitOfWork);
            result = run();
            aspect.afterEnd();
            return result;
        } catch (Exception e) {
            ex = e;
            aspect.onError();
        } finally {
            aspect.onFinish();
            DelegatingTenantResolver.getInstance().setDelegate(null);
        }
        if (ex != null) {
            throw ex;
        }
        return result;
    }

    public abstract Object run() throws Throwable;
}

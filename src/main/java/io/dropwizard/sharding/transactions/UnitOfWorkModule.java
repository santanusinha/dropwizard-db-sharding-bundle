package io.dropwizard.sharding.transactions;

import com.google.inject.AbstractModule;
import com.google.inject.Inject;
import com.google.inject.Provides;
import com.google.inject.Singleton;
import com.google.inject.matcher.Matchers;
import io.dropwizard.hibernate.HibernateBundle;
import io.dropwizard.hibernate.UnitOfWork;
import io.dropwizard.hibernate.UnitOfWorkAwareProxyFactory;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.hibernate.SessionFactory;

import javax.inject.Named;

/**
 * Created on 19/09/18
 */
public class UnitOfWorkModule extends AbstractModule {

    @Override
    protected void configure() {
        UnitOfWorkInterceptor interceptor = new UnitOfWorkInterceptor();
        bindInterceptor(Matchers.any(), Matchers.annotatedWith(UnitOfWork.class), interceptor);
        requestInjection(interceptor);
    }

    @Provides
    @Singleton
    UnitOfWorkAwareProxyFactory provideUnitOfWorkAwareProxyFactory(HibernateBundle hibernateBundle) {
        return new UnitOfWorkAwareProxyFactory(hibernateBundle);
    }


    private static class UnitOfWorkInterceptor implements MethodInterceptor {

        @Inject
        UnitOfWorkAwareProxyFactory proxyFactory;
        @Inject
        @Named("session")
        SessionFactory sessionFactory;

        @Override
        public Object invoke(MethodInvocation mi) throws Throwable {
            TransactionRunner runner = new TransactionRunner(proxyFactory, sessionFactory) {
                @Override
                public Object run() throws Throwable {
                    return mi.proceed();
                }
            };
            return runner.start(mi.getMethod().isAnnotationPresent(ReuseSession.class),
                    mi.getMethod().getAnnotation(UnitOfWork.class));
        }
    }
}

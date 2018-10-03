package io.dropwizard.sharding.transactions;

import com.google.inject.AbstractModule;
import com.google.inject.Inject;
import com.google.inject.Provides;
import com.google.inject.Singleton;
import com.google.inject.matcher.Matchers;
import io.dropwizard.hibernate.UnitOfWork;
import io.dropwizard.sharding.hibernate.ConstTenantIdentifierResolver;
import io.dropwizard.sharding.hibernate.MultiTenantHibernateBundle;
import io.dropwizard.sharding.hibernate.MultiTenantUnitOfWorkAwareProxyFactory;
import io.dropwizard.sharding.providers.ShardKeyProvider;
import io.dropwizard.sharding.resolvers.bucket.BucketResolver;
import io.dropwizard.sharding.resolvers.shard.ShardResolver;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.hibernate.SessionFactory;

import javax.inject.Named;
import java.util.Objects;

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
    MultiTenantUnitOfWorkAwareProxyFactory provideUnitOfWorkAwareProxyFactory(MultiTenantHibernateBundle hibernateBundle) {
        return new MultiTenantUnitOfWorkAwareProxyFactory(hibernateBundle);
    }

    private static class UnitOfWorkInterceptor implements MethodInterceptor {

        @Inject
        MultiTenantUnitOfWorkAwareProxyFactory proxyFactory;
        @Inject
        @Named("session")
        SessionFactory sessionFactory;
        @Inject
        BucketResolver bucketResolver;
        @Inject
        ShardResolver shardResolver;
        @Inject
        ShardKeyProvider shardKeyProvider;

        @Override
        public Object invoke(MethodInvocation mi) throws Throwable {
            String shardKey = shardKeyProvider.getKey();
            Objects.requireNonNull(shardKey, "No shard-key set for this session");
            String bucketId = bucketResolver.resolve(shardKey);
            String shardId = shardResolver.resolve(bucketId);

            TransactionRunner runner = new TransactionRunner(proxyFactory, sessionFactory,
                    new ConstTenantIdentifierResolver(shardId)) {
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

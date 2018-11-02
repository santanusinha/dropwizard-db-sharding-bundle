/*
 * Copyright 2018 Cleartax
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package in.cleartax.dropwizard.sharding.transactions;

import com.google.inject.AbstractModule;
import com.google.inject.Inject;
import com.google.inject.matcher.Matchers;
import in.cleartax.dropwizard.sharding.hibernate.ConstTenantIdentifierResolver;
import in.cleartax.dropwizard.sharding.hibernate.MultiTenantDataSourceFactory;
import in.cleartax.dropwizard.sharding.hibernate.MultiTenantUnitOfWorkAwareProxyFactory;
import in.cleartax.dropwizard.sharding.providers.ShardKeyProvider;
import in.cleartax.dropwizard.sharding.resolvers.bucket.BucketResolver;
import in.cleartax.dropwizard.sharding.resolvers.shard.ShardResolver;
import io.dropwizard.hibernate.UnitOfWork;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.hibernate.SessionFactory;

import javax.inject.Named;
import java.util.Objects;

public class UnitOfWorkModule extends AbstractModule {

    @Override
    protected void configure() {
        UnitOfWorkInterceptor interceptor = new UnitOfWorkInterceptor();
        bindInterceptor(Matchers.any(), Matchers.annotatedWith(UnitOfWork.class), interceptor);
        requestInjection(interceptor);
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
        @Inject
        @Named("multiTenantConfiguration")
        MultiTenantDataSourceFactory multiTenantDataSourceFactory;

        private String getTenantIdentifier(MethodInvocation mi) {
            boolean useDefaultShard = mi.getMethod().isAnnotationPresent(DefaultTenant.class);
            String tenantId;
            if (!useDefaultShard && multiTenantDataSourceFactory.isAllowMultipleTenants()) {
                String shardKey = shardKeyProvider.getKey();
                Objects.requireNonNull(shardKey, "No tenant-identifier set for this session");
                String bucketId = bucketResolver.resolve(shardKey);
                tenantId = shardResolver.resolve(bucketId);
            } else {
                tenantId = multiTenantDataSourceFactory.getDefaultTenant();
            }
            return tenantId;
        }

        @Override
        public Object invoke(MethodInvocation mi) throws Throwable {
            String tenantId = getTenantIdentifier(mi);
            Objects.requireNonNull(tenantId, "No tenant-identifier found for this session");

            TransactionRunner runner = new TransactionRunner(proxyFactory, sessionFactory,
                    new ConstTenantIdentifierResolver(tenantId)) {
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

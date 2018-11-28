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
import in.cleartax.dropwizard.sharding.hibernate.DelegatingTenantResolver;
import in.cleartax.dropwizard.sharding.hibernate.MultiTenantSessionSource;
import in.cleartax.dropwizard.sharding.providers.ShardKeyProvider;
import in.cleartax.dropwizard.sharding.resolvers.bucket.BucketResolver;
import in.cleartax.dropwizard.sharding.resolvers.shard.ShardResolver;
import io.dropwizard.hibernate.UnitOfWork;
import lombok.extern.slf4j.Slf4j;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;

import java.util.Objects;

@Slf4j
public class UnitOfWorkModule extends AbstractModule {

    @Override
    protected void configure() {
        UnitOfWorkInterceptor interceptor = new UnitOfWorkInterceptor();
        bindInterceptor(Matchers.any(), Matchers.annotatedWith(UnitOfWork.class), interceptor);
        requestInjection(interceptor);
    }

    private static class UnitOfWorkInterceptor implements MethodInterceptor {

        @Inject
        BucketResolver bucketResolver;
        @Inject
        ShardResolver shardResolver;
        @Inject
        ShardKeyProvider shardKeyProvider;
        @Inject
        MultiTenantSessionSource multiTenantSessionSource;

        private String getTenantIdentifier(MethodInvocation mi) {
            boolean useDefaultShard = mi.getMethod().isAnnotationPresent(DefaultTenant.class);
            String tenantId;
            if (!useDefaultShard && multiTenantSessionSource.getDataSourceFactory().isAllowMultipleTenants()) {
                String shardKey = shardKeyProvider.getKey();
                if (shardKey != null) {
                    String bucketId = bucketResolver.resolve(shardKey);
                    tenantId = shardResolver.resolve(bucketId);
                } else {
                    tenantId = DelegatingTenantResolver.getInstance().resolveCurrentTenantIdentifier();
                }
            } else {
                tenantId = multiTenantSessionSource.getDataSourceFactory().getDefaultTenant();
            }
            return tenantId;
        }

        @Override
        public Object invoke(MethodInvocation mi) throws Throwable {
            String tenantId = getTenantIdentifier(mi);
            Objects.requireNonNull(tenantId, "No tenant-identifier found for this session");

            TransactionRunner runner = new TransactionRunner(multiTenantSessionSource.getUnitOfWorkAwareProxyFactory(),
                    multiTenantSessionSource.getSessionFactory(),
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

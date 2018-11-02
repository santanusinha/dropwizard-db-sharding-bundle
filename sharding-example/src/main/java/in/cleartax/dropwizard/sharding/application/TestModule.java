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

package in.cleartax.dropwizard.sharding.application;

import com.google.inject.AbstractModule;
import com.google.inject.Provides;
import com.google.inject.Singleton;
import in.cleartax.dropwizard.sharding.bucket.CustomerBucketResolver;
import in.cleartax.dropwizard.sharding.hibernate.*;
import in.cleartax.dropwizard.sharding.providers.ShardKeyProvider;
import in.cleartax.dropwizard.sharding.providers.ThreadLocalShardKeyProvider;
import in.cleartax.dropwizard.sharding.resolvers.bucket.BucketResolver;
import in.cleartax.dropwizard.sharding.resolvers.shard.ShardResolver;
import in.cleartax.dropwizard.sharding.services.CustomerService;
import in.cleartax.dropwizard.sharding.services.CustomerServiceImpl;
import in.cleartax.dropwizard.sharding.services.OrderService;
import in.cleartax.dropwizard.sharding.services.OrderServiceImpl;
import in.cleartax.dropwizard.sharding.utils.resolvers.shard.DbBasedShardResolver;
import io.dropwizard.setup.Bootstrap;
import org.hibernate.SessionFactory;
import org.hibernate.cfg.Configuration;
import org.hibernate.service.ServiceRegistry;

import javax.inject.Named;

public class TestModule extends AbstractModule {


    public static final String PCKGS = "in.cleartax.dropwizard.sharding";

    private final MultiTenantHibernateBundle<TestConfig> hibernateBundle =
            new ScanningMultiTenantHibernateBundle<TestConfig>(PCKGS,
                    new CustomSessionFactory()) {
                @Override
                public MultiTenantDataSourceFactory getDataSourceFactory(TestConfig configuration) {
                    return configuration.getMultiTenantDataSourceFactory();
                }
            };

    public TestModule(Bootstrap<TestConfig> bootstrap) {
        bootstrap.addBundle(hibernateBundle);
    }

    @Override
    protected void configure() {
        bind(ShardKeyProvider.class).to(ThreadLocalShardKeyProvider.class).in(Singleton.class);
        bind(BucketResolver.class).to(CustomerBucketResolver.class);
        bind(ShardResolver.class).to(DbBasedShardResolver.class);
        bind(OrderService.class).to(OrderServiceImpl.class);
        bind(CustomerService.class).to(CustomerServiceImpl.class);
    }

    @Provides
    @Named("session")
    public SessionFactory getSession() {
        return hibernateBundle.getSessionFactory();
    }

    @Provides
    public MultiTenantHibernateBundle getHibernateBundle() {
        return hibernateBundle;
    }

    @Provides
    @Singleton
    MultiTenantUnitOfWorkAwareProxyFactory provideUnitOfWorkAwareProxyFactory(MultiTenantHibernateBundle hibernateBundle) {
        return new MultiTenantUnitOfWorkAwareProxyFactory(hibernateBundle);
    }

    @Provides
    @Named("multiTenantConfiguration")
    public MultiTenantDataSourceFactory getMultiTenantDataSource(TestConfig config) {
        return config.getMultiTenantDataSourceFactory();
    }

    private class CustomSessionFactory extends MultiTenantSessionFactoryFactory {
        @Override
        protected void configure(Configuration configuration, ServiceRegistry registry) {
            configuration.setCurrentTenantIdentifierResolver(DelegatingTenantResolver.getInstance());
            super.configure(configuration, registry);
        }
    }
}

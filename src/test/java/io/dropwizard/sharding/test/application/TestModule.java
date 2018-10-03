package io.dropwizard.sharding.test.application;

import com.google.common.collect.ImmutableList;
import com.google.inject.AbstractModule;
import com.google.inject.Provides;
import com.google.inject.Singleton;
import io.dropwizard.setup.Bootstrap;
import io.dropwizard.sharding.hibernate.*;
import io.dropwizard.sharding.providers.ShardKeyProvider;
import io.dropwizard.sharding.providers.ThreadLocalShardKeyProvider;
import io.dropwizard.sharding.resolvers.bucket.BucketResolver;
import io.dropwizard.sharding.resolvers.shard.ShardResolver;
import io.dropwizard.sharding.test.testdata.entities.CustomerToBucketMapping;
import io.dropwizard.sharding.test.testdata.entities.Order;
import io.dropwizard.sharding.test.testdata.entities.OrderItem;
import io.dropwizard.sharding.test.testdata.resolvers.bucket.CustomerBucketResolver;
import io.dropwizard.sharding.test.testdata.services.OrderService;
import io.dropwizard.sharding.test.testdata.services.OrderServiceImpl;
import io.dropwizard.sharding.utils.entities.BucketToShardMapping;
import io.dropwizard.sharding.utils.resolvers.shard.DbBasedShardResolver;
import org.hibernate.SessionFactory;
import org.hibernate.cfg.Configuration;
import org.hibernate.service.ServiceRegistry;

import javax.inject.Named;

/**
 * Created on 02/10/18
 */
public class TestModule extends AbstractModule {


    public static final String PCKGS = "in.dropwizard.sharding.test";

    private final MultiTenantHibernateBundle<TestConfig> hibernateBundle =
            new ScanningMultiTenantHibernateBundle<TestConfig>(ImmutableList.of(Order.class, OrderItem.class,
                    CustomerToBucketMapping.class, BucketToShardMapping.class),
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

    private class CustomSessionFactory extends MultiTenantSessionFactoryFactory {
        @Override
        protected void configure(Configuration configuration, ServiceRegistry registry) {
            configuration.setCurrentTenantIdentifierResolver(DelegatingTenantResolver.getInstance());
            super.configure(configuration, registry);
        }
    }
}

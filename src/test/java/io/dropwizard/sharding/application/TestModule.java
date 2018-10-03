package io.dropwizard.sharding.application;

import com.google.inject.AbstractModule;
import io.dropwizard.setup.Bootstrap;
import io.dropwizard.sharding.hibernate.*;
import io.dropwizard.sharding.providers.ShardKeyProvider;
import io.dropwizard.sharding.providers.ThreadLocalShardKeyProvider;
import io.dropwizard.sharding.resolvers.bucket.BucketResolver;
import io.dropwizard.sharding.resolvers.bucket.ConsistentHashBasedBucketResolver;
import io.dropwizard.sharding.resolvers.shard.ShardResolver;
import io.dropwizard.sharding.testdata.services.OrderService;
import io.dropwizard.sharding.testdata.services.OrderServiceImpl;
import io.dropwizard.sharding.utils.resolvers.shard.DbBasedShardResolver;
import org.hibernate.cfg.Configuration;
import org.hibernate.service.ServiceRegistry;

/**
 * Created on 02/10/18
 */
public class TestModule extends AbstractModule {


    public static final String[] PCKGS = {"in.dropwizard.sharding"};

    private final MultiTenantHibernateBundle<TestConfig> hibernateBundle =
            new ScanningMultiTenantHibernateBundle<TestConfig>(PCKGS, new CustomSessionFactory()) {
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
        bind(ShardKeyProvider.class).to(ThreadLocalShardKeyProvider.class);
        bind(BucketResolver.class).to(ConsistentHashBasedBucketResolver.class);
        bind(ShardResolver.class).to(DbBasedShardResolver.class);
        bind(OrderService.class).to(OrderServiceImpl.class);
    }

    private class CustomSessionFactory extends MultiTenantSessionFactoryFactory {
        @Override
        protected void configure(Configuration configuration, ServiceRegistry registry) {
            configuration.setCurrentTenantIdentifierResolver(DelegatingTenantResolver.getInstance());
            super.configure(configuration, registry);
        }
    }
}

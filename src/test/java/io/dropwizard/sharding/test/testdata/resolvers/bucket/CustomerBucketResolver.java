package io.dropwizard.sharding.test.testdata.resolvers.bucket;

import io.dropwizard.hibernate.UnitOfWork;
import io.dropwizard.sharding.hibernate.MultiTenantUnitOfWorkAwareProxyFactory;
import io.dropwizard.sharding.resolvers.bucket.BucketResolver;
import io.dropwizard.sharding.test.testdata.dao.CustomerToBucketMappingDAO;
import io.dropwizard.sharding.transactions.DefaultTenant;
import lombok.RequiredArgsConstructor;

import javax.inject.Inject;
import java.util.Optional;

/**
 * Created on 03/10/18
 */

@RequiredArgsConstructor(onConstructor = @__(@Inject))
public class CustomerBucketResolver implements BucketResolver {

    private final CustomerToBucketMappingDAO dao;
    private final MultiTenantUnitOfWorkAwareProxyFactory proxyFactory;


    @Override
    @UnitOfWork
    @DefaultTenant
    public String resolve(String customerId) {
        Optional<String> bucketId = dao.getBucketId(customerId);
        if (!bucketId.isPresent()) {
            throw new IllegalAccessError(String.format("%s customer not mapped to any bucket", customerId));
        }
        return bucketId.get();
    }
}

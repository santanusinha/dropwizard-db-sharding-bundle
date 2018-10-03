package io.dropwizard.sharding.utils.resolvers.shard;

import io.dropwizard.hibernate.UnitOfWork;
import io.dropwizard.sharding.resolvers.shard.ShardResolver;
import io.dropwizard.sharding.transactions.DefaultTenant;
import io.dropwizard.sharding.utils.dao.BucketToShardMappingDAO;
import lombok.RequiredArgsConstructor;

import javax.inject.Inject;
import java.util.Optional;

/**
 * Created on 03/10/18
 */
@RequiredArgsConstructor(onConstructor = @__(@Inject))
public class DbBasedShardResolver implements ShardResolver {

    private final BucketToShardMappingDAO dao;

    @Override
    @UnitOfWork
    @DefaultTenant
    public String resolve(String bucketId) {
        Optional<String> shardId = dao.getShardId(bucketId);
        if (!shardId.isPresent()) {
            throw new IllegalAccessError(String.format("%s bucket not mapped to any shard", bucketId));
        }
        return shardId.get();
    }
}

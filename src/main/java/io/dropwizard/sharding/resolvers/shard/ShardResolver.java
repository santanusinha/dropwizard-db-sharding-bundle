package io.dropwizard.sharding.resolvers.shard;

public interface ShardResolver {
    int resolve(int bucketId);
}

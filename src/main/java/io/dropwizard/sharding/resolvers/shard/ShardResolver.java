package io.dropwizard.sharding.resolvers.shard;

public interface ShardResolver {
    String resolve(String bucketId);
}

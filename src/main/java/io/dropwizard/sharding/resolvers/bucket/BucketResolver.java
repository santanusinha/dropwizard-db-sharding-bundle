package io.dropwizard.sharding.resolvers.bucket;

public interface BucketResolver {
    int resolve(String shardKey);
}

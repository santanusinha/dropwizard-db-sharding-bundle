package io.dropwizard.sharding.resolvers.bucket;

public interface BucketResolver<T> {
    int resolve(T shardKey);
}

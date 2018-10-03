package io.dropwizard.sharding.resolvers.bucket;

public interface BucketResolver {
    String resolve(String shardKey);
}

package io.appform.dropwizard.sharding.sharding;

import io.appform.dropwizard.sharding.sharding.impl.ConsistentHashBucketIdExtractor;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BucketResolverUnknownTenantTest {

    @Test
    void unknownTenantIsRejectedWithANamedError() {
        final ShardManager shardManager = new BalancedShardManager(4);
        final BucketResolver<String> resolver = new BucketResolver<>(
                Map.of("tenant1", new ConsistentHashBucketIdExtractor<>(shardManager)),
                Map.of(getClass().getName(),
                        EntityMeta.builder().bucketKeyColumnName("bucket_key").build()));

        final IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> resolver.getBucketInfo("no-such-tenant", "customer-1", getClass()));
        assertTrue(exception.getMessage().contains("no-such-tenant"));
    }
}

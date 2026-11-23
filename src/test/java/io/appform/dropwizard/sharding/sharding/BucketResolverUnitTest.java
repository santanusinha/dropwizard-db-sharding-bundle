package io.appform.dropwizard.sharding.sharding;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.function.ToIntFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BucketResolverUnitTest {

    static class Entity {
    }

    private static final Map<String, EntityMeta> META = Map.of(Entity.class.getName(),
            EntityMeta.builder().bucketKeyColumnName("bucket_key").build());

    @Test
    void resolvesWithTenantFunction() {
        final Map<String, ToIntFunction<String>> fns = Map.of("t1", key -> 7);
        final var info = new BucketResolver<>(fns, META).getBucketInfo("t1", "k", Entity.class);
        assertEquals(7, info.getValue());
        assertEquals("bucket_key", info.getKey());
    }

    @Test
    void unknownTenantFails() {
        final Map<String, ToIntFunction<String>> fns = Map.of("t1", key -> 7);
        final var resolver = new BucketResolver<>(fns, META);
        final var ex = assertThrows(IllegalArgumentException.class,
                () -> resolver.getBucketInfo("t2", "k", Entity.class));
        assertEquals("Unknown tenant: t2", ex.getMessage());
    }

    @Test
    void missingEntityMetaReturnsNull() {
        final Map<String, ToIntFunction<String>> fns = Map.of("t1", key -> 7);
        assertNull(new BucketResolver<>(fns, Map.of()).getBucketInfo("t1", "k", Entity.class));
        assertNull(new BucketResolver<>(fns, META).getBucketInfo("t1", "k", String.class));
    }
}

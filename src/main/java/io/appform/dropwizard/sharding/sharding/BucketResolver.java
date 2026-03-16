package io.appform.dropwizard.sharding.sharding;

import com.google.common.base.Preconditions;
import org.apache.commons.collections.MapUtils;

import java.util.Map;

public class BucketResolver<T> {

    private final BucketIdExtractor<T> bucketIdExtractor;
    private final Map<String, EntityMeta> initialisedEntitiesMeta;

    public BucketResolver(final BucketIdExtractor<T> bucketIdExtractor,
                          final Map<String, EntityMeta> initialisedEntitiesMeta) {
        Preconditions.checkArgument(initialisedEntitiesMeta != null, "initialisedEntitiesMeta must not be null");
        Preconditions.checkArgument(bucketIdExtractor != null, "BucketIdExtractor must not be null");
        this.bucketIdExtractor = bucketIdExtractor;
        this.initialisedEntitiesMeta = initialisedEntitiesMeta;
    }

    public <U> BucketInfo getBucketInfo(final String tenantId,
                                        final T shardingKey,
                                        final Class<U> clazz) {
        if (MapUtils.isEmpty(initialisedEntitiesMeta)) {
            return null;
        }

        final var entityMeta = initialisedEntitiesMeta.get(clazz.getName());
        if (entityMeta == null) {
            return null;
        }

        final var bucketId = bucketIdExtractor.bucketId(tenantId, shardingKey);

        return BucketInfo.builder()
                .value(bucketId)
                .key(entityMeta.getBucketKeyColumnName())
                .build();
    }
}

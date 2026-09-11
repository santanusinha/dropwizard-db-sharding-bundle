package io.appform.dropwizard.sharding.sharding;

import com.google.common.base.Preconditions;
import org.apache.commons.collections.MapUtils;

import java.util.Map;

/***
 * This class is responsible for resolving the bucket information for a given sharding key and tenant id.
 * it gets initialised with a BucketIdExtractor and a map of initialised entities meta.
 * The getBucketInfo method takes the tenant id, sharding key and the class of the entity and returns the bucket information for that entity.
 * If the entity is not initialised or the bucket information cannot be resolved, it returns null.
 *
 * @param <T>
 */
public class BucketResolver<T> {

    private final Map<String, BucketIdExtractor<T>> bucketIdExtractors;
    private final Map<String, EntityMeta> initialisedEntitiesMeta;

    public BucketResolver(final Map<String, BucketIdExtractor<T>> bucketIdExtractors,
                          final Map<String, EntityMeta> initialisedEntitiesMeta) {
        Preconditions.checkArgument(initialisedEntitiesMeta != null, "initialisedEntitiesMeta must not be null");
        Preconditions.checkArgument(bucketIdExtractors != null, "BucketIdExtractors must not be null");
        this.bucketIdExtractors = bucketIdExtractors;
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

        final var bucketIdExtractor = bucketIdExtractors.get(tenantId);
        if (bucketIdExtractor == null) {
            throw new IllegalArgumentException("Unknown tenant: " + tenantId);
        }
        final var bucketId = bucketIdExtractor.bucketId(shardingKey);

        return BucketInfo.builder()
                .value(bucketId)
                .key(entityMeta.getBucketKeyColumnName())
                .build();
    }
}

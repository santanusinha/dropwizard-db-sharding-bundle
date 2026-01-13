package io.appform.dropwizard.sharding.sharding;

import com.google.common.base.Preconditions;
import org.apache.commons.collections.MapUtils;
import org.apache.commons.lang3.StringUtils;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

public class BucketKeyReader {

    private static final AtomicReference<BucketKeyReader> INSTANCE = new AtomicReference<>();

    private final Map<String, BucketIdExtractor<String>> bucketIdExtractors;
    private final Map<String, EntityMeta> initialisedEntitiesMeta;

    private BucketKeyReader(final Map<String, BucketIdExtractor<String>> bucketIdExtractors,
                            final Map<String, EntityMeta> initialisedEntitiesMeta) {
        Preconditions.checkArgument(initialisedEntitiesMeta != null, "initialisedEntitiesMeta must not be null");
        Preconditions.checkArgument(bucketIdExtractors != null, "BucketIdExtractor must not be null");
        this.bucketIdExtractors = bucketIdExtractors;
        this.initialisedEntitiesMeta = initialisedEntitiesMeta;
    }

    public static BucketKeyReader getInstance(final Map<String, BucketIdExtractor<String>> bucketIdExtractors,
                                              final Map<String, EntityMeta> initialisedEntitiesMeta) {
        BucketKeyReader current = INSTANCE.get();
        if (current == null) {
            synchronized (BucketKeyReader.class) {
                BucketKeyReader reader = new BucketKeyReader(bucketIdExtractors, initialisedEntitiesMeta);
                if(INSTANCE.compareAndSet(null, reader)) {
                    return reader;
                }
                return INSTANCE.get();
            }
        }
        return current;
    }

    public <U> BucketKeyInfo getBucketId(final String tenantId,
                                         final String shardingKey,
                                         final Class<U> clazz) {
        if (MapUtils.isEmpty(initialisedEntitiesMeta)) {
            return null;
        }

        final var entityMeta = initialisedEntitiesMeta.get(clazz.getName());
        if (entityMeta == null) {
            return null;
        }

        if (MapUtils.isEmpty(bucketIdExtractors)) {
            return null;
        }

        final var bucketIdExtractor = bucketIdExtractors.get(tenantId);
        if (Objects.isNull(bucketIdExtractor)) {
            return null;
        }

        final var bucketId = bucketIdExtractor.bucketId(tenantId, shardingKey);

        return BucketKeyInfo.builder()
                .fieldName(entityMeta.getBucketKeyFieldName())
                .value(bucketId)
                .build();
    }
}

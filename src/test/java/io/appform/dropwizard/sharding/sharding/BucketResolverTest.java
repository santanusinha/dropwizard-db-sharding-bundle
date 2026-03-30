package io.appform.dropwizard.sharding.sharding;

import io.appform.dropwizard.sharding.BalancedDBShardingBundle;
import io.appform.dropwizard.sharding.BundleBasedTestBase;
import io.appform.dropwizard.sharding.DBShardingBundleBase;
import io.appform.dropwizard.sharding.config.ShardedHibernateFactory;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.SneakyThrows;
import lombok.ToString;
import lombok.experimental.FieldNameConstants;
import lombok.val;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.Table;

public class BucketResolverTest extends BundleBasedTestBase {

    private static final String shardingKey = "PV10";
    private static final String childValue = "CV10";
    private static final int preComputedBucketKeyValue = 103;

    @Override
    protected DBShardingBundleBase<TestConfig> getBundle() {
        return new BalancedDBShardingBundle<TestConfig>(EntityWithBucketKeyWithColumnName.class, EntityWithBucketKeyWithoutColumnName.class) {
            @Override
            protected ShardedHibernateFactory getConfig(TestConfig config) {
                return testConfig.getShards();
            }
        };
    }

    @SneakyThrows
    @Test
    void testEntity() {
        val bundle = createBundle();
        val entityWithBucketKeyWithColumnNameLookupDao = bundle.createParentObjectDao(EntityWithBucketKeyWithColumnName.class);
        val entityWithBucketKeyWithoutColumnNameLookupDao = bundle.createParentObjectDao(EntityWithBucketKeyWithoutColumnName.class);


        val objWithBucketColumnAnnotation = buildEntityWithBucketKeyWithColumnName(shardingKey);
        entityWithBucketKeyWithColumnNameLookupDao.save(objWithBucketColumnAnnotation);
        val savedObjWithBucketColumnAnnotation = entityWithBucketKeyWithColumnNameLookupDao.get(shardingKey).orElse(null);
        Assertions.assertNotNull(savedObjWithBucketColumnAnnotation);

        val bucketKeyExtractedWithColumn = bundle.getBucketInfo(shardingKey, EntityWithBucketKeyWithColumnName.class);
        Assertions.assertNotNull(bucketKeyExtractedWithColumn);
        Assertions.assertEquals(savedObjWithBucketColumnAnnotation.getBucketKey(), bucketKeyExtractedWithColumn.getValue());

        val objWithoutBucketColumnAnnotation = buildEntityWithBucketKeyWithoutColumnName(shardingKey);
        entityWithBucketKeyWithoutColumnNameLookupDao.save(objWithoutBucketColumnAnnotation);
        val savedObjWithoutBucketColumnAnnotation = entityWithBucketKeyWithoutColumnNameLookupDao.get(shardingKey).orElse(null);
        Assertions.assertNotNull(savedObjWithoutBucketColumnAnnotation);

        val bucketKeyExtractedWithoutColumn = bundle.getBucketInfo(shardingKey, EntityWithBucketKeyWithoutColumnName.class);
        Assertions.assertNotNull(bucketKeyExtractedWithoutColumn);
        Assertions.assertEquals(savedObjWithoutBucketColumnAnnotation.getBucketKey(), bucketKeyExtractedWithoutColumn.getValue());
    }

    @Entity
    @Table(name = "entity_with_bucket_key")
    @Getter
    @Setter
    @ToString
    @NoArgsConstructor
    @AllArgsConstructor
    @FieldNameConstants
    public static class EntityWithBucketKeyWithColumnName {

        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        private long id;

        @Column
        @LookupKey
        private String name;

        @Column(name = "bucket_key")
        @BucketKey
        private int bucketKey;
    }

    @Entity
    @Table(name = "entity_without_bucket_key")
    @Getter
    @Setter
    @ToString
    @NoArgsConstructor
    @AllArgsConstructor
    @FieldNameConstants
    public static class EntityWithBucketKeyWithoutColumnName {

        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        private long id;

        @Column
        @LookupKey
        private String name;

        @Column
        @BucketKey
        private int bucketKey;
    }

    private EntityWithBucketKeyWithColumnName buildEntityWithBucketKeyWithColumnName(final String lookupKey) {
        val obj = new EntityWithBucketKeyWithColumnName();
        obj.setName(lookupKey);
        return obj;
    }

    private EntityWithBucketKeyWithoutColumnName buildEntityWithBucketKeyWithoutColumnName(final String lookupKey) {
        val obj = new EntityWithBucketKeyWithoutColumnName();
        obj.setName(lookupKey);
        return obj;
    }

    private DBShardingBundleBase<TestConfig> createBundle() {
        val bundle = getBundle();
        bundle.initialize(bootstrap);
        bundle.run(testConfig, environment);
        return bundle;
    }

}

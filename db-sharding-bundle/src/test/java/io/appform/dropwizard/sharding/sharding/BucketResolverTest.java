package io.appform.dropwizard.sharding.sharding;

import io.appform.dropwizard.sharding.BalancedDBShardingBundle;
import io.appform.dropwizard.sharding.BundleBasedTestBase;
import io.appform.dropwizard.sharding.DBShardingBundleBase;
import io.appform.dropwizard.sharding.config.ShardedHibernateFactory;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
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


public class BucketResolverTest extends BundleBasedTestBase {

    private static final String SHARDING_KEY = "PV10";

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


        val objWithBucketColumnAnnotation = buildEntityWithBucketKeyWithColumnName(SHARDING_KEY);
        entityWithBucketKeyWithColumnNameLookupDao.save(objWithBucketColumnAnnotation);
        val savedObjWithBucketColumnAnnotation = entityWithBucketKeyWithColumnNameLookupDao.get(SHARDING_KEY).orElse(null);
        Assertions.assertNotNull(savedObjWithBucketColumnAnnotation);

        val bucketKeyExtractedWithColumn = bundle.getBucketInfo(SHARDING_KEY, EntityWithBucketKeyWithColumnName.class);
        Assertions.assertNotNull(bucketKeyExtractedWithColumn);
        Assertions.assertEquals(savedObjWithBucketColumnAnnotation.getBucketKey(), bucketKeyExtractedWithColumn.getValue());

        val objWithoutBucketColumnAnnotation = buildEntityWithBucketKeyWithoutColumnName(SHARDING_KEY);
        entityWithBucketKeyWithoutColumnNameLookupDao.save(objWithoutBucketColumnAnnotation);
        val savedObjWithoutBucketColumnAnnotation = entityWithBucketKeyWithoutColumnNameLookupDao.get(SHARDING_KEY).orElse(null);
        Assertions.assertNotNull(savedObjWithoutBucketColumnAnnotation);

        val bucketKeyExtractedWithoutColumn = bundle.getBucketInfo(SHARDING_KEY, EntityWithBucketKeyWithoutColumnName.class);
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

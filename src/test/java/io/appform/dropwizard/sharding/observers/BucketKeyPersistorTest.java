package io.appform.dropwizard.sharding.observers;

import io.appform.dropwizard.sharding.BalancedDBShardingBundle;
import io.appform.dropwizard.sharding.BundleBasedTestBase;
import io.appform.dropwizard.sharding.DBShardingBundleBase;
import io.appform.dropwizard.sharding.config.ShardedHibernateFactory;
import io.appform.dropwizard.sharding.observers.entity.BaseChild;
import io.appform.dropwizard.sharding.observers.entity.ChildWithDuplicateBucketKey;
import io.appform.dropwizard.sharding.observers.entity.HierarchicalBaseChild;
import io.appform.dropwizard.sharding.observers.entity.HierarchicalBaseChildImpl;
import io.appform.dropwizard.sharding.observers.entity.HierarchicalChildImpl;
import io.appform.dropwizard.sharding.observers.entity.ParentWithoutBucketKey;
import io.appform.dropwizard.sharding.observers.entity.SimpleChild;
import io.appform.dropwizard.sharding.observers.entity.SimpleParent;
import lombok.SneakyThrows;
import lombok.val;
import org.hibernate.criterion.DetachedCriteria;
import org.hibernate.criterion.Property;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class BucketKeyPersistorTest extends BundleBasedTestBase {

    private static final String shardingKey = "PV10";
    private static final String childValue = "CV10";
    private static final int preComputedBucketKeyValue = 103;

    @Override
    protected DBShardingBundleBase<TestConfig> getBundle() {
        return new BalancedDBShardingBundle<TestConfig>(SimpleChild.class, SimpleParent.class,
                ParentWithoutBucketKey.class, HierarchicalBaseChild.class, HierarchicalBaseChildImpl.class,
                HierarchicalChildImpl.class) {
            @Override
            protected ShardedHibernateFactory getConfig(TestConfig config) {
                return testConfig.getShards();
            }
        };
    }

    protected DBShardingBundleBase<TestConfig> getBundleWithMalformedEntity() {
        return new BalancedDBShardingBundle<TestConfig>(ChildWithDuplicateBucketKey.class, BaseChild.class) {
            @Override
            protected ShardedHibernateFactory getConfig(TestConfig config) {
                return testConfig.getShards();
            }
        };
    }

    @SneakyThrows
    @Test
    public void testObserverInvocationForSave() {
        val bundle = createBundle();
        val parentDao = bundle.createParentObjectDao(SimpleParent.class);
        val childDao = bundle.createRelatedObjectDao(SimpleChild.class);

        val obj = buildParentObj(shardingKey);
        parentDao.save(obj);

        val persistedParent = parentDao.get(shardingKey);
        assertNotNull(persistedParent.get());
        assertEquals(preComputedBucketKeyValue, persistedParent.get().getBucketKey());

        val childObj = buildSimpleChildObj(shardingKey, childValue);
        childDao.save(shardingKey, childObj);
        val persistedChild = childDao.select(shardingKey,  DetachedCriteria.forClass(SimpleChild.class)
                        .add(Property.forName(SimpleChild.Fields.parent)
                                .eq(shardingKey)),
                0,
                Integer.MAX_VALUE);
        assertNotNull(persistedChild.get(0));
        assertEquals(preComputedBucketKeyValue, persistedChild.get(0).getBucketKey());
    }

    @SneakyThrows
    @Test
    public void testObserverInvocationForSaveAll() {
        val bundle = createBundle();
        val parentDao = bundle.createParentObjectDao(SimpleParent.class);
        val childDao = bundle.createRelatedObjectDao(SimpleChild.class);

        val obj = buildParentObj(shardingKey);
        parentDao.save(obj);
        val persistedParent = parentDao.get(shardingKey);
        val bucketKeyValue = persistedParent.get().getBucketKey();
        assertEquals(preComputedBucketKeyValue, bucketKeyValue);

        val childObj = buildSimpleChildObj(shardingKey, childValue);
        childDao.saveAll(shardingKey, Collections.singletonList(childObj));
        val persistedChild = childDao.select(shardingKey,  DetachedCriteria.forClass(SimpleChild.class)
                        .add(Property.forName(SimpleChild.Fields.parent)
                                .eq(shardingKey)),
                0,
                Integer.MAX_VALUE);
        assertNotNull(persistedChild.get(0));
        assertEquals(preComputedBucketKeyValue, persistedChild.get(0).getBucketKey());
    }

    @SneakyThrows
    @Test
    public void testObserverForCreateAndUpdate() {
        val bundle = createBundle();
        val childDao = bundle.createRelatedObjectDao(SimpleChild.class);
        val criteria = DetachedCriteria.forClass(SimpleChild.class)
                .add(Property.forName(SimpleChild.Fields.parent).eq(shardingKey));
        val childObj = buildSimpleChildObj(shardingKey, childValue);
        childDao.createOrUpdate(shardingKey, criteria, t -> t, () -> childObj);

        var persistedChild = childDao.select(shardingKey, criteria, 0, Integer.MAX_VALUE);
        assertNotNull(persistedChild.get(0));
        assertEquals(preComputedBucketKeyValue, persistedChild.get(0).getBucketKey());

        childDao.createOrUpdate(shardingKey, criteria, t -> {
            t.setBucketKey(-1);
            return t;
        }, () -> childObj);

        persistedChild = childDao.select(shardingKey, criteria, 0, Integer.MAX_VALUE);
        assertNotNull(persistedChild.get(0));
        assertEquals(preComputedBucketKeyValue, persistedChild.get(0).getBucketKey());
    }

    @SneakyThrows
    @Test
    public void testObserverForCreateAndUpdateInLockedContext() {
        val bundle = createBundle();
        val childDao = bundle.createRelatedObjectDao(SimpleChild.class);

        val criteria = DetachedCriteria.forClass(SimpleChild.class)
                .add(Property.forName(SimpleChild.Fields.parent).eq(shardingKey));
        val childObj = buildSimpleChildObj(shardingKey, childValue);

        var lockedContext = childDao.saveAndGetExecutor(shardingKey, childObj);
        lockedContext.createOrUpdate(childDao, criteria, t -> t, () -> childObj).execute();

        var getChild = childDao.select(shardingKey, criteria, 0, Integer.MAX_VALUE);
        assertNotNull(getChild.get(0));
        assertEquals(preComputedBucketKeyValue, getChild.get(0).getBucketKey());

        lockedContext = childDao.lockAndGetExecutor(shardingKey, criteria);
        lockedContext.createOrUpdate(childDao, criteria, t -> {
            t.setBucketKey(-1);
            return t;
        }, () -> childObj).execute();

        getChild = childDao.select(shardingKey, criteria, 0, Integer.MAX_VALUE);
        assertNotNull(getChild.get(0));
        assertEquals(preComputedBucketKeyValue, getChild.get(0).getBucketKey());
    }

    @SneakyThrows
    @Test
    public void testObserverForCreateAndUpdateByLookupKey() {
        val bundle = createBundle();
        val parentDao = bundle.createParentObjectDao(SimpleParent.class);

        val parentObj = buildParentObj(shardingKey);
        parentDao.createOrUpdate(shardingKey, (t) -> t, () -> parentObj);

        var persistedParent = parentDao.get(shardingKey);
        assertNotNull(persistedParent.get());
        assertEquals(preComputedBucketKeyValue, persistedParent.get().getBucketKey());

        parentDao.createOrUpdate(shardingKey, (t) -> {
            t.setBucketKey(-1);
            return t;
        }, () -> parentObj);

        persistedParent = parentDao.get(shardingKey);
        assertNotNull(persistedParent.get());
        assertEquals(preComputedBucketKeyValue, persistedParent.get().getBucketKey());
    }

    @SneakyThrows
    @Test
    public void testObserverForGetAndUpdateByLookupKey() {
        val bundle = createBundle();
        val parentDao = bundle.createParentObjectDao(SimpleParent.class);

        val parentObj = buildParentObj(shardingKey);
        parentDao.save(parentObj);

        var persistedParent = parentDao.get(shardingKey);
        assertNotNull(persistedParent.get());
        assertEquals(preComputedBucketKeyValue, persistedParent.get().getBucketKey());

        parentDao.update(shardingKey, (t) -> {
            if (t.isPresent()) {
                t.get().setBucketKey(-1);
                return t.get();
            }
            return null;
        });

        persistedParent = parentDao.get(shardingKey);
        assertNotNull(persistedParent.get());
        assertEquals(preComputedBucketKeyValue, persistedParent.get().getBucketKey());
    }

    @SneakyThrows
    @Test
    public void testObserverForSelectAndUpdate() {
        val bundle = createBundle();
        val childDao = bundle.createRelatedObjectDao(SimpleChild.class);

        val criteria = DetachedCriteria.forClass(SimpleChild.class)
                .add(Property.forName(SimpleChild.Fields.parent).eq(shardingKey));
        val childObj = buildSimpleChildObj(shardingKey, childValue);

        childDao.save(shardingKey, childObj);
        var persistedChild = childDao.select(shardingKey, criteria, 0, Integer.MAX_VALUE);
        assertNotNull(persistedChild.get(0));
        assertEquals(preComputedBucketKeyValue, persistedChild.get(0).getBucketKey());

        childDao.update(shardingKey, criteria, t -> {
            t.setBucketKey(-1);
            return t;
        });

        persistedChild = childDao.select(shardingKey, criteria, 0, Integer.MAX_VALUE);
        assertNotNull(persistedChild.get(0));
        assertEquals(preComputedBucketKeyValue, persistedChild.get(0).getBucketKey());
    }

    @SneakyThrows
    @Test
    public void testObserverForUpdateAll() {
        val bundle = createBundle();
        val childDao = bundle.createRelatedObjectDao(SimpleChild.class);

        val criteria = DetachedCriteria.forClass(SimpleChild.class)
                .add(Property.forName(SimpleChild.Fields.parent).eq(shardingKey));
        val childObj = buildSimpleChildObj(shardingKey, childValue);

        childDao.save(shardingKey, childObj);
        var persistedChild = childDao.select(shardingKey, criteria, 0, Integer.MAX_VALUE);
        assertNotNull(persistedChild.get(0));
        assertEquals(preComputedBucketKeyValue, persistedChild.get(0).getBucketKey());

        childDao.updateAll(shardingKey, 0, Integer.MAX_VALUE, criteria, t -> {
            t.setBucketKey(-1);
            return t;
        });

        persistedChild = childDao.select(shardingKey, criteria, 0, Integer.MAX_VALUE);
        assertNotNull(persistedChild.get(0));
        assertEquals(preComputedBucketKeyValue, persistedChild.get(0).getBucketKey());
    }

    @SneakyThrows
    @Test
    public void testObserverForGetAndUpdate() {
        val bundle = createBundle();
        val childDao = bundle.createRelatedObjectDao(SimpleChild.class);

        val criteria = DetachedCriteria.forClass(SimpleChild.class)
                .add(Property.forName(SimpleChild.Fields.parent).eq(shardingKey));
        val childObj = buildSimpleChildObj(shardingKey, childValue);

        childDao.save(shardingKey, childObj);
        var persistedChild = childDao.select(shardingKey, criteria, 0, Integer.MAX_VALUE);
        assertNotNull(persistedChild.get(0));
        assertEquals(preComputedBucketKeyValue, persistedChild.get(0).getBucketKey());

        childDao.update(shardingKey, persistedChild.get(0).getId(), t -> {
            t.setBucketKey(-1);
            return t;
        });

        persistedChild = childDao.select(shardingKey, criteria, 0, Integer.MAX_VALUE);
        assertNotNull(persistedChild.get(0));
        assertEquals(preComputedBucketKeyValue, persistedChild.get(0).getBucketKey());
    }

    @SneakyThrows
    @Test
    public void testWhenBucketKeyNotPresent() {
        val bundle = createBundle();
        val parentWithoutBucketKeyDao = bundle.createParentObjectDao(ParentWithoutBucketKey.class);

        val obj = buildParentWithoutBucketKeyObj(shardingKey);
        parentWithoutBucketKeyDao.save(obj);

        val persistedParent = parentWithoutBucketKeyDao.get(shardingKey);
        assertTrue(persistedParent.isPresent());
        assertEquals(shardingKey, persistedParent.get().getName());
    }

    @SneakyThrows
    @Test
    public void testWithMalformedEntity() {
        final var error = Assertions.assertThrows(RuntimeException.class, this::createBundleWithMalformedEntity);
        final var errorMessage = String.format("Failed to validate/resolve entity meta for " +
                ChildWithDuplicateBucketKey.class.getName());
        Assertions.assertEquals(errorMessage, error.getMessage());
    }

    @SneakyThrows
    @Test
    public void testObserverInvocationForSaveWithMultiHeirarchy() {
        val bundle = createBundle();
        val childDao = bundle.createRelatedObjectDao(HierarchicalChildImpl.class);

        val childObj = buildHierarchicalChildImplObj(shardingKey, childValue);
        childDao.save(shardingKey, childObj);
        val persistedChild = childDao.select(shardingKey,  DetachedCriteria.forClass(HierarchicalChildImpl.class)
                        .add(Property.forName("parent")
                                .eq(shardingKey)),
                0,
                Integer.MAX_VALUE);
        assertNotNull(persistedChild.get(0));
        assertEquals(preComputedBucketKeyValue, persistedChild.get(0).getBucketKey());
    }

    private DBShardingBundleBase<TestConfig> createBundle() {
        val bundle = getBundle();
        bundle.initialize(bootstrap);
        bundle.initialize(bootstrap);
        bundle.run(testConfig, environment);
        bundle.run(testConfig, environment);
        return bundle;
    }

    private DBShardingBundleBase<TestConfig> createBundleWithMalformedEntity() {
        val bundle = getBundleWithMalformedEntity();
        bundle.initialize(bootstrap);
        bundle.initialize(bootstrap);
        bundle.run(testConfig, environment);
        bundle.run(testConfig, environment);
        return bundle;
    }

    private SimpleParent buildParentObj(final String lookupKey) {
        val obj = new SimpleParent();
        obj.setName(lookupKey);
        // setting incorrect bucketKey, should not be persisted or updated anywhere.
        obj.setBucketKey(-1);
        return obj;
    }

    private SimpleChild buildSimpleChildObj(final String shardingKey,
                                            final String value) {
        val obj = new SimpleChild();
        obj.setParent(shardingKey);
        obj.setValue(value);
        // setting incorrect bucketKey, should not be persisted or updated anywhere.
        obj.setBucketKey(-1);
        return obj;
    }

    private HierarchicalChildImpl buildHierarchicalChildImplObj(final String shardingKey,
                                                                final String value) {
        val obj = new HierarchicalChildImpl();
        obj.setParent(shardingKey);
        obj.setValue(value);
        // setting incorrect bucketKey, should not be persisted or updated anywhere.
        obj.setBucketKey(-1);
        return obj;
    }

    private ParentWithoutBucketKey buildParentWithoutBucketKeyObj (final String lookupKey) {
        val obj = new ParentWithoutBucketKey();
        obj.setName(lookupKey);
        // setting incorrect bucketKey, should not be persisted or updated anywhere.
        return obj;
    }

}
package io.appform.dropwizard.sharding.observers;

import io.appform.dropwizard.sharding.BalancedDBShardingBundle;
import io.appform.dropwizard.sharding.BundleBasedTestBase;
import io.appform.dropwizard.sharding.DBShardingBundleBase;
import io.appform.dropwizard.sharding.config.ShardedHibernateFactory;
import io.appform.dropwizard.sharding.observers.entity.CopyFromParentIntegrationChild;
import io.appform.dropwizard.sharding.observers.entity.CopyFromParentIntegrationParent;
import lombok.SneakyThrows;
import lombok.val;
import org.hibernate.criterion.DetachedCriteria;
import org.hibernate.criterion.Property;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end tests that exercise {@link CopyFromParentObserver} registered on a real
 * {@link DBShardingBundleBase}, going through the actual {@link io.appform.dropwizard.sharding.dao.LockedContext}
 * / transaction / observer chain against a real (H2) database - rather than calling
 * {@code persistor.visit(...)} directly with a stubbed {@code saver(e -> e)} and
 * {@code opContext.apply(null)}, which does not exercise persistence, bucket-key computation,
 * or transaction rollback at all.
 * <p>
 * The bucket-key value asserted below ({@code 103} for sharding key {@code "PV10"}) is the same
 * precomputed constant used by {@link BucketKeyPersistorTest}: both tests use an identical
 * {@link BalancedDBShardingBundle}-based, 2-shard {@link BundleBasedTestBase} setup, and
 * {@link io.appform.dropwizard.sharding.sharding.impl.ConsistentHashBucketIdExtractor}'s bucket
 * id is a pure function of {@code (tenantId, shardingKey)} - it does not depend on the entity
 * type - so the same sharding key must always deterministically hash to the same bucket.
 */
class CopyFromParentIntegrationTest extends BundleBasedTestBase {

    private static final String PARENT_NAME = "PV10";
    private static final int PRECOMPUTED_BUCKET_KEY_FOR_PARENT_NAME = 103;

    @Override
    protected DBShardingBundleBase<TestConfig> getBundle() {
        return new BalancedDBShardingBundle<TestConfig>(
                CopyFromParentIntegrationParent.class, CopyFromParentIntegrationChild.class) {
            @Override
            protected ShardedHibernateFactory getConfig(TestConfig config) {
                return testConfig.getShards();
            }
        };
    }

    @SneakyThrows
    @Test
    void testCopyHappensBeforeBucketKeyCalculation_fullObserverChain() {
        val bundle = createBundle(true, false, false);

        val parentDao = bundle.createParentObjectDao(CopyFromParentIntegrationParent.class);
        val childDao = bundle.createRelatedObjectDao(CopyFromParentIntegrationChild.class);

        val parent = new CopyFromParentIntegrationParent();
        parent.setName(PARENT_NAME);
        parent.setTransactionRef("TXN-REF-1");

        // Deliberately leave parentRef/txnRef unset (default null) on the child - they must be
        // populated purely via @CopyFromParent, through the registered CopyFromParentObserver,
        // and bucketKey must be computed from the *copied* parentRef, not a stale/default one.
        parentDao.saveAndGetExecutor(parent)
                .save(childDao, p -> {
                    CopyFromParentIntegrationChild child = new CopyFromParentIntegrationChild();
                    child.setValue("child-value");
                    return child;
                })
                .execute();

        List<CopyFromParentIntegrationChild> children = childDao.select(
                PARENT_NAME,
                DetachedCriteria.forClass(CopyFromParentIntegrationChild.class)
                        .add(Property.forName(CopyFromParentIntegrationChild.Fields.parentRef).eq(PARENT_NAME)),
                0, 10);

        assertEquals(1, children.size());
        CopyFromParentIntegrationChild persistedChild = children.get(0);

        // Copy from parent must have happened.
        assertEquals(PARENT_NAME, persistedChild.getParentRef(), "sharding key must be copied from parent");
        assertEquals("TXN-REF-1", persistedChild.getTxnRef(), "field must be copied from parent");

        // Bucket key must have been computed from the *copied* (non-null) sharding key. If
        // BucketKeyPersistor's saver wrap had run before the copy (the bug this test guards
        // against), the sharding key field would still have been null at that point, which
        // would either NPE while hashing it, or persist a stale/default bucket key of 0.
        assertEquals(PRECOMPUTED_BUCKET_KEY_FOR_PARENT_NAME, persistedChild.getBucketKey(),
                "bucket key must be computed from the already-copied sharding key");
    }

    @SneakyThrows
    @Test
    void testCopyIfDefaultOnlyViolation_rollsBackEntireTransaction_fullObserverChain() {
        val bundle = createBundle(true, false, true);

        val parentDao = bundle.createParentObjectDao(CopyFromParentIntegrationParent.class);
        val childDao = bundle.createRelatedObjectDao(CopyFromParentIntegrationChild.class);

        val parent = new CopyFromParentIntegrationParent();
        parent.setName("PV11");
        parent.setTransactionRef("PARENT-TXN");

        val lockedContext = parentDao.saveAndGetExecutor(parent)
                .save(childDao, p -> {
                    CopyFromParentIntegrationChild child = new CopyFromParentIntegrationChild();
                    // Non-default value on a copyIfDefaultOnly-checked field -> violation.
                    child.setTxnRef("MANUAL-SETTER-VALUE");
                    child.setValue("child-value");
                    return child;
                });
        assertThrows(RuntimeException.class, lockedContext::execute);

        // The whole LockedContext transaction (parent save + child save) must be rolled back -
        // neither the parent nor the child should be persisted.
        assertTrue(parentDao.get("PV11").isEmpty(), "parent save must have been rolled back");
        List<CopyFromParentIntegrationChild> children = childDao.select(
                "PV11",
                DetachedCriteria.forClass(CopyFromParentIntegrationChild.class)
                        .add(Property.forName(CopyFromParentIntegrationChild.Fields.parentRef).eq("PV11")),
                0, 10);
        assertEquals(0, children.size(), "child save must have been rolled back");
    }

    private DBShardingBundleBase<TestConfig> createBundle(boolean copyEnabled,
                                                           boolean mismatchDetectionEnabled,
                                                           boolean copyIfDefaultOnly) {
        val bundle = getBundle();
        bundle.registerObserver(CopyFromParentObserver.builder()
                .copyEnabled(copyEnabled)
                .mismatchDetectionEnabled(mismatchDetectionEnabled)
                .copyIfDefaultOnly(copyIfDefaultOnly)
                .build());
        bundle.initialize(bootstrap);
        bundle.run(testConfig, environment);
        return bundle;
    }
}


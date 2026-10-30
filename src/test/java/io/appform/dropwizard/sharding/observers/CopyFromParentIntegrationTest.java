package io.appform.dropwizard.sharding.observers;

import io.appform.dropwizard.sharding.BalancedDBShardingBundle;
import io.appform.dropwizard.sharding.BundleBasedTestBase;
import io.appform.dropwizard.sharding.DBShardingBundleBase;
import io.appform.dropwizard.sharding.config.ShardedHibernateFactory;
import io.appform.dropwizard.sharding.dao.RelationalDao;
import io.appform.dropwizard.sharding.dao.operations.LegacyOpContextVisitor;
import io.appform.dropwizard.sharding.dao.operations.Save;
import io.appform.dropwizard.sharding.dao.operations.SaveWithParent;
import io.appform.dropwizard.sharding.execution.TransactionExecutionContext;
import io.appform.dropwizard.sharding.observers.entity.CopyFromParentIntegrationChild;
import io.appform.dropwizard.sharding.observers.entity.CopyFromParentIntegrationParent;
import lombok.SneakyThrows;
import lombok.val;
import org.hibernate.criterion.DetachedCriteria;
import org.hibernate.criterion.Property;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.stream.Collectors;

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
        val ex = assertThrows(RuntimeException.class, lockedContext::execute);
        assertTrue(rootCauseMessage(ex).startsWith("COPY_IF_DEFAULT_VIOLATION"), rootCauseMessage(ex));

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

    @SneakyThrows
    @Test
    void testHandlerSave_copiesFromParent_andRunsHandler_fullObserverChain() {
        // Exercises the public LockedContext#save(dao, entity, handler) entry point, which internally routes
        // through the package-private RelationalDao#save(context, entity, handler, parent).
        val bundle = createBundle(true, false, false);

        val parentDao = bundle.createParentObjectDao(CopyFromParentIntegrationParent.class);
        val childDao = bundle.createRelatedObjectDao(CopyFromParentIntegrationChild.class);

        val parent = new CopyFromParentIntegrationParent();
        parent.setName(PARENT_NAME);
        parent.setTransactionRef("TXN-REF-H");

        val child = new CopyFromParentIntegrationChild();
        child.setValue("child-value");

        val handlerSawCopiedValues = new AtomicBoolean(false);
        parentDao.saveAndGetExecutor(parent)
                .save(childDao, child, saved -> {
                    handlerSawCopiedValues.set(PARENT_NAME.equals(saved.getParentRef())
                            && "TXN-REF-H".equals(saved.getTxnRef()));
                    return saved;
                })
                .execute();

        assertTrue(handlerSawCopiedValues.get(), "handler must run after copy, on the saved entity");

        List<CopyFromParentIntegrationChild> children = childDao.select(
                PARENT_NAME,
                DetachedCriteria.forClass(CopyFromParentIntegrationChild.class)
                        .add(Property.forName(CopyFromParentIntegrationChild.Fields.parentRef).eq(PARENT_NAME)),
                0, 10);
        assertEquals(1, children.size());
        assertEquals("TXN-REF-H", children.get(0).getTxnRef());
        assertEquals(PRECOMPUTED_BUCKET_KEY_FOR_PARENT_NAME, children.get(0).getBucketKey());
    }

    /**
     * Backward compatibility, end to end: a registered observer whose visitor predates SaveWithParent (only implements
     * visit(Save), like an external lease interceptor) must keep intercepting LockedContext child saves - through
     * visit(Save), with the very same op instance - and its saver wrapping must take effect, alongside
     * CopyFromParentObserver and the built-in BucketKeyPersistor.
     */
    @SneakyThrows
    @Test
    void testLegacyVisitorObserver_interceptsLockedContextChildSave_viaVisitSave() {
        val visitedSaves = new ArrayList<Save<?, ?>>();
        val parentsSeenViaInstanceof = new ArrayList<Object>();
        val wrappedSaverInvocations = new AtomicInteger();
        val legacyVisitor = new LegacyOpContextVisitor<Void>() {
            @Override
            public <T, R> Void visit(Save<T, R> opContext) {
                visitedSaves.add(opContext);
                if (opContext instanceof SaveWithParent) {
                    parentsSeenViaInstanceof.add(((SaveWithParent<?, ?, ?>) opContext).getParent());
                }
                val oldSaver = opContext.getSaver();
                opContext.setSaver(e -> {
                    wrappedSaverInvocations.incrementAndGet();
                    return oldSaver.apply(e);
                });
                return null;
            }
        };
        val bundle = getBundle();
        bundle.registerObserver(new TransactionObserver(null) {
            @Override
            public <T> T execute(TransactionExecutionContext context, Supplier<T> supplier) {
                context.getOpContext().visit(legacyVisitor);
                return proceed(context, supplier);
            }
        });
        bundle.registerObserver(CopyFromParentObserver.builder().build());
        bundle.initialize(bootstrap);
        bundle.run(testConfig, environment);

        val parentDao = bundle.createParentObjectDao(CopyFromParentIntegrationParent.class);
        val childDao = bundle.createRelatedObjectDao(CopyFromParentIntegrationChild.class);
        val parent = new CopyFromParentIntegrationParent();
        parent.setName(PARENT_NAME);
        parent.setTransactionRef("TXN-REF-L");

        parentDao.saveAndGetExecutor(parent)
                .save(childDao, p -> {
                    val child = new CopyFromParentIntegrationChild();
                    child.setValue("child-value");
                    return child;
                })
                .execute();

        val childSaves = visitedSaves.stream().filter(SaveWithParent.class::isInstance).collect(Collectors.toList());
        assertEquals(1, childSaves.size(), "the LockedContext child save must reach visit(Save) as SaveWithParent");
        assertEquals(1, parentsSeenViaInstanceof.size());
        assertTrue(parentsSeenViaInstanceof.get(0) instanceof CopyFromParentIntegrationParent);
        assertEquals(1, wrappedSaverInvocations.get(),
                "the legacy visitor's saver wrap must run exactly once, for the child save only");

        val children = childDao.select(PARENT_NAME,
                DetachedCriteria.forClass(CopyFromParentIntegrationChild.class)
                        .add(Property.forName(CopyFromParentIntegrationChild.Fields.parentRef).eq(PARENT_NAME)),
                0, 10);
        assertEquals(1, children.size());
        assertEquals("TXN-REF-L", children.get(0).getTxnRef());
        assertEquals(PRECOMPUTED_BUCKET_KEY_FOR_PARENT_NAME, children.get(0).getBucketKey());
    }

    /**
     * Dynamic config, end to end: flipping a flag in the config source takes effect on the next LockedContext save
     * without restarting.
     */
    @SneakyThrows
    @Test
    void testDynamicConfigChange_takesEffectOnNextTransaction_fullObserverChain() {
        val copyIfDefaultOnly = new AtomicBoolean(false);
        val bundle = getBundle();
        bundle.registerObserver(CopyFromParentObserver.builder()
                .configSupplier(() -> new CopyFromParentObserver.CopyFromParentObserverConfig() {
                    @Override
                    public Boolean getCopyEnabled() {
                        return true;
                    }

                    @Override
                    public Boolean getMismatchDetectionEnabled() {
                        return null;
                    }

                    @Override
                    public Boolean getCopyIfDefaultOnly() {
                        return copyIfDefaultOnly.get();
                    }
                })
                .build());
        bundle.initialize(bootstrap);
        bundle.run(testConfig, environment);

        val parentDao = bundle.createParentObjectDao(CopyFromParentIntegrationParent.class);
        val childDao = bundle.createRelatedObjectDao(CopyFromParentIntegrationChild.class);

        // copyIfDefaultOnly=false: a manually set (non-default) value is overwritten from the parent
        val parent1 = new CopyFromParentIntegrationParent();
        parent1.setName("PV20");
        parent1.setTransactionRef("PARENT-TXN-1");
        parentDao.saveAndGetExecutor(parent1)
                .save(childDao, p -> {
                    val child = new CopyFromParentIntegrationChild();
                    child.setTxnRef("MANUAL");
                    return child;
                })
                .execute();
        assertEquals("PARENT-TXN-1", childrenOf(childDao, "PV20").get(0).getTxnRef());

        // flip at runtime -> the same kind of save now fails and is rolled back
        copyIfDefaultOnly.set(true);
        val parent2 = new CopyFromParentIntegrationParent();
        parent2.setName("PV21");
        parent2.setTransactionRef("PARENT-TXN-2");
        val lockedContext = parentDao.saveAndGetExecutor(parent2)
                .save(childDao, p -> {
                    val child = new CopyFromParentIntegrationChild();
                    child.setTxnRef("MANUAL");
                    return child;
                });
        val ex = assertThrows(RuntimeException.class, lockedContext::execute);
        assertTrue(rootCauseMessage(ex).startsWith("COPY_IF_DEFAULT_VIOLATION"),
                "must fail because of the runtime-enabled copyIfDefaultOnly check, got: " + rootCauseMessage(ex));
        assertTrue(parentDao.get("PV21").isEmpty(), "parent save must have been rolled back");
        assertTrue(childrenOf(childDao, "PV21").isEmpty(), "child save must have been rolled back");
    }

    private static String rootCauseMessage(Throwable t) {
        Throwable current = t;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return String.valueOf(current.getMessage());
    }

    @SneakyThrows
    private static List<CopyFromParentIntegrationChild> childrenOf(RelationalDao<CopyFromParentIntegrationChild> dao,
                                                                   String parentName) {
        return dao.select(parentName,
                DetachedCriteria.forClass(CopyFromParentIntegrationChild.class)
                        .add(Property.forName(CopyFromParentIntegrationChild.Fields.parentRef).eq(parentName)),
                0, 10);
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


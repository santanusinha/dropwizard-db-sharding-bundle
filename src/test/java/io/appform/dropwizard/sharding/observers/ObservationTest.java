package io.appform.dropwizard.sharding.observers;

import io.appform.dropwizard.sharding.BundleBasedTestBase;
import io.appform.dropwizard.sharding.DBShardingBundle;
import io.appform.dropwizard.sharding.DBShardingBundleBase;
import io.appform.dropwizard.sharding.config.ShardedHibernateFactory;
import io.appform.dropwizard.sharding.execution.TransactionExecutionContext;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import lombok.val;
import org.hibernate.criterion.DetachedCriteria;
import org.hibernate.criterion.Property;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;


/**
 *
 */
@Slf4j
public class ObservationTest extends BundleBasedTestBase {

    private static final class CountingObserver extends TransactionObserver {
        private final AtomicInteger callCounter = new AtomicInteger();

        public CountingObserver() {
            super(null);
        }


        @Override
        public <T> T execute(TransactionExecutionContext context, Supplier<T> supplier) {
            try {
                return proceed(context, supplier);
            } finally {
                log.info("Incrementing counter for {}.{}. New Value: {}",
                        context.getDaoClass().getSimpleName(),
                        context.getOpType(),
                        callCounter.incrementAndGet());
            }
        }
    }

    private final CountingObserver co = new CountingObserver();

    @BeforeEach
    public void resetCounter() {
        co.callCounter.set(0);
    }

    @Override
    protected DBShardingBundleBase<TestConfig> getBundle() {
        val bundle = new DBShardingBundle<TestConfig>(SimpleParent.class, SimpleChild.class) {
            @Override
            protected ShardedHibernateFactory getConfig(TestConfig config) {
                return testConfig.getShards();
            }
        };
        bundle.registerObserver(co);
        return bundle;
    }

    @Test
    @SneakyThrows
    public void testObserverInvocationForBasicOps() {
        val bundle = createBundle();

        val parentDao = bundle.createParentObjectDao(SimpleParent.class);
        val childDao = bundle.createRelatedObjectDao(SimpleChild.class);

        val parent = parentDao.save(new SimpleParent()
                        .setName("P1"))
                .orElse(null);
        assertNotNull(parent);
        val child = childDao.save(parent.getName(),
                        new SimpleChild()
                                .setParent(parent.getName())
                                .setValue("CV1"))
                .orElse(null);
        assertNotNull(child);
        assertEquals(2, co.callCounter.get());
    }

    @Test
    @SneakyThrows
    public void testObserverInvocationForLockedContext() {
        val bundle = createBundle();

        val parentDao = bundle.createParentObjectDao(SimpleParent.class);
        val childDao = bundle.createRelatedObjectDao(SimpleChild.class);

        val parent = parentDao.saveAndGetExecutor(new SimpleParent()
                        .setName("P1"))
                .save(childDao, parentObj -> new SimpleChild()
                        .setParent(parentObj.getName())
                        .setValue("CV1"))
                .execute();
        assertNotNull(parent);
        assertEquals(2, co.callCounter.get());
        assertEquals(1, childDao.select(parent.getName(),
                        DetachedCriteria.forClass(SimpleChild.class)
                                .add(Property.forName(SimpleChild.Fields.parent)
                                        .eq(parent.getName())),
                        0,
                        Integer.MAX_VALUE)
                .size());
        assertEquals(3, co.callCounter.get());
    }

    @Test
    @SneakyThrows
    public void testObserverInvocationForReadContext() {
        val bundle = createBundle();

        val parentDao = bundle.createParentObjectDao(SimpleParent.class);
        val childDao = bundle.createRelatedObjectDao(SimpleChild.class);

        val parent = parentDao.saveAndGetExecutor(new SimpleParent()
                        .setName("P1"))
                .save(childDao, parentObj -> new SimpleChild()
                        .setParent(parentObj.getName())
                        .setValue("CV1"))
                .execute();
        assertNotNull(parent);
        assertEquals(2, co.callCounter.get());
        val augmentedParent = parentDao.readOnlyExecutor(parent.getName())
                .readAugmentParent(childDao, DetachedCriteria.forClass(SimpleChild.class)
                                .add(Property.forName(SimpleChild.Fields.parent)
                                        .eq(parent.getName())),
                        0,
                        Integer.MAX_VALUE,
                        (parentObject, children) -> parentObject.getChildren().addAll(children))
                .execute()
                .orElse(null);
        assertNotNull(augmentedParent);
        assertEquals(1, augmentedParent.getChildren().size());
        assertEquals(4, co.callCounter.get());
    }

    private DBShardingBundleBase<TestConfig> createBundle() {
        val bundle = getBundle();
        bundle.initialize(bootstrap);
        bundle.initBundles(bootstrap);
        bundle.runBundles(testConfig, environment);
        bundle.run(testConfig, environment);
        return bundle;
    }
}

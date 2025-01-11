package io.appform.dropwizard.sharding.observers;

import io.appform.dropwizard.sharding.BundleBasedTestBase;
import io.appform.dropwizard.sharding.DBShardingBundle;
import io.appform.dropwizard.sharding.DBShardingBundleBase;
import io.appform.dropwizard.sharding.config.ShardedHibernateFactory;
import io.appform.dropwizard.sharding.execution.TransactionExecutionContext;
import io.appform.dropwizard.sharding.listeners.TransactionListener;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import lombok.val;
import org.hibernate.criterion.DetachedCriteria;
import org.hibernate.criterion.Property;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;


/**
 *
 */
@Slf4j
public class ErrorListenerTest extends BundleBasedTestBase {
    private static final class CountingListener implements TransactionListener {
        private final AtomicInteger preCounter = new AtomicInteger();
        private final AtomicInteger postCounter = new AtomicInteger();
        private final AtomicInteger errorCounter = new AtomicInteger();

        @Override
        public void beforeExecute(TransactionExecutionContext listenerContext) {
            preCounter.incrementAndGet();
        }

        @Override
        public void afterExecute(TransactionExecutionContext listenerContext) {
            postCounter.incrementAndGet();
        }

        @Override
        public void afterException(TransactionExecutionContext listenerContext, Throwable e) {
            log.error("Error in function: {}.{}",
                    listenerContext.getEntityClass().getSimpleName(),
                    listenerContext.getCommandName());
            errorCounter.incrementAndGet();
        }
    }

    private static final class ErrorThrowingListener implements TransactionListener {
        private final int where;

        private ErrorThrowingListener(int where) {
            this.where = where;
        }

        @Override
        public void beforeExecute(TransactionExecutionContext listenerContext) {
            if (0 == where) {
                throw new RuntimeException("Before Execute Test Error");
            }
        }

        @Override
        public void afterExecute(TransactionExecutionContext listenerContext) {
            if (1 == where) {
                throw new RuntimeException("After Execute Test Error");
            }
        }

        @Override
        public void afterException(TransactionExecutionContext listenerContext, Throwable e) {
            if (2 == where) {
                throw new RuntimeException("Error Execute Test Error");
            }
        }
    }

    private final CountingListener cl = new CountingListener();

    @Override
    protected DBShardingBundleBase<TestConfig> getBundle() {
        val bundle = new DBShardingBundle<TestConfig>(SimpleParent.class, SimpleChild.class) {
            @Override
            protected ShardedHibernateFactory getConfig(TestConfig config) {
                return testConfig.getShards();
            }
        };
        bundle.registerListener(new ErrorThrowingListener(0));
        bundle.registerListener(new ErrorThrowingListener(1));
        bundle.registerListener(new ErrorThrowingListener(2));
        bundle.registerListener(cl);
        return bundle;
    }

    @BeforeEach
    public void reset() {
        cl.preCounter.set(0);
        cl.postCounter.set(0);
        cl.errorCounter.set(0);
    }

    @Test
    @SneakyThrows
    public void testFilterErrorCounter() {
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
        assertThrows(Exception.class,
                () -> parentDao.lockAndGetExecutor(parent.getName())
                        .update(childDao,
                                DetachedCriteria.forClass(SimpleChild.class)
                                        .add(Property.forName(SimpleChild.Fields.parent).eq(parent.getName())),
                                child -> {
                                    throw new RuntimeException("Test error");
                                })
                        .execute());
        assertEquals(2, cl.errorCounter.get());
        assertEquals(4, cl.preCounter.get());
        assertEquals(2, cl.postCounter.get());
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

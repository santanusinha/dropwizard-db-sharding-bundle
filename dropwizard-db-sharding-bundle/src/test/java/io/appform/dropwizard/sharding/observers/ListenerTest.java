package io.appform.dropwizard.sharding.observers;

import io.appform.dropwizard.sharding.BundleBasedTestBase;
import io.appform.dropwizard.sharding.DBShardingBundle;
import io.appform.dropwizard.sharding.DBShardingBundleBase;
import io.appform.dropwizard.sharding.config.ShardedHibernateFactory;
import io.appform.dropwizard.sharding.execution.TransactionExecutionContext;
import io.appform.dropwizard.sharding.listeners.TransactionListener;
import io.appform.dropwizard.sharding.observers.entity.SimpleChild;
import io.appform.dropwizard.sharding.observers.entity.SimpleParent;
import lombok.SneakyThrows;
import lombok.val;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 *
 */
public class ListenerTest extends BundleBasedTestBase {
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
            errorCounter.incrementAndGet();
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
        assertEquals(2, cl.preCounter.get());
        assertEquals(2, cl.postCounter.get());
    }

    private DBShardingBundleBase<TestConfig> createBundle() {
        val bundle = getBundle();
        bundle.initialize(bootstrap);
        bundle.run(testConfig, environment);
        return bundle;
    }
}

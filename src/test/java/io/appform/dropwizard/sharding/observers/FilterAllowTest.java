package io.appform.dropwizard.sharding.observers;

import io.appform.dropwizard.sharding.BundleBasedTestBase;
import io.appform.dropwizard.sharding.DBShardingBundle;
import io.appform.dropwizard.sharding.DBShardingBundleBase;
import io.appform.dropwizard.sharding.config.ShardedHibernateFactory;
import io.appform.dropwizard.sharding.execution.TransactionExecutionContext;
import io.appform.dropwizard.sharding.filters.TransactionFilter;
import io.appform.dropwizard.sharding.filters.TransactionFilterResult;
import io.appform.dropwizard.sharding.observers.entity.SimpleChild;
import io.appform.dropwizard.sharding.observers.entity.SimpleParent;
import lombok.SneakyThrows;
import lombok.val;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 *
 */
public class FilterAllowTest extends BundleBasedTestBase {
    private static final class AllowingFilter implements TransactionFilter {

        @Override
        public TransactionFilterResult evaluate(TransactionExecutionContext context) {
            return TransactionFilterResult.allow();
        }
    }


    @Override
    protected DBShardingBundleBase<TestConfig> getBundle() {
        val bundle = new DBShardingBundle<TestConfig>(SimpleParent.class, SimpleChild.class) {
            @Override
            protected ShardedHibernateFactory getConfig(TestConfig config) {
                return testConfig.getShards();
            }
        };
        bundle.registerFilter(new AllowingFilter());
        return bundle;
    }

    @Test
    @SneakyThrows
    public void testObserverInvocationForBasicOps() {
        val bundle = createBundle();

        val parentDao = bundle.createParentObjectDao(SimpleParent.class);
        val childDao = bundle.createRelatedObjectDao(SimpleChild.class);

        assertNotNull(parentDao.save(new SimpleParent().setName("P1")).orElse(null));
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

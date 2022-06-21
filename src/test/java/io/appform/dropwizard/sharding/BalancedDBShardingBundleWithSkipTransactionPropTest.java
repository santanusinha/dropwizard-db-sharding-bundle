package io.appform.dropwizard.sharding;

import io.appform.dropwizard.sharding.config.ShardedHibernateFactory;
import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class BalancedDBShardingBundleWithSkipTransactionPropTest extends DBShardingBundleTestBase {

    @Override
    protected DBShardingBundleBase<TestConfig> getBundle() {
        return new BalancedDBShardingBundle<TestConfig>("io.appform.dropwizard.sharding.dao.testdata.entities") {
            @Override
            protected ShardedHibernateFactory getConfig(TestConfig config) {
                return testConfig.getShards();
            }
        };
    }

    @After
    public void after() {
        System.clearProperty("db.ro.skipTxn");
    }

    @Test
    public void testBundleWithRoSkipTxnPropertySet() {
        System.setProperty("db.ro.skipTxn", "true");
        DBShardingBundleBase<TestConfig> bundle = getBundle();
        bundle.initialize(bootstrap);
        bundle.initBundles(bootstrap);
        bundle.runBundles(testConfig, environment);
        bundle.run(testConfig, environment);

        assertTrue(bundle.isRoSkipTransaction());
    }

    @Test
    public void testBundleWithRoSkipTxnPropertySetToEmpty() {
        System.setProperty("db.ro.skipTxn", "");
        DBShardingBundleBase<TestConfig> bundle = getBundle();
        bundle.initialize(bootstrap);
        bundle.initBundles(bootstrap);
        bundle.runBundles(testConfig, environment);
        bundle.run(testConfig, environment);

        assertTrue(bundle.isRoSkipTransaction());
    }

    @Test
    public void testBundleWithRoSkipTxnPropertyNotSet() {
        DBShardingBundleBase<TestConfig> bundle = getBundle();
        bundle.initialize(bootstrap);
        bundle.initBundles(bootstrap);
        bundle.runBundles(testConfig, environment);
        bundle.run(testConfig, environment);

        assertFalse(bundle.isRoSkipTransaction());
    }
}

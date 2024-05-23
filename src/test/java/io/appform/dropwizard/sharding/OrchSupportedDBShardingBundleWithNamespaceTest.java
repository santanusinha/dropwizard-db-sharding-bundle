package io.appform.dropwizard.sharding;

import io.appform.dropwizard.sharding.config.ShardedHibernateFactory;
import io.appform.dropwizard.sharding.dao.testdata.entities.Order;
import io.appform.dropwizard.sharding.dao.testdata.entities.OrderItem;

public class OrchSupportedDBShardingBundleWithNamespaceTest  extends DBShardingBundleTestBase{
    @Override
    protected DBShardingBundleBase<TestConfig> getBundle() {
        return new OrchSupportedDBShardingBundle<TestConfig>("namespace", Order.class, OrderItem.class) {
            @Override
            protected ShardedHibernateFactory getConfig(TestConfig config) {
                return testConfig.getShards();
            }
        };
    }
}

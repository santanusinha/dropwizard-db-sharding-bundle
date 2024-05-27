package io.appform.dropwizard.sharding;

import io.appform.dropwizard.sharding.config.ShardedHibernateFactory;
import io.appform.dropwizard.sharding.dao.RelationalDao;
import io.appform.dropwizard.sharding.dao.WrapperDao;
import io.appform.dropwizard.sharding.dao.testdata.OrderDao;
import io.appform.dropwizard.sharding.dao.testdata.entities.Order;
import io.appform.dropwizard.sharding.dao.testdata.entities.OrderItem;
import io.appform.dropwizard.sharding.observers.bucket.BucketIdObserver;
import io.appform.dropwizard.sharding.observers.bucket.BucketIdSaver;
import io.appform.dropwizard.sharding.sharding.OrchSupportedShardManager;
import io.appform.dropwizard.sharding.sharding.impl.ConsistentHashBucketIdExtractor;
import io.appform.dropwizard.sharding.utils.BucketCalculator;
import lombok.val;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OrchSupportedDBShardingBundleWithNamespaceTest  extends BundleBasedTestBase{
    @Override
    protected DBShardingBundleBase<TestConfig> getBundle() {
        return new OrchSupportedDBShardingBundle<TestConfig>("namespace", Order.class, OrderItem.class) {
            @Override
            protected ShardedHibernateFactory getConfig(TestConfig config) {
                return testConfig.getShards();
            }
        };
    }

    @Test
    void testBundle() throws Exception {
        DBShardingBundleBase<TestConfig> bundle = getBundle();
        bundle.initialize(bootstrap);
        bundle.initBundles(bootstrap);
        bundle.runBundles(testConfig, environment);
        bundle.run(testConfig, environment);
        val bucketCalculator = bucketCalculator();
        val bucketIdSaver = new BucketIdSaver(bucketCalculator);
        val bucketIdObserver = new BucketIdObserver(bucketIdSaver);
        bucketIdObserver.init(bucketIdSaver);
        bundle.registerObserver(bucketIdObserver);
        WrapperDao<Order, OrderDao> dao = bundle.createWrapperDao(OrderDao.class);

        RelationalDao<Order> rDao = bundle.createRelatedObjectDao(Order.class);

        RelationalDao<OrderItem> orderItemDao = bundle.createRelatedObjectDao(OrderItem.class);


        final String customer = "customer1";

        Order order = Order.builder()
                .customerId(customer)
                .orderId("OD00001")
                .amount(100)
                .build();
        Order saveResult = dao.forParent(customer).save(order);

        long saveId = saveResult.getId();

        Order result = dao.forParent(customer).get(saveId);

        assertEquals(saveResult.getId(), result.getId());
        assertEquals(saveResult.getBucketId(), result.getBucketId());
        assertEquals(saveResult.getId(), result.getId());
    }
    public BucketCalculator<String> bucketCalculator() {
        return new BucketCalculator<>(new ConsistentHashBucketIdExtractor<>(new OrchSupportedShardManager(1024)));
    }
}

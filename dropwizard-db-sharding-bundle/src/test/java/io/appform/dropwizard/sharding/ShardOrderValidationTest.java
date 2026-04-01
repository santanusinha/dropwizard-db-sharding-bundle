package io.appform.dropwizard.sharding;

import io.appform.dropwizard.sharding.config.MultiTenantShardedHibernateFactory;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ShardOrderValidationTest extends  MultiTenantDBShardingBundleTestBase {

    @Override
    protected MultiTenantDBShardingBundleBase<TestConfig> getBundle() {
        return new MultiTenantBalancedDBShardingBundle<TestConfig>("io.appform.dropwizard.sharding.dao.testdata.entities", "io.appform.dropwizard.sharding.dao.testdata.multi") {
            @Override
            protected MultiTenantShardedHibernateFactory getConfig(TestConfig config) {
                return testConfig.getShards();
            }
        };
    }

    @Test
    public void testShardOrderingIsPreserved() throws Exception {
        final int shardCount = 1024;
        final var shards = IntStream.range(0, shardCount)
                .mapToObj(i -> createConfig("tenant1_" + i))
                .collect(Collectors.toList());

        testConfig.getShards().getTenants().get("TENANT1").setShards(shards);

        MultiTenantDBShardingBundleBase<TestConfig> bundle = getBundle();
        bundle.initialize(bootstrap);
        bundle.run(testConfig, environment);

        List<SessionFactory> sessionFactories = bundle.getSessionFactories().get("TENANT1");
        assertEquals(shardCount, sessionFactories.size());

        IntStream.range(0, shardCount).forEach(i -> {
            SessionFactory sessionFactory = sessionFactories.get(i);
            try (Session session = sessionFactory.openSession()) {
                String jdbcUrl = session.doReturningWork(connection -> connection.getMetaData().getURL());
                String expected = "tenant1_" + i;
                assertTrue(jdbcUrl.contains(expected),
                        String.format("Shard %d expected to contain '%s' but got URL: %s", i, expected, jdbcUrl));
            }
        });
    }
}

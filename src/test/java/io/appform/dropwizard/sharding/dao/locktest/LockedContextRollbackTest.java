package io.appform.dropwizard.sharding.dao.locktest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.appform.dropwizard.sharding.BundleBasedTestBase;
import io.appform.dropwizard.sharding.DBShardingBundle;
import io.appform.dropwizard.sharding.DBShardingBundleBase;
import io.appform.dropwizard.sharding.ShardInfoProvider;
import io.appform.dropwizard.sharding.config.ShardedHibernateFactory;
import io.appform.dropwizard.sharding.dao.LookupDao;
import io.appform.dropwizard.sharding.dao.RelationalDao;
import io.appform.dropwizard.sharding.healthcheck.HealthCheckManager;
import io.appform.dropwizard.sharding.hibernate.SessionFactoryFactory;
import io.appform.dropwizard.sharding.sharding.InMemoryLocalShardBlacklistingStore;
import io.appform.dropwizard.sharding.sharding.ShardBlacklistingStore;
import io.dropwizard.db.PooledDataSourceFactory;
import lombok.val;
import org.hibernate.criterion.DetachedCriteria;
import org.junit.jupiter.api.Test;

import java.util.List;

public class LockedContextRollbackTest extends BundleBasedTestBase {

    @Override
    protected DBShardingBundleBase<TestConfig> getBundle() {
        return new DBShardingBundle<TestConfig>(SomeLookupObject.class, SomeOtherObject.class) {
            @Override
            protected ShardedHibernateFactory getConfig(TestConfig config) {
                return testConfig.getShards();
            }

            @Override
            protected ShardBlacklistingStore getBlacklistingStore() {
                return new InMemoryLocalShardBlacklistingStore();
            }
        };
    }

    private DBShardingBundleBase<TestConfig> getBundleWithAutoCommitEnabled() {
        return new DBShardingBundle<TestConfig>(SomeLookupObject.class, SomeOtherObject.class) {
            @Override
            protected ShardedHibernateFactory getConfig(TestConfig config) {
                return testConfig.getShards();
            }

            @Override
            protected ShardBlacklistingStore getBlacklistingStore() {
                return new InMemoryLocalShardBlacklistingStore();
            }

            @Override
            protected SessionFactoryFactory<TestConfig> createSessionFactoryFactory(
                    List<Class<?>> entities,
                    HealthCheckManager healthCheckManager,
                    ShardInfoProvider shardInfoProvider,
                    int shard,
                    ShardedHibernateFactory shardConfig) {
                return new SessionFactoryFactory<TestConfig>(entities, healthCheckManager) {
                    @Override
                    protected String name() {
                        return shardInfoProvider.shardName(shard);
                    }

                    @Override
                    public PooledDataSourceFactory getDataSourceFactory(TestConfig t) {
                        return shardConfig.getShards().get(shard);
                    }

                    @Override
                    protected void disableAutoCommit(PooledDataSourceFactory dbConfig) {
                        // no-op: simulate the broken pre-fix behavior
                    }
                };
            }
        };
    }

    @Test
    public void testRollbackOnFailedRelationalSave() throws Exception {
        DBShardingBundleBase<TestConfig> bundle = getBundle();
        bundle.initialize(bootstrap);
        bundle.run(testConfig, environment);

        LookupDao<SomeLookupObject> lookupDao = bundle.createParentObjectDao(SomeLookupObject.class);
        RelationalDao<SomeOtherObject> relationDao = bundle.createRelatedObjectDao(SomeOtherObject.class);

        SomeLookupObject parent = SomeLookupObject.builder()
                .myId("rollback-test-1")
                .name("Parent")
                .build();

        assertThrows(RuntimeException.class, () ->
                lookupDao.saveAndGetExecutor(parent)
                        .save(relationDao, p -> SomeOtherObject.builder()
                                .myId(p.getMyId())
                                .value("child-1")
                                .build())
                        .save(relationDao, p -> SomeOtherObject.builder()
                                .myId(p.getMyId())
                                .value("child-2")
                                .build())
                        .save(relationDao, p -> {
                            throw new RuntimeException("simulated failure on third save");
                        })
                        .execute()
        );

        assertTrue(lookupDao.get("rollback-test-1").isEmpty());
        List<SomeOtherObject> children = relationDao.select(
                "rollback-test-1", DetachedCriteria.forClass(SomeOtherObject.class), 0, 10);
        assertEquals(0, children.size());
    }

    @Test
    public void testSuccessfulCommitWithMultipleRelationalSaves() throws Exception {
        DBShardingBundleBase<TestConfig> bundle = getBundle();
        bundle.initialize(bootstrap);
        bundle.run(testConfig, environment);

        LookupDao<SomeLookupObject> lookupDao = bundle.createParentObjectDao(SomeLookupObject.class);
        RelationalDao<SomeOtherObject> relationDao = bundle.createRelatedObjectDao(SomeOtherObject.class);

        SomeLookupObject parent = SomeLookupObject.builder()
                .myId("commit-test-1")
                .name("Parent")
                .build();

        lookupDao.saveAndGetExecutor(parent)
                .save(relationDao, p -> SomeOtherObject.builder()
                        .myId(p.getMyId())
                        .value("child-1")
                        .build())
                .save(relationDao, p -> SomeOtherObject.builder()
                        .myId(p.getMyId())
                        .value("child-2")
                        .build())
                .save(relationDao, p -> SomeOtherObject.builder()
                        .myId(p.getMyId())
                        .value("child-3")
                        .build())
                .execute();

        assertTrue(lookupDao.get("commit-test-1").isPresent());
        List<SomeOtherObject> children = relationDao.select(
                "commit-test-1", DetachedCriteria.forClass(SomeOtherObject.class), 0, 10);
        assertEquals(3, children.size());
    }

    @Test
    public void testRollbackFailsWhenAutoCommitIsTrue() throws Exception {
        DBShardingBundleBase<TestConfig> bundle = getBundleWithAutoCommitEnabled();
        bundle.initialize(bootstrap);
        bundle.run(testConfig, environment);

        LookupDao<SomeLookupObject> lookupDao = bundle.createParentObjectDao(SomeLookupObject.class);
        RelationalDao<SomeOtherObject> relationDao = bundle.createRelatedObjectDao(SomeOtherObject.class);

        SomeLookupObject parent = SomeLookupObject.builder()
                .myId("autocommit-broken-1")
                .name("Parent")
                .build();

        assertThrows(RuntimeException.class, () ->
                lookupDao.saveAndGetExecutor(parent)
                        .save(relationDao, p -> SomeOtherObject.builder()
                                .myId(p.getMyId())
                                .value("child-1")
                                .build())
                        .save(relationDao, p -> SomeOtherObject.builder()
                                .myId(p.getMyId())
                                .value("child-2")
                                .build())
                        .save(relationDao, p -> {
                            throw new RuntimeException("simulated failure on third save");
                        })
                        .execute()
        );

        assertTrue(lookupDao.get("autocommit-broken-1").isPresent(),
                "With autoCommit=true, parent leaks despite rollback");
        val children = relationDao.select(
                "autocommit-broken-1", DetachedCriteria.forClass(SomeOtherObject.class), 0, 10);
        assertTrue(children.size() > 0,
                "With autoCommit=true, children leak despite rollback");
    }
}

package io.appform.dropwizard.sharding.dao.locktest;

import io.appform.dropwizard.sharding.BundleBasedTestBase;
import io.appform.dropwizard.sharding.DBShardingBundle;
import io.appform.dropwizard.sharding.DBShardingBundleBase;
import io.appform.dropwizard.sharding.config.ShardedHibernateFactory;
import io.appform.dropwizard.sharding.dao.LookupDao;
import io.appform.dropwizard.sharding.dao.RelationalDao;
import io.appform.dropwizard.sharding.sharding.InMemoryLocalShardBlacklistingStore;
import io.appform.dropwizard.sharding.sharding.ShardBlacklistingStore;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
                "rollback-test-1", (queryRoot, query, criteriaBuilder) -> {

                }, 0, 10);
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
                "commit-test-1", (queryRoot, query, criteriaBuilder) -> {

                }, 0, 10);
        assertEquals(3, children.size());
    }
}

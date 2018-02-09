package io.dropwizard.sharding.dao.snapshot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.Lists;
import io.dropwizard.sharding.TestUtils;
import io.dropwizard.sharding.dao.RelationalDao;
import io.dropwizard.sharding.dao.SnapshottedLookupDao;
import io.dropwizard.sharding.sharding.ShardManager;
import io.dropwizard.sharding.sharding.impl.ConsistentHashBucketIdExtractor;
import lombok.val;
import org.hibernate.SessionFactory;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.Assert.assertEquals;

public class SnapshotDaoTest {

    private List<SessionFactory> sessionFactories = Lists.newArrayList();
    private SnapshottedLookupDao<SnapshotTestEntity, SnapshotEntityImpl> lookupDao;
    private RelationalDao<SnapshotEntityImpl> snapshotDao;

    @Before
    public void before() {
        for (int i = 0; i < 2; i++) {
            sessionFactories.add(TestUtils.buildSessionFactory(String.format("db_%d", i),
                    Lists.newArrayList(SnapshotEntityImpl.class, SnapshotTestEntity.class)));
        }
        final ShardManager shardManager = new ShardManager(sessionFactories.size());
        snapshotDao = new RelationalDao<>(sessionFactories, SnapshotEntityImpl.class, shardManager, new ConsistentHashBucketIdExtractor<>());
        lookupDao = new SnapshottedLookupDao<>(sessionFactories,
                SnapshotTestEntity.class,
                shardManager,
                new ConsistentHashBucketIdExtractor<>(),
                SnapshotEntityImpl.class,
                entity -> {
                    try {
                        return new ObjectMapper().writeValueAsBytes(entity);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                },
                snapshotDao,
                new SnapshotEntityFactory<SnapshotEntityImpl>() {
                    @Override
                    public SnapshotEntityImpl newInstance() {
                        return new SnapshotEntityImpl();
                    }
                });
    }

    @After
    public void after() {
        sessionFactories.forEach(SessionFactory::close);
    }


    @Test
    public void testSave() throws Exception {
        val id = UUID.randomUUID().toString();
        lookupDao.save(SnapshotTestEntity.builder()
                .id(id)
                .data("abcd")
                .build());
        List<SnapshotEntityImpl> snapshots = lookupDao.snapshots(id);
        assertEquals(1, snapshots.size());
    }

    @Test
    public void testUpdate() throws Exception {
        val id = UUID.randomUUID().toString();
        lookupDao.save(SnapshotTestEntity.builder()
                .id(id)
                .data("abcd")
                .build());
        lookupDao.update(id, x -> {
            x.get().setData("EFGH");
            return x.get();
        });
        List<SnapshotEntityImpl> snapshots = lookupDao.snapshots(id);
        assertEquals(2, snapshots.size());
    }

}

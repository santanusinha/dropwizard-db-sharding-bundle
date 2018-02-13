package io.dropwizard.sharding.dao;

import io.dropwizard.sharding.dao.snapshot.SnapshotEntity;
import io.dropwizard.sharding.sharding.BucketIdExtractor;
import io.dropwizard.sharding.sharding.ShardManager;
import io.dropwizard.sharding.utils.Transactions;
import org.hibernate.SessionFactory;
import org.hibernate.criterion.DetachedCriteria;
import org.hibernate.criterion.Restrictions;

import java.util.List;
import java.util.Optional;
import java.util.function.Function;

public class SnapshottedLookupDao<T, U extends SnapshotEntity> extends LookupDao<T> {

    private final Class<U> snapshotEntityClass;
    private final SnapshotProvider<T, U> snapshotProvider;
    private final RelationalDao<U> snapshotEntityRelationalDao;

    public SnapshottedLookupDao(List<SessionFactory> sessionFactories,
                                Class<T> entityClass,
                                ShardManager shardManager,
                                BucketIdExtractor<String> bucketIdExtractor,
                                Class<U> snapshotEntityClass,
                                SnapshotProvider<T, U> snapshotProvider,
                                RelationalDao<U> snapshotEntityRelationalDao) {
        super(sessionFactories, entityClass, shardManager, bucketIdExtractor);
        this.snapshotEntityClass = snapshotEntityClass;
        this.snapshotProvider = snapshotProvider;
        this.snapshotEntityRelationalDao = snapshotEntityRelationalDao;
    }

    @Override
    public Optional<T> save(T entity) throws Exception {
        final String key = key(entity);
        LockedContext<T> context = super.saveAndGetExecutor(entity);
        applySnapshot(context, key, entity);
        return Optional.ofNullable(context.execute());
    }

    @Override
    public <U> U save(T entity, Function<T, U> handler) throws Exception {
        final String key = key(entity);
        LockedContext<T> context = super.saveAndGetExecutor(entity);
        applySnapshot(context, key, entity);
        return handler.apply(entity);
    }

    @Override
    public boolean updateInLock(String id, Function<Optional<T>, T> updater) {
        LookupDaoPriv dao = dao(id);
        return updateImpl(shardId(id), id, dao::getLockedForWrite, updater, dao);
    }

    @Override
    public boolean update(String id, Function<Optional<T>, T> updater) {
        LookupDaoPriv dao = dao(id);
        return updateImpl(shardId(id), id, dao::get, updater, dao);
    }

    @Override
    public LockedContext<T> lockAndGetExecutor(String id) {
        throw new IllegalArgumentException("OperationNotSupportedOnSnapshottedDao");
    }

    @Override
    public LockedContext<T> saveAndGetExecutor(T entity) {
        throw new IllegalArgumentException("OperationNotSupportedOnSnapshottedDao");
    }

    public List<U> snapshots(String id, int start, int count) throws Exception {
        return snapshotEntityRelationalDao.select(id, DetachedCriteria.forClass(snapshotEntityClass)
                .add(Restrictions.eq("key", id)), start, count);
    }

    private boolean updateImpl(int shardId, String id,
                               Function<String, T> getter,
                               Function<Optional<T>, T> updater, LookupDaoPriv dao) {
        try {
            return Transactions.<T, String, Boolean>execute(dao.getSessionFactory(), true, getter, id, entity -> {
                T newEntity = updater.apply(Optional.ofNullable(entity));
                if (null == newEntity) {
                    return false;
                }
                dao.update(newEntity);
                snapshotEntityRelationalDao.save(shardId, dao.getSessionFactory(), snapshotEntity(id, entity));
                return true;
            });
        } catch (Exception e) {
            throw new RuntimeException("Error updating entity: " + id, e);
        }
    }

    private U snapshotEntity(String key, T entity) {
        U snapshotEntity = snapshotProvider.snapshot(entity);
        snapshotEntity.setKey(key);
        snapshotEntity.setVersion(System.currentTimeMillis());
        return snapshotEntity;
    }

    private void applySnapshot(LockedContext<T> lockedContext, String key, T entity) {
        U snapshotEntity = snapshotEntity(key, entity);
        if (snapshotEntity != null) {
            lockedContext.save(snapshotEntityRelationalDao, x -> snapshotEntity);
        }
    }

}


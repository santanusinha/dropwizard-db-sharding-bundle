package io.dropwizard.sharding.dao;

import io.dropwizard.sharding.dao.snapshot.SnapshotEntity;

public interface SnapshotProvider<T, U extends SnapshotEntity> {

    U snapshot(T entity);

}

package io.dropwizard.sharding.dao.snapshot;

public abstract class SnapshotEntityFactory<T> {

    public abstract T newInstance();

}

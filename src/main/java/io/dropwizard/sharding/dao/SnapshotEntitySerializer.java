package io.dropwizard.sharding.dao;

public interface SnapshotEntitySerializer<T> {

    byte[] serialize(T entity) throws RuntimeException;

}

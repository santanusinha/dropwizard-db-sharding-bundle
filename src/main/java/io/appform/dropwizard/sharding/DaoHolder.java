package io.appform.dropwizard.sharding;

public interface DaoHolder<T> {
    T get(int shard, final String key);

    T get(int shard);

    int size();
}

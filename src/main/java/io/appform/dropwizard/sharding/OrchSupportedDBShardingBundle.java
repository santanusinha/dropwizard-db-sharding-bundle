package io.appform.dropwizard.sharding;

import io.appform.dropwizard.sharding.observers.bucket.BucketIdObserver;
import io.appform.dropwizard.sharding.observers.bucket.BucketIdSaver;
import io.appform.dropwizard.sharding.sharding.OrchSupportedShardManager;
import io.appform.dropwizard.sharding.sharding.ShardBlacklistingStore;
import io.appform.dropwizard.sharding.sharding.ShardManager;
import io.dropwizard.Configuration;

import java.util.List;

public abstract class OrchSupportedDBShardingBundle<T extends Configuration> extends DBShardingBundleBase<T> {
    public OrchSupportedDBShardingBundle(
            String dbNamespace,
            Class<?> entity,
            Class<?>... entities) {
        super(dbNamespace, entity, entities);
        registerObserver(new BucketIdObserver(new BucketIdSaver()));
    }

    public OrchSupportedDBShardingBundle(String dbNamespace, List<String> classPathPrefixList) {
        super(dbNamespace, classPathPrefixList);
        registerObserver(new BucketIdObserver(new BucketIdSaver()));
    }

    public OrchSupportedDBShardingBundle(Class<?> entity, Class<?>... entities) {
        super(entity, entities);
        registerObserver(new BucketIdObserver(new BucketIdSaver()));
    }

    public OrchSupportedDBShardingBundle(String... classPathPrefixes) {
        super(classPathPrefixes);
        registerObserver(new BucketIdObserver(new BucketIdSaver()));
    }

    @Override
    protected ShardManager createShardManager(int numShards, ShardBlacklistingStore shardBlacklistingStore) {
        return new OrchSupportedShardManager(numShards, shardBlacklistingStore);
    }
}

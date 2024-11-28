package io.appform.dropwizard.sharding;

import io.appform.dropwizard.sharding.observers.bucket.BucketIdObserver;
import io.appform.dropwizard.sharding.observers.bucket.BucketIdSaver;
import io.appform.dropwizard.sharding.sharding.OrchSupportedShardManager;
import io.appform.dropwizard.sharding.sharding.ShardBlacklistingStore;
import io.appform.dropwizard.sharding.sharding.ShardManager;
import io.dropwizard.Configuration;
import lombok.extern.slf4j.Slf4j;
import lombok.val;

import java.util.List;

@Slf4j
public abstract class OrchSupportedDBShardingBundle<T extends Configuration> extends DBShardingBundleBase<T> {
    public OrchSupportedDBShardingBundle(
            String dbNamespace,
            Class<?> entity,
            Class<?>... entities) {
        super(dbNamespace, entity, entities);
        val bucketCalculator = bucketCalculator();
        val bucketIdSaver = new BucketIdSaver(bucketCalculator);
        val bucketIdObserver = new BucketIdObserver(bucketIdSaver);
        bucketIdObserver.init(bucketIdSaver);
        registerObserver(bucketIdObserver);
    }

    public OrchSupportedDBShardingBundle(String dbNamespace, List<String> classPathPrefixList) {
        super(dbNamespace, classPathPrefixList);
        val bucketCalculator = bucketCalculator();
        val bucketIdSaver = new BucketIdSaver(bucketCalculator);
        val bucketIdObserver = new BucketIdObserver(bucketIdSaver);
        bucketIdObserver.init(bucketIdSaver);
        registerObserver(bucketIdObserver);
    }

    public OrchSupportedDBShardingBundle(Class<?> entity, Class<?>... entities) {
        super(entity, entities);
        val bucketCalculator = bucketCalculator();
        val bucketIdSaver = new BucketIdSaver(bucketCalculator);
        val bucketIdObserver = new BucketIdObserver(bucketIdSaver);
        bucketIdObserver.init(bucketIdSaver);
        registerObserver(bucketIdObserver);
    }

    public OrchSupportedDBShardingBundle(String... classPathPrefixes) {
        super(classPathPrefixes);
        val bucketCalculator = bucketCalculator();
        val bucketIdSaver = new BucketIdSaver(bucketCalculator);
        val bucketIdObserver = new BucketIdObserver(bucketIdSaver);
        bucketIdObserver.init(bucketIdSaver);
        registerObserver(bucketIdObserver);
    }

    @Override
    protected ShardManager createShardManager(int numShards, ShardBlacklistingStore shardBlacklistingStore) {
        return new OrchSupportedShardManager(numShards, shardBlacklistingStore);
    }
}

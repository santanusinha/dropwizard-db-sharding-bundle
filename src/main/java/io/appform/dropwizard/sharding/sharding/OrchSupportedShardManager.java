package io.appform.dropwizard.sharding.sharding;

import lombok.Builder;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;

/**
 * Manages Shard to Bucket Mapping based on Orchestrator
 */
@ToString
@Slf4j
public class OrchSupportedShardManager extends ShardManager {
    private final int numShards;

    @Builder
    public OrchSupportedShardManager(int numShards) {
        this(numShards, new InMemoryLocalShardBlacklistingStore());
    }

    public OrchSupportedShardManager(int numShards, ShardBlacklistingStore shardBlacklistingStore) {
        super(shardBlacklistingStore);
        this.numShards = numShards;
    }


    @Override
    public int numBuckets() {
        return 1024;
    }

    @Override
    protected int numShards() {
        return numBuckets();
    }

    @Override
    protected int shardForBucketImpl(int bucketId) {
       //Integration with Orchestrator to give the bucketId corresponding to ShardId
        return 29;
    }
}

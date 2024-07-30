package io.appform.dropwizard.sharding.sharding;

import lombok.Builder;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.Map;

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
        return this.numShards;
    }

    @Override
    protected int shardForBucketImpl(int bucketId) {

       //TODO: RIDHIMA Integration with Orchestrator to give the bucketId corresponding to ShardId
        Map<Integer, Integer> bucketShardMapping = new HashMap<>();
        for(int i = 0; i < 1024; i++){
            bucketShardMapping.put(i, 1);
        }
        return bucketShardMapping.get(bucketId);
    }
}

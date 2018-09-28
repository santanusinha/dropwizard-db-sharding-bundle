package io.dropwizard.sharding.providers;

import lombok.NoArgsConstructor;

import javax.annotation.Nullable;

/**
 * Created on 23/09/18
 */
@NoArgsConstructor
public class ThreadLocalShardKeyProvider implements ShardKeyProvider {

    private volatile String shardId;

    @Override
    @Nullable
    public String getKey() {
        return this.shardId;
    }

    public void setKey(String shardId) {
        this.shardId = shardId;
    }
}

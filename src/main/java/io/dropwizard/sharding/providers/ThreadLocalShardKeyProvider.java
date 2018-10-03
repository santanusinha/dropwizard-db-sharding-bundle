package io.dropwizard.sharding.providers;

import lombok.NoArgsConstructor;

import javax.annotation.Nullable;

/**
 * Created on 23/09/18
 */
@NoArgsConstructor
public class ThreadLocalShardKeyProvider implements ShardKeyProvider {

    private static ThreadLocal<String> context = new ThreadLocal<>();

    @Override
    @Nullable
    public String getKey() {
        return context.get();
    }

    @Override
    public void setKey(String shardId) {
        context.set(shardId);
    }
}

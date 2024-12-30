/*
 * Copyright 2019 Santanu Sinha <santanu.sinha@gmail.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package io.appform.dropwizard.sharding.sharding;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import io.appform.dropwizard.sharding.exceptions.ShardBlacklistedException;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.TimeUnit;


/**
 *
 */
@ToString
@Slf4j
public abstract class ShardManager {

    private final ShardBlacklistingStore shardBlacklistingStore;
    private LoadingCache<Integer, Boolean> blackListedShards;

    abstract public int numBuckets();

    abstract protected int numShards();

    abstract protected int shardForBucketImpl(int bucketId);

    protected ShardManager(ShardBlacklistingStore shardBlacklistingStore) {
        this.shardBlacklistingStore = shardBlacklistingStore;
        this.blackListedShards = Caffeine.newBuilder()
                .maximumSize(10_000)
                .expireAfterWrite(1, TimeUnit.MINUTES)
                .refreshAfterWrite(15, TimeUnit.SECONDS)
                .build(shardBlacklistingStore::blacklisted);
    }

    public int shardForBucket(int bucketId) {
        final int shard = shardForBucketImpl(bucketId);
        final Boolean isBlacklisted = blackListedShards.get(shard);
        if (null != isBlacklisted && isBlacklisted) {
            throw new ShardBlacklistedException(shard);
        }
        return shard;
    }

    public boolean isMappedToValidShard(int bucketId) {
        final int shard = shardForBucketImpl(bucketId);
        final Boolean isBlacklisted = blackListedShards.get(shard);
        if (null != isBlacklisted && isBlacklisted) {
            return false;
        }
        return true;
    }

    public void blacklistShard(int shardId) {
        if (shardId >= 0 && shardId < numShards()) {
            shardBlacklistingStore.blacklist(shardId);
            blackListedShards.refresh(shardId);
        }
    }

    public boolean isBlacklisted(int shardId) {
        if (shardId >= 0
                && shardId < numShards()
                && shardBlacklistingStore != null) {
            return shardBlacklistingStore.blacklisted(shardId);
        }
        return false;
    }

    public void unblacklistShard(int shardId) {
        if (shardId >= 0 && shardId < numShards()) {
            shardBlacklistingStore.unblacklist(shardId);
            blackListedShards.refresh(shardId);
        }
    }
}

/*
 * Copyright 2018 Santanu Sinha <santanu.sinha@gmail.com>
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

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 *
 */
public class InMemoryLocalShardBlacklistingStore implements ShardBlacklistingStore {

    private final ConcurrentMap<Integer, Boolean> blacklisted = new ConcurrentHashMap<>();

    @Override
    public void blacklist(int shardId) {
        blacklisted.put(shardId, true);
    }

    @Override
    public void unblacklist(int shardId) {
        blacklisted.remove(shardId);
    }

    @Override
    public boolean blacklisted(int shardId) {
        return blacklisted.getOrDefault(shardId, false);
    }

}

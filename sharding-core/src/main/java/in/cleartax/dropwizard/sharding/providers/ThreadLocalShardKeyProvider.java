/*
 * Copyright 2018 Cleartax
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

package in.cleartax.dropwizard.sharding.providers;

import in.cleartax.dropwizard.sharding.utils.exception.Preconditions;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nullable;
import java.util.Objects;

/**
 * Created on 23/09/18
 */
@NoArgsConstructor
@Slf4j
public class ThreadLocalShardKeyProvider implements ShardKeyProvider {

    private static ThreadLocal<String> context = new ThreadLocal<>();

    @Override
    @Nullable
    public String getKey() {
        return context.get();
    }

    @Override
    public void setKey(String shardId) {
        Preconditions.checkState(Objects.isNull(this.getKey()), "Trying to set shard-key without " +
                "clearing previous context: " + this.getKey() + ", Thread: " + Thread.currentThread().getName());
        log.debug("Setting shard-key = {} in Thread: {}", shardId, Thread.currentThread().getName());
        context.set(shardId);
    }

    @Override
    public void clear() {
        log.debug("Clearing shard-key = {} in Thread: {}", this.getKey(), Thread.currentThread().getName());
        context.remove();
    }
}

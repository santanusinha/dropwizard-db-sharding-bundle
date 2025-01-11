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

package io.appform.dropwizard.sharding.admin;

import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;

/**
 *
 */
@Slf4j
public class TaskUtils {
    private TaskUtils() {
    }


    public static int parseShardParam(Map<String, List<String>> params) throws Exception {
        if (!params.containsKey("shardId")) {
            log.warn("No shard specified for blacklisting");
            throw new Exception("No shard id provided");
        }
        String shardValue = params.get("shardId")
                .stream()
                .findFirst()
                .orElse(null);
        if (null == shardValue) {
            log.warn("No shard value specified for shardId during blacklisting");
            throw new Exception("Null shard id provided");
        }

        int shard;
        try {
            shard = Integer.parseInt(shardValue);
        } catch (NumberFormatException e) {
            log.error("Invalid shard id provided", e);
            throw new Exception("Invalid shard id provided: " + shardValue);
        }
        return shard;
    }
}

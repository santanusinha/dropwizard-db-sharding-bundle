/*
 * Copyright 2026 Santanu Sinha <santanu.sinha@gmail.com>
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

package io.appform.dropwizard.sharding.e2e;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.appform.dropwizard.sharding.config.ShardedHibernateFactory;
import io.dropwizard.core.Configuration;

/**
 * Configuration for the e2e test application. Parses {@code shards} and
 * {@code metricConfig} from YAML, exercising the full Jackson/validation path
 * of {@link ShardedHibernateFactory}.
 */
public class E2ETestConfig extends Configuration {

    @JsonProperty("sharding")
    private ShardedHibernateFactory sharding = ShardedHibernateFactory.builder().build();

    public ShardedHibernateFactory getSharding() {
        return sharding;
    }

    public void setSharding(ShardedHibernateFactory sharding) {
        this.sharding = sharding;
    }
}

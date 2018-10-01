/*
 * Copyright 2018 Saurabh Agrawal (Cleartax)
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

package io.dropwizard.sharding.hibernate;

import com.google.common.collect.ImmutableMap;
import io.dropwizard.db.ManagedDataSource;
import lombok.Getter;

import java.util.HashMap;
import java.util.Map;

public class MultiTenantManagedDataSource {
    @Getter
    private final Map<String, ManagedDataSource> tenantDataSourceMap;

    public MultiTenantManagedDataSource(Map<String, ManagedDataSource> tenantDataSourceMap) {
        this.tenantDataSourceMap = ImmutableMap.copyOf(tenantDataSourceMap);
    }

    public void start() throws Exception {
        for (ManagedDataSource ds : tenantDataSourceMap.values()) {
            ds.start();
        }
    }

    public void stop() throws Exception {
        for (ManagedDataSource ds : tenantDataSourceMap.values()) {
            ds.stop();
        }
    }
}

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

package in.cleartax.dropwizard.sharding.hibernate;

import com.codahale.metrics.MetricRegistry;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import io.dropwizard.db.ManagedDataSource;
import io.dropwizard.util.Duration;
import io.dropwizard.validation.MinDuration;
import io.dropwizard.validation.ValidationMethod;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.validator.constraints.NotBlank;
import org.hibernate.validator.constraints.NotEmpty;

import javax.validation.constraints.NotNull;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Getter
@Setter
public class MultiTenantDataSourceFactory {

    public static final String REPLICA = "_replica";

    @NotEmpty
    private Map<String, ExtendedDataSourceFactory> tenantDbMap;

    private boolean autoCommentsEnabled = true;
    @NotNull
    private String validationQuery = "/* Health Check */ SELECT 1";
    @MinDuration(value = 1)
    private Duration validationQueryTimeout;
    @NotBlank
    private String defaultTenant;
    private boolean allowMultipleTenants;
    private boolean verboseLogging;

    public boolean isAutoCommentsEnabled() {
        return autoCommentsEnabled;
    }

    @JsonProperty
    public Optional<Duration> getValidationQueryTimeout() {
        return Optional.ofNullable(validationQueryTimeout);
    }

    public MultiTenantManagedDataSource build(MetricRegistry metricRegistry, String name) {
        Map<String, ManagedDataSource> tenantDataSourceMap = Maps.newHashMap();
        for (Map.Entry<String, ExtendedDataSourceFactory> tenantDataSourceEntry : tenantDbMap.entrySet()) {
            ExtendedDataSourceFactory dataSourceFactory = tenantDataSourceEntry.getValue();
            tenantDataSourceMap.put(tenantDataSourceEntry.getKey(), dataSourceFactory.build(metricRegistry,
                    name + "-" + tenantDataSourceEntry.getKey()));
            if (dataSourceFactory.getReadReplica() != null && dataSourceFactory.getReadReplica().isEnabled()) {
                tenantDataSourceMap.put(tenantDataSourceEntry.getKey() + REPLICA,
                        dataSourceFactory.getReadReplica().build(metricRegistry,
                                name + "-" + tenantDataSourceEntry.getKey() + REPLICA));
            }
        }
        return new MultiTenantManagedDataSource(tenantDataSourceMap);
    }

    public ExtendedDataSourceFactory getDefaultDataSourceFactory() {
        return tenantDbMap.get(defaultTenant);
    }

    public Map<String, ExtendedDataSourceFactory> getWritableTenants() {
        return tenantDbMap;
    }

    @ValidationMethod(message = "Tenant configuration is not valid")
    public boolean isValid() {
        return getDefaultDataSourceFactory() != null && tenantDbMap != null && !tenantDbMap.isEmpty();
    }
}
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
import io.dropwizard.db.DataSourceFactory;
import io.dropwizard.db.ManagedDataSource;
import io.dropwizard.util.Duration;
import io.dropwizard.validation.MinDuration;
import io.dropwizard.validation.ValidationMethod;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.validator.constraints.NotBlank;
import org.hibernate.validator.constraints.NotEmpty;

import javax.validation.constraints.NotNull;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Getter
@Setter
public class MultiTenantDataSourceFactory {
    @NotEmpty
    private Map<String, DataSourceFactory> tenantDbMap;

    @NotNull
    private Map<String, String> properties = new LinkedHashMap<>();

    private boolean autoCommentsEnabled = true;
    @NotNull
    private String validationQuery = "/* Health Check */ SELECT 1";
    @MinDuration(value = 1)
    private Duration validationQueryTimeout;
    @NotBlank
    private String defaultTenant;
    private boolean allowMultipleTenants;

    public boolean isAutoCommentsEnabled() {
        return autoCommentsEnabled;
    }

    @JsonProperty
    public Optional<Duration> getValidationQueryTimeout() {
        return Optional.ofNullable(validationQueryTimeout);
    }

    public MultiTenantManagedDataSource build(MetricRegistry metricRegistry, String name) {
        Map<String, ManagedDataSource> tenantDataSourceMap = tenantDbMap.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey,
                        e -> e.getValue().build(metricRegistry, name + "-" + e.getKey())));
        return new MultiTenantManagedDataSource(tenantDataSourceMap);
    }

    public DataSourceFactory getDefaultDataSourceFactory() {
        return tenantDbMap.get(defaultTenant);
    }

    @ValidationMethod(message = "Tenant configuration is not valid")
    public boolean isValid() {
        return getDefaultDataSourceFactory() != null &&
                (allowMultipleTenants ? tenantDbMap.size() > 1 : tenantDbMap.size() == 1);
    }
}

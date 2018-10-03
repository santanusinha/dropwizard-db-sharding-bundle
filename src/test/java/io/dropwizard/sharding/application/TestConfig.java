package io.dropwizard.sharding.application;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.dropwizard.Configuration;
import io.dropwizard.sharding.hibernate.MultiTenantDataSourceFactory;
import lombok.Data;
import lombok.EqualsAndHashCode;

import javax.validation.Valid;
import javax.validation.constraints.NotNull;

/**
 * Created on 02/10/18
 */

@Data
@EqualsAndHashCode(callSuper = true)
public class TestConfig extends Configuration {
    @Valid
    @NotNull
    @JsonProperty("multiDatabase")
    private MultiTenantDataSourceFactory multiTenantDataSourceFactory;
}

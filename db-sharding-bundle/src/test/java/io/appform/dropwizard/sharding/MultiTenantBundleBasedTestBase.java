package io.appform.dropwizard.sharding;

import com.codahale.metrics.health.HealthCheckRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.Maps;
import io.appform.dropwizard.sharding.config.MultiTenantShardedHibernateFactory;
import io.appform.dropwizard.sharding.config.ShardedHibernateFactory;
import io.appform.dropwizard.sharding.config.ShardingBundleOptions;
import io.dropwizard.Configuration;
import io.dropwizard.db.DataSourceFactory;
import io.dropwizard.jersey.DropwizardResourceConfig;
import io.dropwizard.jersey.setup.JerseyEnvironment;
import io.dropwizard.lifecycle.setup.LifecycleEnvironment;
import io.dropwizard.setup.AdminEnvironment;
import io.dropwizard.setup.Bootstrap;
import io.dropwizard.setup.Environment;
import lombok.Getter;
import org.junit.jupiter.api.BeforeEach;

import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;


public abstract class MultiTenantBundleBasedTestBase {

  protected static class TestConfig extends Configuration {

    @Getter
    private MultiTenantShardedHibernateFactory shards = new MultiTenantShardedHibernateFactory(Map.of("TENANT1",
        ShardedHibernateFactory.builder()
            .shardingOptions(ShardingBundleOptions.builder().build()).build(),
        "TENANT2", ShardedHibernateFactory.builder()
            .shardingOptions(ShardingBundleOptions.builder().build()).build()));
  }

  protected final TestConfig testConfig = new TestConfig();
  protected final HealthCheckRegistry healthChecks = mock(HealthCheckRegistry.class);
  protected final JerseyEnvironment jerseyEnvironment = mock(JerseyEnvironment.class);
  protected final LifecycleEnvironment lifecycleEnvironment = mock(LifecycleEnvironment.class);
  protected final Environment environment = mock(Environment.class);
  protected final AdminEnvironment adminEnvironment = mock(AdminEnvironment.class);
  protected final Bootstrap<?> bootstrap = mock(Bootstrap.class);

  protected abstract MultiTenantDBShardingBundleBase<TestConfig> getBundle();

  protected DataSourceFactory createConfig(String dbName) {
    Map<String, String> properties = Maps.newHashMap();
    properties.put("hibernate.dialect", "org.hibernate.dialect.H2Dialect");
    properties.put("hibernate.hbm2ddl.auto", "create");

    DataSourceFactory shard = new DataSourceFactory();
    shard.setDriverClass("org.h2.Driver");
    shard.setUrl("jdbc:h2:mem:" + dbName);
    shard.setValidationQuery("select 1");
    shard.setProperties(properties);

    return shard;
  }

  @BeforeEach
  public void setup() {
    testConfig.shards.getTenants().get("TENANT1")
        .setShards(List.of(createConfig("tenant1_1"), createConfig("tenant1_2")));
    testConfig.shards.getTenants().get("TENANT2")
        .setShards(List.of(createConfig("tenant2_1"), createConfig("tenant2_2")));
    when(jerseyEnvironment.getResourceConfig()).thenReturn(new DropwizardResourceConfig());
    when(environment.jersey()).thenReturn(jerseyEnvironment);
    when(environment.lifecycle()).thenReturn(lifecycleEnvironment);
    when(environment.healthChecks()).thenReturn(healthChecks);
    when(environment.admin()).thenReturn(adminEnvironment);
    when(bootstrap.getHealthCheckRegistry()).thenReturn(mock(HealthCheckRegistry.class));
    when(bootstrap.getObjectMapper()).thenReturn(mock(ObjectMapper.class));
  }
}

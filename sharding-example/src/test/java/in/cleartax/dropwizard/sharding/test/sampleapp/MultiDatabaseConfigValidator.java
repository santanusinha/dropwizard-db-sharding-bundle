package in.cleartax.dropwizard.sharding.test.sampleapp;

import com.fasterxml.jackson.databind.ObjectMapper;
import in.cleartax.dropwizard.sharding.hibernate.MultiTenantDataSourceFactory;
import io.dropwizard.configuration.ConfigurationException;
import io.dropwizard.configuration.ConfigurationValidationException;
import io.dropwizard.configuration.YamlConfigurationFactory;
import io.dropwizard.jackson.Jackson;
import io.dropwizard.jersey.validation.Validators;
import org.junit.Test;

import javax.validation.Validator;
import java.io.File;
import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Created by mohitsingh on 20/12/18.
 */
public class MultiDatabaseConfigValidator {

    @Test
    public void testMultiDatabaseCorrectConfig() throws IOException, ConfigurationException {
        final ObjectMapper objectMapper = Jackson.newObjectMapper();
        final Validator validator = Validators.newValidator();
        final YamlConfigurationFactory<MultiTenantDataSourceFactory> factory = new YamlConfigurationFactory<>(MultiTenantDataSourceFactory.class, validator, objectMapper, "dw");

        final File correctReadReplicaYaml = new File(Thread.currentThread().getContextClassLoader().getResource("correct_multi_tenant_conf.yml").getPath());
        final File correctMultiTenantYaml = new File(Thread.currentThread().getContextClassLoader().getResource("correct_multi_tenant_conf.yml").getPath());

        final MultiTenantDataSourceFactory configuration1 = factory.build(correctReadReplicaYaml);
        assertThat(configuration1.isValid()).isTrue();
        final MultiTenantDataSourceFactory configuration2 = factory.build(correctMultiTenantYaml);
        assertThat(configuration2.isValid()).isTrue();
    }

    @Test(expected = ConfigurationValidationException.class)
    public void testMultiDatabaseIncorrectConfig() throws IOException, ConfigurationException {
        final ObjectMapper objectMapper = Jackson.newObjectMapper();
        final Validator validator = Validators.newValidator();
        final YamlConfigurationFactory<MultiTenantDataSourceFactory> factory = new YamlConfigurationFactory<>(MultiTenantDataSourceFactory.class, validator, objectMapper, "dw");

        final File incorrectMultiTenantYaml = new File(Thread.currentThread().getContextClassLoader().getResource("incorrect_multi_tenant_conf.yml").getPath());
        final MultiTenantDataSourceFactory configuration1 = factory.build(incorrectMultiTenantYaml);
    }
}

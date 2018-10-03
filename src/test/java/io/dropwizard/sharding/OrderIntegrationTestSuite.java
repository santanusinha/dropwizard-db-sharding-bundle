package io.dropwizard.sharding;

import io.dropwizard.client.JerseyClientBuilder;
import io.dropwizard.client.JerseyClientConfiguration;
import io.dropwizard.setup.Environment;
import io.dropwizard.sharding.application.TestApplication;
import io.dropwizard.sharding.application.TestConfig;
import io.dropwizard.testing.ResourceHelpers;
import io.dropwizard.testing.junit.DropwizardAppRule;
import io.dropwizard.util.Duration;
import org.junit.ClassRule;
import org.junit.runner.RunWith;
import org.junit.runners.Suite;

import javax.ws.rs.client.Client;

/**
 * Created on 03/10/18
 */
@RunWith(Suite.class)
@Suite.SuiteClasses({OrderIntegrationTest.class})
public class OrderIntegrationTestSuite {
    private static final String TEST_CONFIG_PATH = ResourceHelpers.resourceFilePath("test.yml");
    @ClassRule
    public static final DropwizardAppRule<TestConfig> RULE =
            new DropwizardAppRule<>(TestApplication.class, TEST_CONFIG_PATH);
    public static Client client;

    static {
        RULE.addListener(new DropwizardAppRule.ServiceListener<TestConfig>() {
            @Override
            public void onRun(TestConfig configuration, Environment environment, DropwizardAppRule<TestConfig> rule) throws Exception {
                super.onRun(configuration, environment, rule);

                // Building Jersey client
                JerseyClientConfiguration jerseyClientConfiguration = new JerseyClientConfiguration();
                // increasing minThreads from 1 (default) to 2 to ensure async requests run in parallel.
                jerseyClientConfiguration.setMinThreads(2);
                jerseyClientConfiguration.setTimeout(Duration.seconds(60));
                client = new JerseyClientBuilder(RULE.getEnvironment()).using(jerseyClientConfiguration).build("test-client");
            }
        });
    }
}

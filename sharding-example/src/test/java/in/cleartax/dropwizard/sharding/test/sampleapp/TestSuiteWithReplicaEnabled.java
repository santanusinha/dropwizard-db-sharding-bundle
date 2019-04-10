package in.cleartax.dropwizard.sharding.test.sampleapp;

import com.google.common.collect.ImmutableMap;
import in.cleartax.dropwizard.sharding.application.TestApplication;
import in.cleartax.dropwizard.sharding.application.TestConfig;
import in.cleartax.dropwizard.sharding.test.sampleapp.utils.ReadOnlyDBConfigModifier;
import io.dropwizard.setup.Environment;
import io.dropwizard.testing.junit.DropwizardAppRule;
import org.junit.ClassRule;
import org.junit.runner.RunWith;
import org.junit.runners.Suite;

import javax.ws.rs.client.Client;

/**
 * Created on 05/10/18
 */
@RunWith(Suite.class)
@Suite.SuiteClasses({OrderIntegrationTestWithReplica.class})
public class TestSuiteWithReplicaEnabled {
    private static final String TEST_CONFIG_PATH =
            ReadOnlyDBConfigModifier.modifyReadOnlyDBConfig("test_with_replica.yml",
                    ImmutableMap.of("READ_ONLY_DB_URL1", "read_replica_1",
                            "READ_ONLY_DB_URL2", "read_replica_2"));

    @ClassRule
    public static final DropwizardAppRule<TestConfig> RULE =
            new DropwizardAppRule<>(TestApplication.class, TEST_CONFIG_PATH);
    public static Client client;

    static {
        RULE.addListener(new DropwizardAppRule.ServiceListener<TestConfig>() {
            @Override
            public void onRun(TestConfig configuration, Environment environment, DropwizardAppRule<TestConfig> rule)
                    throws Exception {
                client = TestHelper.onSuiteRun(rule);
            }
        });
    }
}

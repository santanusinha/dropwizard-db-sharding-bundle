package io.dropwizard.sharding.test;

import com.google.common.io.Resources;
import io.dropwizard.client.JerseyClientBuilder;
import io.dropwizard.client.JerseyClientConfiguration;
import io.dropwizard.db.DataSourceFactory;
import io.dropwizard.db.ManagedDataSource;
import io.dropwizard.setup.Environment;
import io.dropwizard.sharding.test.application.TestApplication;
import io.dropwizard.sharding.test.application.TestConfig;
import io.dropwizard.testing.ResourceHelpers;
import io.dropwizard.testing.junit.DropwizardAppRule;
import io.dropwizard.util.Duration;
import lombok.extern.slf4j.Slf4j;
import org.h2.tools.RunScript;
import org.junit.ClassRule;
import org.junit.runner.RunWith;
import org.junit.runners.Suite;

import javax.ws.rs.client.Client;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URL;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * Created on 03/10/18
 */
@RunWith(Suite.class)
@Suite.SuiteClasses({OrderIntegrationTest.class})
@Slf4j
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

                for (DataSourceFactory ds : RULE.getConfiguration().getMultiTenantDataSourceFactory()
                        .getTenantDbMap().values()) {
                    initDb(ds, "init_db.sql");
                }

                initDb(RULE.getConfiguration().getMultiTenantDataSourceFactory().getDefaultDataSourceFactory(),
                        "default_shard_config.sql");

                // Building Jersey client
                JerseyClientConfiguration jerseyClientConfiguration = new JerseyClientConfiguration();
                // increasing minThreads from 1 (default) to 2 to ensure async requests run in parallel.
                jerseyClientConfiguration.setMinThreads(2);
                jerseyClientConfiguration.setTimeout(Duration.seconds(60));
                client = new JerseyClientBuilder(RULE.getEnvironment()).using(jerseyClientConfiguration).build("test-client");
            }
        });
    }

    private static void initDb(DataSourceFactory ds, String sqlFile) throws SQLException, IOException {
        ManagedDataSource managedDataSource = ds
                .build(RULE.getEnvironment().metrics(), "init");

        // Seed data
        log.info("Running init_db.sql to load seed data");
        try (Connection connection = managedDataSource.getConnection()) {
            URL url = Resources.getResource(sqlFile);
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(url.openStream()))) {
                RunScript.execute(connection, reader);
            }
        }
    }
}

package in.cleartax.dropwizard.sharding.test.sampleapp;

import com.google.common.io.Resources;
import in.cleartax.dropwizard.sharding.application.TestConfig;
import in.cleartax.dropwizard.sharding.dto.OrderDto;
import in.cleartax.dropwizard.sharding.hibernate.MultiTenantManagedDataSource;
import io.dropwizard.client.JerseyClientBuilder;
import io.dropwizard.client.JerseyClientConfiguration;
import io.dropwizard.db.ManagedDataSource;
import io.dropwizard.testing.junit.DropwizardAppRule;
import io.dropwizard.util.Duration;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.HttpStatus;
import org.h2.tools.RunScript;

import javax.ws.rs.ForbiddenException;
import javax.ws.rs.client.Client;
import javax.ws.rs.client.Entity;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URL;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Created on 05/10/18
 */
@Slf4j
public class TestHelper {

    public static Client onSuiteRun(DropwizardAppRule<TestConfig> rule)
            throws Exception {

        if(rule.getConfiguration().getMultiTenantDataSourceFactory().isReadOnlyReplicaEnabled()) {
            PrepareReadOnlyTestDB.generateReplicaDB();
        }

        MultiTenantManagedDataSource multiTenantManagedDataSource =
                rule.getConfiguration().getMultiTenantDataSourceFactory()
                        .build(rule.getEnvironment().metrics(), "migrations");

        for(Map.Entry<String, ManagedDataSource> entry : multiTenantManagedDataSource.getTenantDataSourceMap().entrySet()) {
            if(rule.getConfiguration().getMultiTenantDataSourceFactory().getWriteShards().containsKey(entry.getKey())) {
                initDb("init_db.sql", entry.getValue());
            }
        }
        initDb("default_shard_config.sql", multiTenantManagedDataSource.getTenantDataSourceMap().get(
                rule.getConfiguration().getMultiTenantDataSourceFactory().getDefaultTenant()));


        // Building Jersey client
        JerseyClientConfiguration jerseyClientConfiguration = new JerseyClientConfiguration();
        // increasing minThreads from 1 (default) to 2 to ensure async requests run in parallel.
        jerseyClientConfiguration.setMinThreads(2);
        jerseyClientConfiguration.setTimeout(Duration.seconds(300));
        return new JerseyClientBuilder(rule.getEnvironment())
                .using(jerseyClientConfiguration).build("test-client");
    }


    private static void initDb(String sqlFile,
                               ManagedDataSource managedDataSource) throws SQLException, IOException {
        // Seed data
        log.info("Running init_db.sql to load seed data");
        try (Connection connection = managedDataSource.getConnection()) {
            URL url = Resources.getResource(sqlFile);
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(url.openStream()))) {
                RunScript.execute(connection, reader);
            }
        }
    }

    public static void initDb(String sqlFile, Connection con) throws SQLException, IOException {
        URL url = Resources.getResource(sqlFile);
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(url.openStream()))) {
            RunScript.execute(con, reader);
        }
    }

    public static OrderDto createOrder(OrderDto order, Client client, String host,
                                       String authToken) {
        Response response = client.target(
                String.format("%s/v0.1/orders", host))
                .request()
                .header(authToken, order.getCustomerId())
                .put(Entity.entity(order, MediaType.APPLICATION_JSON_TYPE));
        assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_OK);
        return response.readEntity(OrderDto.class);
    }

    public static OrderDto createOrderOnReplica(OrderDto order, Client client, String host,
                                       String authToken) throws Exception {
       Response response = client.target(
                String.format("%s/v0.1/orders/replica", host))
                .request()
                .header(authToken, order.getCustomerId())
                .put(Entity.entity(order, MediaType.APPLICATION_JSON_TYPE));
        assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_FORBIDDEN);
        throw new ForbiddenException("Unauthorized Access Of ReadOnlyDB");
    }

    public static OrderDto getOrder(long id, String customerId, Client client, String host,
                                    String authToken) {
        Response response = client.target(
                String.format("%s/v0.1/orders/%d", host, id))
                .request()
                .header(authToken, customerId)
                .get();
        assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_OK);
        return response.readEntity(OrderDto.class);
    }

    public static OrderDto getOrderFromReplica(long id, String customerId, Client client, String host,
                                    String authToken) {
        Response response = client.target(
                String.format("%s/v0.1/orders/replica/%d", host, id))
                .request()
                .header(authToken, customerId)
                .get();
        assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_OK);
        return response.readEntity(OrderDto.class);
    }

    public static OrderDto getOrder(long id, String customerId, Client client, String host,
                                    String authToken, String shardId) {
        Response response = client.target(
                String.format("%s/v0.1/orders/%d/%s", host, id, shardId))
                .request()
                .header(authToken, customerId)
                .get();
        assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_OK);
        return response.readEntity(OrderDto.class);
    }

    public static OrderDto triggerAutoFlush(OrderDto order, Client client, String host,
                                            String authToken) {
        Response response = client.target(
                String.format("%s/v0.1/orders/auto-flush-test", host))
                .request()
                .header(authToken, order.getCustomerId())
                .post(Entity.entity(order, MediaType.APPLICATION_JSON_TYPE));
        assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_OK);
        return response.readEntity(OrderDto.class);
    }
}
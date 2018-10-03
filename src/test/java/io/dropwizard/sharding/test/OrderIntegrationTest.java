package io.dropwizard.sharding.test;

import com.google.common.collect.Lists;
import io.dropwizard.sharding.test.application.TestConfig;
import io.dropwizard.sharding.test.testdata.entities.Order;
import io.dropwizard.sharding.test.testdata.entities.OrderItem;
import io.dropwizard.testing.junit.DropwizardAppRule;
import lombok.RequiredArgsConstructor;
import org.apache.http.HttpStatus;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import javax.ws.rs.client.Client;
import javax.ws.rs.client.Entity;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Created on 03/10/18
 */
@RunWith(Parameterized.class)
@RequiredArgsConstructor
public class OrderIntegrationTest {
    @ClassRule
    public static final DropwizardAppRule<TestConfig> RULE = OrderIntegrationTestSuite.RULE;
    private static final String AUTH_TOKEN = "X-Auth-Token";
    private static Client client;
    private static String host;
    private final String customerId;

    @Parameterized.Parameters
    public static List<String> customerIds() {
        return Lists.newArrayList("1", "2", "3");
    }

    @BeforeClass
    public static void setUp() {
        client = OrderIntegrationTestSuite.client;
        host = String.format("http://localhost:%d/api", RULE.getLocalPort());
    }

    private Response createOrder(Order order) {
        Response response = client.target(
                String.format("%s/v0.1/orders", host))
                .request()
                .header(AUTH_TOKEN, order.getCustomerId())
                .put(Entity.entity(order, MediaType.APPLICATION_JSON_TYPE));
        assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_OK);
        return response;
    }

    @Test
    public void testCreateOrder() {
        final String orderId = UUID.randomUUID().toString();
        Order order = Order.builder()
                .orderId(orderId)
                .amount(100)
                .items(Lists.newArrayList(
                        OrderItem.builder().name("test").build()
                ))
                .customerId(customerId)
                .build();
        Response response = createOrder(order);
        order = response.readEntity(Order.class);
        assertThat(order.getOrderId())
                .describedAs("Created order for customer = " + customerId)
                .isEqualTo(orderId);
    }
}

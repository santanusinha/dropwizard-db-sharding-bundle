package in.cleartax.dropwizard.sharding.test.sampleapp;

import com.google.common.collect.Lists;
import in.cleartax.dropwizard.sharding.test.sampleapp.application.TestConfig;
import in.cleartax.dropwizard.sharding.test.sampleapp.testdata.dto.OrderDto;
import in.cleartax.dropwizard.sharding.test.sampleapp.testdata.dto.OrderItemDto;
import io.dropwizard.testing.junit.DropwizardAppRule;
import lombok.RequiredArgsConstructor;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;

import javax.ws.rs.client.Client;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Created on 05/10/18
 */
@RequiredArgsConstructor
public class OrderIntegrationTestWithoutMultiTenancy {
    @ClassRule
    public static final DropwizardAppRule<TestConfig> RULE = IntegrationTestSuiteWithoutMultiTenancy.RULE;
    private static final String AUTH_TOKEN = "X-Auth-Token";
    private static Client client;
    private static String host;

    @BeforeClass
    public static void setUp() {
        client = IntegrationTestSuiteWithoutMultiTenancy.client;
        host = String.format("http://localhost:%d/api", RULE.getLocalPort());
    }

    @Test
    public void testCreateOrderWithoutSharding() {
        final String orderId = UUID.randomUUID().toString();
        final String customerId = "3";
        OrderDto orderDto = OrderDto.builder()
                .orderId(orderId)
                .amount(100)
                .items(Lists.newArrayList(
                        OrderItemDto.builder().name("test").build()
                ))
                .customerId(customerId)
                .build();
        orderDto = TestHelper.createOrder(orderDto, client, host, AUTH_TOKEN);
        assertThat(orderDto.getOrderId())
                .describedAs("Created order for customer = " + customerId)
                .isEqualTo(orderId);
        assertThat(orderDto.getCustomerId()).isEqualTo(customerId);

        orderDto = TestHelper.getOrder(orderDto.getId(), orderDto.getCustomerId(), client, host, AUTH_TOKEN);
        assertThat(orderDto.getOrderId())
                .describedAs("Fetched order for customer = " + customerId)
                .isEqualTo(orderId);
        assertThat(orderDto.getCustomerId()).isEqualTo(customerId);
    }
}

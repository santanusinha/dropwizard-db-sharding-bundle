/*
 * Copyright 2018 Cleartax
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

package in.cleartax.dropwizard.sharding.test.sampleapp;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import in.cleartax.dropwizard.sharding.application.TestConfig;
import in.cleartax.dropwizard.sharding.dto.OrderDto;
import in.cleartax.dropwizard.sharding.dto.OrderItemDto;
import io.dropwizard.testing.junit.DropwizardAppRule;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import javax.ws.rs.client.Client;
import java.util.List;
import java.util.UUID;

import static in.cleartax.dropwizard.sharding.test.sampleapp.utils.AssertionUtil.assertOrderPresentOnShard;
import static org.assertj.core.api.Assertions.assertThat;

@RunWith(Parameterized.class)
@RequiredArgsConstructor
public class OrderIntegrationTestWithMultiTenancy {
    @ClassRule
    public static final DropwizardAppRule<TestConfig> RULE = TestSuiteWithMultiTenancy.RULE;
    private static final String AUTH_TOKEN = "X-Auth-Token";
    private static final List<String> shards = ImmutableList.of("shard1", "shard2");
    private static Client client;
    private static String host;
    private final ImmutablePair<String, String> customerIdAndShardId;

    @Parameterized.Parameters
    public static List<ImmutablePair<String, String>> customerIds() {
        return Lists.newArrayList(ImmutablePair.of("1", "shard1"),
                ImmutablePair.of("2", "shard1"),
                ImmutablePair.of("3", "shard2"));
    }

    @BeforeClass
    public static void setUp() {
        client = TestSuiteWithMultiTenancy.client;
        host = String.format("http://localhost:%d/api", RULE.getLocalPort());
    }

    @Test
    public void testCreateOrder() throws Throwable {
        final String orderId = UUID.randomUUID().toString();
        final int updatedAmt = 1000;
        OrderDto orderDto = OrderDto.builder()
                .orderId(orderId)
                .amount(100)
                .items(Lists.newArrayList(
                        OrderItemDto.builder().name("test").build()
                ))
                .customerId(customerIdAndShardId.getLeft())
                .build();
        orderDto = TestHelper.createOrder(orderDto, client, host, AUTH_TOKEN);
        assertThat(orderDto.getOrderId())
                .describedAs("Created order for customer = " + customerIdAndShardId.getLeft())
                .isEqualTo(orderId);
        assertThat(orderDto.getCustomerId()).isEqualTo(customerIdAndShardId.getLeft());

        orderDto = TestHelper.getOrder(orderDto.getId(), orderDto.getCustomerId(), client, host, AUTH_TOKEN);
        assertThat(orderDto.getOrderId())
                .describedAs("Fetched order for customer = " + customerIdAndShardId.getLeft())
                .isEqualTo(orderId);
        assertThat(orderDto.getCustomerId()).isEqualTo(customerIdAndShardId.getLeft());

        orderDto = TestHelper.getOrder(orderDto.getId(), orderDto.getCustomerId(), client, host, AUTH_TOKEN,
                customerIdAndShardId.getRight());
        assertThat(orderDto.getOrderId())
                .describedAs("Fetched order for customer = " + customerIdAndShardId.getLeft() +
                        " from shard = " + customerIdAndShardId.getRight())
                .isEqualTo(orderId);
        assertThat(orderDto.getCustomerId()).isEqualTo(customerIdAndShardId.getLeft());

        orderDto.setAmount(updatedAmt);
        TestHelper.triggerAutoFlush(orderDto, client, host, AUTH_TOKEN);
        orderDto = TestHelper.getOrder(orderDto.getId(), orderDto.getCustomerId(), client, host, AUTH_TOKEN);
        assertThat(orderDto.getAmount())
                .describedAs("Auto-flush caused entity to be updated")
                .isEqualTo(updatedAmt);
        assertThat(orderDto.getCustomerId()).isEqualTo(customerIdAndShardId.getLeft());

        assertOrderPresentOnShard(customerIdAndShardId.getRight(), orderDto, shards, RULE);
    }
}

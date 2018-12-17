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

import javax.ws.rs.ForbiddenException;
import javax.ws.rs.client.Client;
import java.util.List;
import java.util.UUID;

import static in.cleartax.dropwizard.sharding.test.sampleapp.utils.AssertionUtil.assertOrderPresentOnShard;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@RunWith(Parameterized.class)
@RequiredArgsConstructor
public class OrderIntegrationTestWithReplica {
    @ClassRule
    public static final DropwizardAppRule<TestConfig> RULE = TestSuiteWithReplicaEnabled.RULE;
    private static final String AUTH_TOKEN = "X-Auth-Token";
    private static final List<String> shards = ImmutableList.of("readReplica", "shard1");
    private static Client client;
    private static String host;
    private final ImmutablePair<Integer, ImmutablePair<String, String > > orderIdAndCustomerIdTenantIdMap; // {orderId, (customerId, shardId)}

    @Parameterized.Parameters
    public static List<ImmutablePair<Integer, ImmutablePair<String, String> > > customerIds() {
        return Lists.newArrayList(ImmutablePair.of(1, ImmutablePair.of("1", "readReplica")));
    }

    @BeforeClass
    public static void setUp() {
        client = TestSuiteWithReplicaEnabled.client;
        host = String.format("http://localhost:%d/api", RULE.getLocalPort());
    }

    @Test
    public void testCreateOrder() throws Throwable {
        final Integer orderId = orderIdAndCustomerIdTenantIdMap.getLeft();
        final String orderExtId = "11111111-2222-3333-4444-aaaaaaaaaaa" + orderId;
        final String customerId = orderIdAndCustomerIdTenantIdMap.getRight().getLeft();

        OrderDto orderDtoFromReplica = TestHelper.getOrderFromReplica(orderId,customerId, client, host, AUTH_TOKEN);
        assertThat(orderDtoFromReplica.getOrderId())
                .describedAs("Fetched order for customer = " + orderIdAndCustomerIdTenantIdMap.getRight().getLeft())
                .isEqualTo(orderExtId);
        assertThat(orderDtoFromReplica.getCustomerId()).isEqualTo(orderIdAndCustomerIdTenantIdMap.getRight().getLeft());
        assertOrderPresentOnShard(orderIdAndCustomerIdTenantIdMap.getRight().getRight(), orderDtoFromReplica, shards, RULE);
        final String oId = UUID.randomUUID().toString();

        OrderDto orderDto = OrderDto.builder()
                .orderId(oId)
                .amount(100)
                .items(Lists.newArrayList(
                        OrderItemDto.builder().name("test").build()
                ))
                .customerId("1")
                .build();
        assertThatThrownBy(() -> TestHelper.createOrderOnReplica(orderDto, client, host, AUTH_TOKEN))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage("Unauthorized Access Of ReadOnlyDB");
    }
}

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
import in.cleartax.dropwizard.sharding.application.TestApplication;
import in.cleartax.dropwizard.sharding.application.TestConfig;
import in.cleartax.dropwizard.sharding.dao.OrderDao;
import in.cleartax.dropwizard.sharding.dto.OrderDto;
import in.cleartax.dropwizard.sharding.dto.OrderItemDto;
import in.cleartax.dropwizard.sharding.entities.Order;
import in.cleartax.dropwizard.sharding.hibernate.ConstTenantIdentifierResolver;
import in.cleartax.dropwizard.sharding.hibernate.MultiTenantSessionSource;
import in.cleartax.dropwizard.sharding.transactions.DefaultUnitOfWorkImpl;
import in.cleartax.dropwizard.sharding.transactions.TransactionRunner;
import io.dropwizard.testing.junit.DropwizardAppRule;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import ru.vyarus.dropwizard.guice.GuiceBundle;

import javax.ws.rs.client.Client;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

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

    private void assertOrderPresentOnShard(final String expectedOnShard, final OrderDto orderDto) throws Throwable {
        final GuiceBundle<TestConfig> guiceBundle = ((TestApplication) RULE.getApplication()).getGuiceBundle();
        final OrderDao orderDao = guiceBundle.getInjector().getInstance(OrderDao.class);
        final MultiTenantSessionSource multiTenantSessionSource = guiceBundle.getInjector()
                .getInstance(MultiTenantSessionSource.class);
        for (final String eachShard : shards) {
            new TransactionRunner<Order>(multiTenantSessionSource.getUnitOfWorkAwareProxyFactory(),
                    multiTenantSessionSource.getSessionFactory(), new ConstTenantIdentifierResolver(eachShard)) {
                @Override
                public Order run() {
                    Order order = orderDao.get(orderDto.getId());
                    if (eachShard.equals(expectedOnShard)) {
                        assertThat(order)
                                .describedAs(String.format("Expecting order with id: %s, " +
                                                "for customer: %s, on shard: %s",
                                        orderDto.getId(), orderDto.getCustomerId(), eachShard))
                                .isNotNull();
                    } else {
                        // Two orders with same ID can exist on different shard
                        assertThat(order == null || !order.getCustomerId().equals(orderDto.getCustomerId()))
                                .describedAs(String.format("Not expecting order with id: %s, " +
                                                "for customer: %s, on shard: %s",
                                        orderDto.getId(), orderDto.getCustomerId(), eachShard))
                                .isTrue();
                    }
                    return order;
                }
            }.start(false, new DefaultUnitOfWorkImpl());
        }
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
        assertThat(orderDtoFromReplica.isReadOnly()).isEqualTo(true); // Just another layer of check
        assertOrderPresentOnShard(orderIdAndCustomerIdTenantIdMap.getRight().getRight(), orderDtoFromReplica);
    }
}

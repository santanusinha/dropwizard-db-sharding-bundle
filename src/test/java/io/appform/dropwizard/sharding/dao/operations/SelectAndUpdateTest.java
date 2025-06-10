package io.appform.dropwizard.sharding.dao.operations;

import io.appform.dropwizard.sharding.dao.testdata.entities.Order;
import lombok.val;
import org.hibernate.Session;
import org.hibernate.criterion.DetachedCriteria;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.Mockito;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Function;

class SelectAndUpdateTest {

    @Mock
    Session session;

    @Test
    void testSelectAndUpdate_withMutators() {

        Order o = Order.builder().id(1).customerId("C1").build();
        Order o2 = Order.builder().id(2).customerId("C2").build();
        List<Order> orders = Arrays.asList(o, o2);

        Function<SelectParam<Order>, List<Order>> spiedSelector = LambdaTestUtils.spiedFunction(
                s -> orders);

        BiConsumer<Order, Order> spiedUpdater = LambdaTestUtils.spiedBiConsumer((v1, v2) -> {
        });

        val selectAndUpdate = SelectAndUpdate.<Order>builder()
                .selectParam(
                        SelectParam.<Order>builder()
                                .criteria(DetachedCriteria.forClass(Order.class))
                                .build())
                .selector(spiedSelector)
                .mutator(order -> order.setCustomerId("C2"))
                .updater(spiedUpdater)
                .build();

        Assertions.assertTrue(selectAndUpdate.apply(session));
        Mockito.verify(spiedUpdater, Mockito.times(1))
                .accept(Mockito.any(),
                        ArgumentMatchers.argThat((Order x) -> x.getCustomerId().equals("C2")));
    }


    @Test
    void testUpdateAll_NoResult() {

        Function<SelectParam<Order>, List<Order>> spiedSelector = LambdaTestUtils.spiedFunction(
                s -> Collections.emptyList());

        BiConsumer<Order, Order> spiedUpdater = LambdaTestUtils.spiedBiConsumer((v1, v2) -> {
        });

        val selectAndUpdate = SelectAndUpdate.<Order>builder()
                .selectParam(
                        SelectParam.<Order>builder()
                                .criteria(DetachedCriteria.forClass(Order.class))
                                .build())
                .selector(spiedSelector)
                .mutator(order -> order.setCustomerId("C2"))
                .updater(spiedUpdater).build();

        Assertions.assertFalse(selectAndUpdate.apply(session));
        Mockito.verify(spiedUpdater, Mockito.times(0))
                .accept(Mockito.any(), Mockito.any());
    }

    @Test
    void testUpdateAll_mutatorGivingNull() {

        Order o = Order.builder().id(1).customerId("C1").build();
        Order o2 = Order.builder().id(2).customerId("C2").build();
        List<Order> orders = Arrays.asList(o, o2);

        Function<SelectParam<Order>, List<Order>> spiedSelector = LambdaTestUtils.spiedFunction(
                s -> orders);

        BiConsumer<Order, Order> spiedUpdater = LambdaTestUtils.spiedBiConsumer((v1, v2) -> {
        });

        val selectAndUpdate = SelectAndUpdate.<Order>builder()
                .selectParam(
                        SelectParam.<Order>builder()
                                .criteria(DetachedCriteria.forClass(Order.class))
                                .build())
                .selector(spiedSelector)
                .mutator(order -> null)
                .updater(spiedUpdater).build();

        Assertions.assertFalse(selectAndUpdate.apply(session));
        Mockito.verify(spiedUpdater, Mockito.times(0))
                .accept(Mockito.any(), Mockito.any());
    }
}
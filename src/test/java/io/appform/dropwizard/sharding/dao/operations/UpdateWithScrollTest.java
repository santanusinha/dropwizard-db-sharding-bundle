package io.appform.dropwizard.sharding.dao.operations;

import io.appform.dropwizard.sharding.dao.testdata.entities.Order;
import lombok.val;
import org.hibernate.ScrollableResults;
import org.hibernate.Session;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.Mockito;

import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class UpdateWithScrollTest {

  @Mock
  Session session;


  @Test
  void testUpdateWithScroll_withMutators() {

    Order o = Order.builder().id(1).customerId("C1").build();
    Order o2 = Order.builder().id(2).customerId("C2").build();

    ScrollableResults scrollableResults = mock(ScrollableResults.class);
    when(scrollableResults.get()).thenReturn(o, o2);
    when(scrollableResults.next()).thenReturn(true, true, false);

    Function<ScrollParam<Order>, ScrollableResults> spiedScroll = LambdaTestUtils.spiedFunction(
        s -> scrollableResults);

    BooleanSupplier spiedUpdateNext = mock(BooleanSupplier.class);
    when(spiedUpdateNext.getAsBoolean()).thenReturn(true, false);

    BiConsumer<Order, Order> spiedUpdater = LambdaTestUtils.spiedBiConsumer((v1, v2) -> {
    });

    val updateWithScroll = UpdateWithScroll.<Order>builder()
        .scrollParam(
            ScrollParam.<Order>builder()
                .querySpec((queryRoot, query, criteriaBuilder) -> {})
                .build())
        .scroll(spiedScroll)
        .updateNext(spiedUpdateNext)
        .mutator(order -> order.setCustomerId("C2"))
        .updater(spiedUpdater).build();

    Assertions.assertTrue(updateWithScroll.apply(session));
    Mockito.verify(spiedUpdater, Mockito.times(2))
        .accept(Mockito.any(),
            ArgumentMatchers.argThat((Order x) -> x.getCustomerId().equals("C2")));
  }


  @Test
  void testUpdateWithScroll_withNoValuesInScroll() {

    Order o = Order.builder().id(1).customerId("C1").build();
    Order o2 = Order.builder().id(2).customerId("C2").build();

    ScrollableResults scrollableResults = mock(ScrollableResults.class);
    when(scrollableResults.get()).thenReturn(null);
    when(scrollableResults.next()).thenReturn(true, true, false);

    Function<ScrollParam<Order>, ScrollableResults> spiedScroll = LambdaTestUtils.spiedFunction(
        s -> scrollableResults);

    BooleanSupplier spiedUpdateNext = mock(BooleanSupplier.class);
    when(spiedUpdateNext.getAsBoolean()).thenReturn(true, false);

    BiConsumer<Order, Order> spiedUpdater = LambdaTestUtils.spiedBiConsumer((v1, v2) -> {
    });

    val updateWithScroll = UpdateWithScroll.<Order>builder()
        .scrollParam(
            ScrollParam.<Order>builder()
                .querySpec((queryRoot, query, criteriaBuilder) -> {})
                .build())
        .scroll(spiedScroll)
        .updateNext(spiedUpdateNext)
        .mutator(order -> order.setCustomerId("C2"))
        .updater(spiedUpdater).build();

    Assertions.assertFalse(updateWithScroll.apply(session));
    Mockito.verify(spiedUpdater, Mockito.times(0))
        .accept(Mockito.any(),
            Mockito.any());
  }
}
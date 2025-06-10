package io.appform.dropwizard.sharding.dao.operations.relationaldao;

import io.appform.dropwizard.sharding.dao.operations.LambdaTestUtils;
import io.appform.dropwizard.sharding.dao.operations.SelectParam;
import io.appform.dropwizard.sharding.dao.testdata.entities.Order;
import io.appform.dropwizard.sharding.dao.testdata.entities.OrderItem;
import lombok.val;
import org.hibernate.Session;
import org.hibernate.criterion.DetachedCriteria;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.Mockito;

import java.util.Collections;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Function;

class CreateOrUpdateInLockedContextTest {

    @Mock
    Session session;

    @Test
    public void testCreateOrUpdateInLockedContext_creation() {

        Function<OrderItem, OrderItem> spiedSaver = LambdaTestUtils.spiedFunction((o) -> o);
        BiConsumer<OrderItem, OrderItem> spiedUpdater = LambdaTestUtils.spiedBiConsumer(
                (o1, o2) -> {
                });

        Order o = Order.builder().id(1).customerId("C1").build();
        OrderItem oi = OrderItem.builder().id(10).order(o).name("orderitem1").build();
        OrderItem oi2 = OrderItem.builder().id(11).order(o).name("orderitem2").build();

        val createOrUpdateInLockedContext = CreateOrUpdateInLockedContext.<OrderItem, Order>builder()
                .lockedEntity(o)
                .selectParam(SelectParam.<OrderItem>builder().criteria(DetachedCriteria.forClass(
                        OrderItem.class)).build())
                .entityGenerator((o1) -> oi)
                .saver(spiedSaver)
                .updater(spiedUpdater)
                .mutator(oi1 -> oi1.setName("updatedOrderItemName"))
                .selector(sp -> Collections.emptyList())
                .build();

        Assertions.assertTrue(createOrUpdateInLockedContext.apply(session));
        Mockito.verify(spiedSaver, Mockito.times(1)).apply(Mockito.any(OrderItem.class));
        Mockito.verify(spiedUpdater, Mockito.times(0))
                .accept(Mockito.any(OrderItem.class), Mockito.any(OrderItem.class));
    }

    @Test
    public void testCreateOrUpdateInLockedContext_updation() {
        Order o = Order.builder().id(1).customerId("C1").build();
        OrderItem oi = OrderItem.builder().id(10).order(o).name("orderitem1").build();
        OrderItem oi2 = OrderItem.builder().id(11).order(o).name("orderitem2").build();

        Function<OrderItem, OrderItem> spiedSaver = LambdaTestUtils.spiedFunction((x) -> x);
        Function<Order, OrderItem> spiedEntityGenerator = LambdaTestUtils.spiedFunction((x) -> oi);

        BiConsumer<OrderItem, OrderItem> spiedUpdater = LambdaTestUtils.spiedBiConsumer(
                (o1, o2) -> {
                });

        val createOrUpdateInLockedContext = CreateOrUpdateInLockedContext.<OrderItem, Order>builder()
                .lockedEntity(o)
                .selectParam(SelectParam.<OrderItem>builder().criteria(DetachedCriteria.forClass(
                        OrderItem.class)).build())
                .entityGenerator(spiedEntityGenerator)
                .saver(spiedSaver)
                .updater(spiedUpdater)
                .mutator(oi1 -> oi1.setName("updatedOrderItemName"))
                .selector(sp -> List.of(oi, oi2))
                .build();

        Assertions.assertTrue(createOrUpdateInLockedContext.apply(session));
        Mockito.verify(spiedEntityGenerator, Mockito.times(0)).apply(Mockito.any(Order.class));
        Mockito.verify(spiedSaver, Mockito.times(0)).apply(Mockito.any(OrderItem.class));
        Mockito.verify(spiedUpdater, Mockito.times(1))
                .accept(Mockito.any(OrderItem.class),
                        ArgumentMatchers.argThat(
                                (OrderItem x) -> x.getName().equals("updatedOrderItemName")));
    }


    @Test
    public void testCreateOrUpdateInLockedContext_exceptionWhenEntityGeneratedIsNull() {
        Order o = Order.builder().id(1).customerId("C1").build();
        OrderItem oi = OrderItem.builder().id(10).order(o).name("orderitem1").build();
        OrderItem oi2 = OrderItem.builder().id(11).order(o).name("orderitem2").build();

        Function<OrderItem, OrderItem> spiedSaver = LambdaTestUtils.spiedFunction((x) -> x);
        Function<Order, OrderItem> spiedEntityGenerator = LambdaTestUtils.spiedFunction(
                (x) -> null);

        BiConsumer<OrderItem, OrderItem> spiedUpdater = LambdaTestUtils.spiedBiConsumer(
                (o1, o2) -> {
                });

        val createOrUpdateInLockedContext = CreateOrUpdateInLockedContext.<OrderItem, Order>builder()
                .lockedEntity(o)
                .selectParam(SelectParam.<OrderItem>builder().criteria(DetachedCriteria.forClass(
                        OrderItem.class)).build())
                .entityGenerator(spiedEntityGenerator)
                .saver(spiedSaver)
                .updater(spiedUpdater)
                .mutator(oi1 -> oi1.setName("updatedOrderItemName"))
                .selector(sp -> Collections.emptyList())
                .build();

        Assertions.assertThrows(RuntimeException.class,
                () -> createOrUpdateInLockedContext.apply(session));
        Mockito.verify(spiedEntityGenerator, Mockito.times(1)).apply(Mockito.any(Order.class));
        Mockito.verify(spiedSaver, Mockito.times(0)).apply(Mockito.any(OrderItem.class));
        Mockito.verify(spiedUpdater, Mockito.times(0))
                .accept(Mockito.any(OrderItem.class),
                        ArgumentMatchers.argThat(
                                (OrderItem x) -> x.getName().equals("updatedOrderItemName")));
    }
}
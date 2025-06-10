package io.appform.dropwizard.sharding.dao.operations.relationaldao;

import io.appform.dropwizard.sharding.dao.operations.LambdaTestUtils;
import io.appform.dropwizard.sharding.dao.testdata.entities.Order;
import lombok.val;
import org.hibernate.Session;
import org.hibernate.criterion.DetachedCriteria;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.Mockito;

import java.util.function.BiConsumer;
import java.util.function.Function;

class CreateOrUpdateTest {

    @Mock
    Session session;

    @Test
    public void testCreateOrUpdate_creation() {

        Function<Order, Order> spiedSaver = LambdaTestUtils.spiedFunction((o) -> o);
        BiConsumer<Order, Order> spiedUpdater = LambdaTestUtils.spiedBiConsumer((o1, o2) -> {
        });

        Order o = Order.builder().id(123).customerId("C1").build();

        val createOrUpdate = CreateOrUpdate.<Order>builder()
                .criteria(DetachedCriteria.forClass(Order.class))
                .getLockedForWrite(s -> null)
                .entityGenerator(() -> o)
                .saver(spiedSaver)
                .updater(spiedUpdater)
                .mutator(o1 -> o.setCustomerId("C2"))
                .getter(s -> o)
                .build();

        Order result = createOrUpdate.apply(session);
        Assertions.assertEquals(result, o);
        Mockito.verify(spiedSaver, Mockito.times(1)).apply(Mockito.any(Order.class));
        Mockito.verify(spiedUpdater, Mockito.times(0))
                .accept(Mockito.any(Order.class), Mockito.any(Order.class));
    }

    @Test
    public void testCreateOrUpdate_updation() {

        Function<Order, Order> spiedSaver = LambdaTestUtils.spiedFunction((o) -> o);
        BiConsumer<Order, Order> spiedUpdater = LambdaTestUtils.spiedBiConsumer((o1, o2) -> {
        });

        Order o = Order.builder().id(123).customerId("C1").build();

        val createOrUpdate = CreateOrUpdate.<Order>builder()
                .criteria(DetachedCriteria.forClass(Order.class))
                .getLockedForWrite(s -> o)
                .entityGenerator(() -> o)
                .saver(spiedSaver)
                .updater(spiedUpdater)
                .mutator(o1 -> o.setCustomerId("C2"))
                .getter(s -> o)
                .build();

        createOrUpdate.apply(session);

        Mockito.verify(spiedSaver, Mockito.times(0)).apply(Mockito.any(Order.class));
        Mockito.verify(spiedUpdater, Mockito.times(1))
                .accept(Mockito.any(Order.class),
                        ArgumentMatchers.argThat((Order x) -> x.getCustomerId().equals("C2")));
    }
}
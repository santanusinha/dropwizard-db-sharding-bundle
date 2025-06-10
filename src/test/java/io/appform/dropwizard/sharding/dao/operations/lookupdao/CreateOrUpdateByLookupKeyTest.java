package io.appform.dropwizard.sharding.dao.operations.lookupdao;

import io.appform.dropwizard.sharding.dao.operations.LambdaTestUtils;
import io.appform.dropwizard.sharding.dao.testdata.entities.Order;
import lombok.val;
import org.hibernate.Session;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.Mockito;

import java.util.function.Consumer;
import java.util.function.Function;

class CreateOrUpdateByLookupKeyTest {

    @Mock
    Session session;

    @Test
    public void testCreateOrUpdateByLookupKey_creation() {

        Function<Order, Order> spiedSaver = LambdaTestUtils.spiedFunction((o) -> o);
        Consumer<Order> spiedUpdater = LambdaTestUtils.spiedConsumer(o1 -> {
        });

        Order o = Order.builder().id(123).customerId("C1").build();

        val createOrUpdateByLookupKey = CreateOrUpdateByLookupKey.<Order>builder()
                .id("123")
                .getLockedForWrite(s -> null)
                .entityGenerator(() -> o)
                .saver(spiedSaver)
                .updater(spiedUpdater)
                .mutator(o1 -> o.setCustomerId("C2"))
                .getter(s -> o)
                .build();

        createOrUpdateByLookupKey.apply(session);
        Mockito.verify(spiedSaver, Mockito.times(1)).apply(Mockito.any(Order.class));
        Mockito.verify(spiedUpdater, Mockito.times(0)).accept(Mockito.any(Order.class));
    }

    @Test
    public void testCreateOrUpdateByLookupKey_updation() {

        Function<Order, Order> spiedSaver = LambdaTestUtils.spiedFunction((o) -> o);
        Consumer<Order> spiedUpdater = LambdaTestUtils.spiedConsumer(o1 -> {
        });

        Order o = Order.builder().id(123).customerId("C1").build();

        val createOrUpdateByLookupKey = CreateOrUpdateByLookupKey.<Order>builder()
                .id("123")
                .getLockedForWrite(s -> o)
                .entityGenerator(() -> o)
                .saver(spiedSaver)
                .updater(spiedUpdater)
                .mutator(o1 -> o.setCustomerId("C2"))
                .getter(s -> o)
                .build();

        createOrUpdateByLookupKey.apply(session);

        Mockito.verify(spiedSaver, Mockito.times(0)).apply(Mockito.any(Order.class));
        Mockito.verify(spiedUpdater, Mockito.times(1)).accept(Mockito.any(Order.class));
    }
}
package io.appform.dropwizard.sharding.dao.operations.lockedcontext;

import io.appform.dropwizard.sharding.dao.operations.LambdaTestUtils;
import io.appform.dropwizard.sharding.dao.testdata.entities.Order;
import lombok.val;
import org.hibernate.Session;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.Mockito;

import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

public class LockAndExecuteTest {

    @Mock
    Session session;

    @Test
    public void testInsertMode_WithOperations() {
        Order o = Order.builder().id(123).customerId("C1").build();

        Function<Order, Order> spiedSaver = LambdaTestUtils.spiedFunction((x) -> x);
        Consumer<Order> spiedOperation = LambdaTestUtils.spiedConsumer((x) -> {
        });

        val lockAndExecute = LockAndExecute.<Order>buildForInsert()
                .entity(o)
                .saver(spiedSaver)
                .build();

        lockAndExecute.getOperations().add(spiedOperation);

        Assertions.assertEquals(o, lockAndExecute.apply(session));
        Mockito.verify(spiedSaver, Mockito.times(1)).apply(Mockito.any(Order.class));
        Mockito.verify(spiedOperation, Mockito.times(1)).accept(Mockito.any(Order.class));
    }

    @Test
    public void testReadMode_WithOperations() {
        Order o = Order.builder().id(123).customerId("C1").build();

        Supplier<Order> spiedGetter = LambdaTestUtils.spiedSupplier(() -> o);
        Consumer<Order> spiedOperation = LambdaTestUtils.spiedConsumer((x) -> {
        });

        val lockAndExecute = LockAndExecute.<Order>buildForRead()
                .getter(spiedGetter)
                .build();

        lockAndExecute.getOperations().add(spiedOperation);

        Assertions.assertEquals(o, lockAndExecute.apply(session));
        Mockito.verify(spiedGetter, Mockito.times(1)).get();
        Mockito.verify(spiedOperation, Mockito.times(1)).accept(Mockito.any(Order.class));
    }


    @Test
    public void testReadMode_EntityNotPresent() {
        Order o = Order.builder().id(123).customerId("C1").build();

        Supplier<Order> spiedGetter = LambdaTestUtils.spiedSupplier(() -> null);
        Consumer<Order> spiedOperation = LambdaTestUtils.spiedConsumer((x) -> {
        });

        val lockAndExecute = LockAndExecute.<Order>buildForRead()
                .getter(spiedGetter)
                .build();

        lockAndExecute.getOperations().add(spiedOperation);

        try {
            Order result = lockAndExecute.apply(session);
        } catch (Exception e) {
            Assertions.assertEquals(e.getClass(), RuntimeException.class);
            Assertions.assertEquals(e.getMessage(), "Entity doesn't exist");
        }
        Mockito.verify(spiedGetter, Mockito.times(1)).get();
        Mockito.verify(spiedOperation, Mockito.times(0)).accept(Mockito.any(Order.class));
    }

}
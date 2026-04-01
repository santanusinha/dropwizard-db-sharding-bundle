package io.appform.dropwizard.sharding.dao.operations.lookupdao;

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

class GetAndUpdateByLookupKeyTest {

	@Mock
	Session session;

	@Test
	void testGetAndUpdateByLookupKey_entityPresent() {

		Order o = Order.builder().id(123).customerId("C1").build();

		Function<String, Order> spiedGetter = LambdaTestUtils.spiedFunction(s -> o);
		Consumer<Order> spiedUpdater = LambdaTestUtils.spiedConsumer(o1 -> {
		});

		val createOrUpdateByLookupKey = GetAndUpdateByLookupKey.<Order>builder().id("123")
				.getter(spiedGetter).mutator(o1 -> o.setCustomerId("C2")).updater(spiedUpdater).build();

		Assertions.assertTrue(createOrUpdateByLookupKey.apply(session));
		Mockito.verify(spiedUpdater, Mockito.times(1)).accept(Mockito.any(Order.class));
	}

	@Test
	void testGetAndUpdateByLookupKey_entityNotPresent() {

		Order o = Order.builder().id(123).customerId("C1").build();

		Function<String, Order> spiedGetter = LambdaTestUtils.spiedFunction(s -> null);
		Consumer<Order> spiedUpdater = LambdaTestUtils.spiedConsumer(o1 -> {
		});

		val getAndUpdateByLookupKey = GetAndUpdateByLookupKey.<Order>builder().id("123")
				.getter(spiedGetter).mutator(o1 -> null).updater(spiedUpdater).build();

		Assertions.assertFalse(getAndUpdateByLookupKey.apply(session));
		Mockito.verify(spiedUpdater, Mockito.times(0)).accept(Mockito.any(Order.class));
	}

}
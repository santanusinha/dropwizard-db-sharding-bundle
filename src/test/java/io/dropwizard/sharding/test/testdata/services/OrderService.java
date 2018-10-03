package io.dropwizard.sharding.test.testdata.services;

import io.dropwizard.sharding.test.testdata.entities.Order;

/**
 * Created on 03/10/18
 */
public interface OrderService {
    Order createOrder(Order order);

    Order getOrder(long orderId);
}

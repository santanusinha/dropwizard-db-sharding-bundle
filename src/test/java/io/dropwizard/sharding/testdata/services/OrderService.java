package io.dropwizard.sharding.testdata.services;

import io.dropwizard.sharding.testdata.entities.Order;

/**
 * Created on 03/10/18
 */
public interface OrderService {
    Order createOrder(Order order);

    Order getOrder(long orderId);
}

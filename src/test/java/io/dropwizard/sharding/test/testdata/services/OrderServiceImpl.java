package io.dropwizard.sharding.test.testdata.services;

import io.dropwizard.hibernate.UnitOfWork;
import io.dropwizard.sharding.test.testdata.dao.OrderDao;
import io.dropwizard.sharding.test.testdata.entities.Order;
import lombok.RequiredArgsConstructor;

import javax.inject.Inject;

/**
 * Test service. Skipping DTOs to keep test light.
 */
@RequiredArgsConstructor(onConstructor = @__(@Inject))
public class OrderServiceImpl implements OrderService {

    private final OrderDao orderDao;

    @Override
    @UnitOfWork
    public Order createOrder(Order order) {
        return orderDao.save(order);
    }

    @Override
    @UnitOfWork
    public Order getOrder(long orderId) {
        return orderDao.get(orderId);
    }
}

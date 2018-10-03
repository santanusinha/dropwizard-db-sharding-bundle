package io.dropwizard.sharding.application;

import com.codahale.metrics.annotation.ExceptionMetered;
import com.codahale.metrics.annotation.Timed;
import io.dropwizard.sharding.testdata.entities.Order;
import io.dropwizard.sharding.testdata.services.OrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.security.PermitAll;
import javax.inject.Inject;
import javax.validation.constraints.NotNull;
import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;

/**
 * Test resource. Skipping DTOs to keep test light.
 */
@Path("v0.1/orders")
@Produces(value = {MediaType.APPLICATION_JSON})
@RequiredArgsConstructor(onConstructor = @__(@Inject))
@Slf4j
public class TestResource {
    private final OrderService orderService;

    @PUT
    @Timed
    @ExceptionMetered
    @Path("")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @PermitAll
    public Order createOrUpdateInvoice(@NotNull Order order) {
        return orderService.createOrder(order);
    }

    @GET
    @Timed
    @ExceptionMetered
    @Path("{id}")
    @Produces(MediaType.APPLICATION_JSON)
    @PermitAll
    public Order getOrder(@PathParam("id") long id) {
        return orderService.getOrder(id);
    }
}

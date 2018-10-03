package io.dropwizard.sharding.test.application;

import io.dropwizard.sharding.providers.ShardKeyProvider;
import lombok.RequiredArgsConstructor;

import javax.annotation.Priority;
import javax.ws.rs.Priorities;
import javax.ws.rs.container.ContainerRequestContext;
import javax.ws.rs.container.ContainerRequestFilter;
import java.io.IOException;

/**
 * Created on 03/10/18
 */
@Priority(Priorities.AUTHENTICATION + 1)
@RequiredArgsConstructor
public class ShardKeyFilter implements ContainerRequestFilter {

    private final ShardKeyProvider shardKeyProvider;

    @Override
    public void filter(ContainerRequestContext requestContext) throws IOException {
        // Using customer-id as header as well (for this test's purpose)
        final String customerId = requestContext.getHeaderString("X-Auth-Token");
        shardKeyProvider.setKey(customerId);
    }
}

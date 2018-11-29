package in.cleartax.dropwizard.sharding.application;

import in.cleartax.dropwizard.sharding.providers.ShardKeyProvider;
import lombok.RequiredArgsConstructor;

import javax.annotation.Priority;
import javax.ws.rs.Priorities;
import javax.ws.rs.container.ContainerRequestContext;
import javax.ws.rs.container.ContainerResponseContext;
import javax.ws.rs.container.ContainerResponseFilter;

/**
 * Created on 29/11/18
 */
@Priority(Priorities.AUTHENTICATION + 1)
@RequiredArgsConstructor
public class ShardKeyResponseFilter implements ContainerResponseFilter {
    private final ShardKeyProvider shardKeyProvider;

    @Override
    public void filter(ContainerRequestContext requestContext, ContainerResponseContext responseContext) {
        shardKeyProvider.clear();
    }
}

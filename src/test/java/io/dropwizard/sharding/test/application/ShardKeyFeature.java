package io.dropwizard.sharding.test.application;

import io.dropwizard.sharding.providers.ShardKeyProvider;
import lombok.RequiredArgsConstructor;

import javax.inject.Inject;
import javax.ws.rs.container.DynamicFeature;
import javax.ws.rs.container.ResourceInfo;
import javax.ws.rs.core.FeatureContext;
import javax.ws.rs.ext.Provider;

/**
 * Created on 03/10/18
 */
@Provider
@RequiredArgsConstructor(onConstructor = @__(@Inject))
public class ShardKeyFeature implements DynamicFeature {

    private final ShardKeyProvider shardKeyProvider;

    @Override
    public void configure(ResourceInfo resourceInfo, FeatureContext context) {
        context.register(new ShardKeyFilter(shardKeyProvider));
    }
}

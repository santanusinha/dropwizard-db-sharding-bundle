package io.appform.dropwizard.sharding.config;

import com.google.common.collect.Maps;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Builder.Default;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * {@summary Config for a tenanted shards hibernate factory.
 * <ul>
 * <li>tenants : This holds the ShardedHibernateFactory configuration in a map keyed by Tenant Id.</li>
 * </ul>}
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class MultiTenantShardedHibernateFactory {

    @Default
    private Map<String, ShardedHibernateFactory> tenants = Maps.newHashMap();

    /**
     * {@summary Get the ShardedHibernateFactory configuration for the given tenantId.}
     *
     * @param tenantId Tenant Id
     * @return ShardedHibernateFactory
     */
    public ShardedHibernateFactory config(final String tenantId) {
        return tenants.get(tenantId);
    }
}

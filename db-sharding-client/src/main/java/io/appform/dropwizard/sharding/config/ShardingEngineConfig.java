package io.appform.dropwizard.sharding.config;

import io.appform.dropwizard.sharding.sharding.NoopShardBlacklistingStore;
import io.appform.dropwizard.sharding.sharding.ShardBlacklistingStore;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Top-level configuration for {@link io.appform.dropwizard.sharding.ShardingEngine}.
 * Framework-agnostic — no Dropwizard types.
 *
 * <p>Example usage:</p>
 * <pre>{@code
 * ShardingEngineConfig config = ShardingEngineConfig.builder()
 *     .tenantShards(Map.of("default", List.of(
 *         ShardConfig.builder().url("jdbc:mysql://localhost/shard0")
 *             .driverClass("com.mysql.cj.jdbc.Driver")
 *             .dialect("org.hibernate.dialect.MySQL8Dialect")
 *             .username("root").password("secret").build(),
 *         ShardConfig.builder().url("jdbc:mysql://localhost/shard1")
 *             .driverClass("com.mysql.cj.jdbc.Driver")
 *             .dialect("org.hibernate.dialect.MySQL8Dialect")
 *             .username("root").password("secret").build()
 *     )))
 *     .entities(List.of(Order.class, OrderItem.class))
 *     .build();
 * }</pre>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ShardingEngineConfig {

    /**
     * Map of tenantId → list of shard configs. For single-tenant usage, use a single entry
     * with key "default".
     */
    @Builder.Default
    private Map<String, List<ShardConfig>> tenantShards = new HashMap<>();

    /**
     * JPA entity classes to register with Hibernate.
     */
    @Builder.Default
    private List<Class<?>> entities = new ArrayList<>();

    /**
     * Sharding options (read-only transactions, encryption, etc.).
     */
    @Builder.Default
    private ShardingBundleOptions shardingOptions = new ShardingBundleOptions();

    /**
     * Store for shard blacklisting. Defaults to no-op (blacklisting disabled).
     */
    @Builder.Default
    private ShardBlacklistingStore blacklistingStore = new NoopShardBlacklistingStore();

    /**
     * Shard routing strategy. Defaults to BALANCED (1024 buckets).
     */
    @Builder.Default
    private ShardType shardType = ShardType.BALANCED;

    /**
     * Optional metric config for transaction metrics.
     */
    @Builder.Default
    private MetricConfig metricConfig = new MetricConfig();
}


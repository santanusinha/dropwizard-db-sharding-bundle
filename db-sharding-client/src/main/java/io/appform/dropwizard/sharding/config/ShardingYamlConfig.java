package io.appform.dropwizard.sharding.config;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * YAML-friendly configuration for sharding. This is what gets deserialized from a {@code local.yml} file.
 * <p>
 * Entity classes and blacklisting store cannot be expressed in YAML, so they are provided
 * separately when creating the {@link io.appform.dropwizard.sharding.ShardingEngine}.
 *
 * <p>Example YAML ({@code local.yml}):</p>
 * <pre>
 * shardType: BALANCED
 * tenantShards:
 *   default:
 *     - url: jdbc:mysql://localhost:3306/shard0
 *       driverClass: com.mysql.cj.jdbc.Driver
 *       dialect: org.hibernate.dialect.MySQL8Dialect
 *       username: root
 *       password: secret
 *     - url: jdbc:mysql://localhost:3306/shard1
 *       driverClass: com.mysql.cj.jdbc.Driver
 *       dialect: org.hibernate.dialect.MySQL8Dialect
 *       username: root
 *       password: secret
 * shardingOptions:
 *   skipReadOnlyTransaction: false
 * </pre>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ShardingYamlConfig {

    /**
     * Map of tenantId → list of shard configs.
     */
    @Builder.Default
    private Map<String, List<ShardConfig>> tenantShards = new HashMap<>();

    /**
     * Shard routing strategy. Defaults to BALANCED.
     */
    @Builder.Default
    private ShardType shardType = ShardType.BALANCED;

    /**
     * Sharding options.
     */
    @Builder.Default
    private ShardingBundleOptions shardingOptions = new ShardingBundleOptions();

    /**
     * Optional metric config.
     */
    @Builder.Default
    private MetricConfig metricConfig = new MetricConfig();
}


sharpackage io.appform.dropwizard.sharding.config;

/**
 * Determines the shard-routing strategy.
 */
public enum ShardType {
    /**
     * Uses 1024 buckets, perfectly balanced across power-of-2 shard counts. Recommended for new projects.
     */
    BALANCED,

    /**
     * Uses 1000 buckets. Kept for backward compatibility with existing data.
     */
    LEGACY
}


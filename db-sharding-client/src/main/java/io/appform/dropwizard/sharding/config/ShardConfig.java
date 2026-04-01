package io.appform.dropwizard.sharding.config;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.HashMap;
import java.util.Map;

/**
 * Configuration for a single database shard. Framework-agnostic — uses plain JDBC strings.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ShardConfig {

    /**
     * JDBC connection URL. Example: "jdbc:mysql://localhost:3306/shard0"
     */
    private String url;

    /**
     * JDBC driver class name. Example: "com.mysql.cj.jdbc.Driver"
     */
    private String driverClass;

    /**
     * Hibernate dialect. Example: "org.hibernate.dialect.MySQL8Dialect"
     */
    private String dialect;

    /**
     * Database username.
     */
    private String username;

    /**
     * Database password.
     */
    private String password;

    /**
     * Hibernate hbm2ddl.auto strategy. Defaults to "validate".
     */
    @Builder.Default
    private String hbm2ddl = "validate";

    /**
     * Additional Hibernate properties. These override any defaults set by the core builder.
     */
    @Builder.Default
    private Map<String, String> properties = new HashMap<>();
}


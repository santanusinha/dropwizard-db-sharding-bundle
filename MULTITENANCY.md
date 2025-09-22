# Dropwizard DB Sharding Bundle Multi Tenancy Support

Multi tenancy support for sharded databases built on top of the same time and scale tested db sharding framework.

## Background
More and more platform services that are built using the db sharding framework that is being used by a multitude of businesses are required to isolate data that is being stored for different businesses. 
This is where multi-tenancy support comes into play. The db sharding framework is extended to support multi tenancy by adding a tenant id to the same sharding configuration that we are familiar with.
This allows for asymmetric shard configuration per tenant and also allows for tenant-specific sharding options. 

## Bundle Initialization
* ```MultiTenantDBShardingBundle``` is the bundle class for initializing the multi-tenancy support with LegacyShardManager.
* ```MultiTenantBalancedDBShardingBundle``` is the bundle class for initializing the multi-tenancy support with BalancedShardManager.

### Example
* ```MultiTenantDBShardingBundle```
```java
MultiTenantDBShardingBundle<ApplicationConfiguration> bundle = new MultiTenantDBShardingBundle<>(Order.class, OrderItem.class) {
      @Override
      protected MultiTenantShardedHibernateFactory getConfig(ApplicationConfiguration config) {
        return config.getDatabaseConfig();
      }
    };
```
* ```MultiTenantBalancedDBShardingBundle```
```java
MultiTenantBalancedDBShardingBundle<ApplicationConfiguration> bundle = new MultiTenantBalancedDBShardingBundle<>(Order.class, OrderItem.class) {
      @Override
      protected MultiTenantShardedHibernateFactory getConfig(ApplicationConfiguration config) {
        return config.getDatabaseConfig();
      }
    };
```

## Types of Multi Tenant DAOs supported
* ```MultiTenantRelationalDao```
* ```MultiTenantLookupDao```
* ```MultiTenantCacheableLookupDao```
* ```MultiTenantCacheableRelationalDao```

## Example Configuration
```yaml
databaseConfig:
  tenants:
    tenant1:
      shards:
        - driverClass: org.postgresql.Driver
          user: pg-user
          password: iAMs00perSecrEET
          url: jdbc:postgresql://db.example.com/tenant1_shard1
          properties:
            charSet: UTF-8
            hibernate.dialect: org.hibernate.dialect.PostgreSQLDialect
          maxWaitForConnection: 1s
          validationQuery: "/* MyApplication Health Check */ SELECT 1"
          minSize: 8
          maxSize: 32
          checkConnectionWhileIdle: false
        - driverClass: org.postgresql.Driver
          user: pg-user
          password: iAMs00perSecrEET
          url: jdbc:postgresql://db.example.com/tenant1_shard2
          properties:
            charSet: UTF-8
            hibernate.dialect: org.hibernate.dialect.PostgreSQLDialect
          maxWaitForConnection: 1s
          validationQuery: "/* MyApplication Health Check */ SELECT 1"
          minSize: 8
          maxSize: 32
          checkConnectionWhileIdle: false
        - driverClass: org.postgresql.Driver
          user: pg-user
          password: iAMs00perSecrEET
          url: jdbc:postgresql://db.example.com/tenant1_shard3
          properties:
            charSet: UTF-8
            hibernate.dialect: org.hibernate.dialect.PostgreSQLDialect
          maxWaitForConnection: 1s
          validationQuery: "/* MyApplication Health Check */ SELECT 1"
          minSize: 8
          maxSize: 32
          checkConnectionWhileIdle: false
        - driverClass: org.postgresql.Driver
          user: pg-user
          password: iAMs00perSecrEET
          url: jdbc:postgresql://db.example.com/tenant1_shard4
          properties:
            charSet: UTF-8
            hibernate.dialect: org.hibernate.dialect.PostgreSQLDialect
          maxWaitForConnection: 1s
          validationQuery: "/* MyApplication Health Check */ SELECT 1"
          minSize: 8
          maxSize: 32
          checkConnectionWhileIdle: false
    tenant2:
      shards:
        - driverClass: org.postgresql.Driver
          user: pg-user
          password: iAMs00perSecrEET
          url: jdbc:postgresql://db.example.com/tenant2_shard1
          properties:
            charSet: UTF-8
            hibernate.dialect: org.hibernate.dialect.PostgreSQLDialect
          maxWaitForConnection: 1s
          validationQuery: "/* MyApplication Health Check */ SELECT 1"
          minSize: 8
          maxSize: 32
          checkConnectionWhileIdle: false
        - driverClass: org.postgresql.Driver
          user: pg-user
          password: iAMs00perSecrEET
          url: jdbc:postgresql://db.example.com/tenant2_shard2
          properties:
            charSet: UTF-8
            hibernate.dialect: org.hibernate.dialect.PostgreSQLDialect
          maxWaitForConnection: 1s
          validationQuery: "/* MyApplication Health Check */ SELECT 1"
          minSize: 8
          maxSize: 32
          checkConnectionWhileIdle: false
```

# NOTE
- All multi-tenant dao APIs are exactly the same as the non-multi-tenant dao APIs but expect an additional tenant id for tenant selection. 

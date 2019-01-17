# Dropwizard DB Sharding Bundle

This library adds support for Database sharding in [Dropwizard](https://www.dropwizard.io) based applications.
Make sure you're familiar with Dropwizard, dependency injection framework like [Guice](https://github.com/google/guice) and concepts like [ThreadLocals](https://docs.oracle.com/javase/7/docs/api/java/lang/ThreadLocal.html) before going ahead.

**License:** [Apache 2.0](http://www.apache.org/licenses/LICENSE-2.0)

**Stable version:** [0.2.16](https://mvnrepository.com/artifact/in.cleartax.dropwizard/sharding-core)

## Why this library?
1. Traditionally in Dropwizard, to open a database transaction ```@UnitOfWork``` is put on every [Jersey](https://jersey.github.io/)-resource method. In case ```@UnitOfWork``` is required at places, other than Jersey-resources a more verbose approach is required as mentiond [here](https://github.com/dropwizard/dropwizard/pull/1361).
2. Nested ```@UnitOfWork``` don't work in Dropwizard, which are some times needed in a sharding use-case.
3. Dropwizard doesn't support Hibernate's multi-tenancy.
4. The [original library](https://github.com/santanusinha/dropwizard-db-sharding-bundle) from where this library has been forked, advocates that database-transaction should only be managed at DAO layer, while it may work in their use-case, for Cleartax being a finance system, clean rollback of transaction (which may include multiple entities in some cases) is very important.

This library solves above problems by:
1. Using [Guice method interceptors](https://github.com/google/guice/wiki/AOP#example-forbidding-method-calls-on-weekends) to seamlessly use ```@UnitOfWork``` in anywhere in the code. 
*Assumption:* Methods that are annotated with ```@UnitOfWork``` aren't private and the class must be created via Guice dependency injection.
2. Handle nested ```@UnitOfWork``` within the same thread. [How?](https://github.com/ClearTax/dropwizard-db-sharding-bundle/commit/0f8fc581ebf340c3bcd2a0907539b36714af6b34)
3. Uses Hibernate's multi-tenancy support and integrates it with Dropwizard.

## How to Use 

### Terminology
1. Shard/Tenant mean the same thing, which is the physical database.
2. Shard-id/tenant-id also mean the same thing, which is the id of the physical database. (Refer point no. 2 in High-level section)
3. Shard-key is the ID on which data is sharded, e.g. if you're sharding by user-id, then user-id becomes your shard-key.
4. Bucket is an intermediate virtual-shard to which shard-key gets mapped to.
5. Bucket-id gets mapped to the shard-id.

### High level
1. Include as dependency:
```
<dependency>
    <groupId>in.cleartax.dropwizard</groupId>
    <artifactId>sharding-core</artifactId>
    <version>0.2.8</version>
</dependency>
```

for liquibase migration support, you can also include:
```
<dependency>
    <groupId>in.cleartax.dropwizard</groupId>
    <artifactId>sharding-migrations</artifactId>
    <version>0.2.8</version>
</dependency>
```

2. Update your Dropwizard YML config to declare all the hosts as [described here](https://github.com/ClearTax/dropwizard-db-sharding-bundle/blob/43f3355ee6/sharding-example/src/test/resources/test_with_multitenant.yml#L11).

3. Understand Guice's [AbstractModule](https://github.com/google/guice/wiki/GettingStarted) and [@Provides](https://github.com/google/guice/wiki/ProvidesMethods).

4. Define all the dependencies in an extension to ```AbstractModule``` that binds all your classes. e.g. [refer this](https://github.com/ClearTax/dropwizard-db-sharding-bundle/blob/master/sharding-example/src/main/java/in/cleartax/dropwizard/sharding/application/TestModule.java).

5. Register your module in your Dropwizard application as [described here](https://github.com/ClearTax/dropwizard-db-sharding-bundle/blob/master/sharding-example/src/main/java/in/cleartax/dropwizard/sharding/application/TestApplication.java#L43).

### Low level
Consider [this method](https://github.com/ClearTax/dropwizard-db-sharding-bundle/blob/fd0e46ed71/sharding-example/src/main/java/in/cleartax/dropwizard/sharding/application/TestResource.java#L75) which is annotated with ```@UnitOfWork```.

[UnitOfWorkInterceptor](https://github.com/ClearTax/dropwizard-db-sharding-bundle/blob/fd0e46ed71/sharding-core/src/main/java/in/cleartax/dropwizard/sharding/transactions/UnitOfWorkModule.java#L48) by using Guice's AOP would intercept the method call and figure out the shard-id/tenant-id then initiate the transaction. For this to work, you would need to do:

**Map all the shard-keys to bucket**

```UnitOfWorkInterceptor``` calls the implementation of [ShardKeyProvider](https://github.com/ClearTax/dropwizard-db-sharding-bundle/blob/0f8fc581eb/sharding-core/src/main/java/in/cleartax/dropwizard/sharding/providers/ShardKeyProvider.java) to map the shard-key to a bucket.

**Map all the buckets to shards**

```UnitOfWorkInterceptor``` calls the implementation of [BucketResolver](https://github.com/ClearTax/dropwizard-db-sharding-bundle/blob/0f8fc581eb/sharding-core/src/main/java/in/cleartax/dropwizard/sharding/resolvers/bucket/BucketResolver.java) to figure out shard-id. Refer [DbBasedShardResolver](https://github.com/ClearTax/dropwizard-db-sharding-bundle/blob/0f8fc581eb/sharding-core/src/main/java/in/cleartax/dropwizard/sharding/utils/resolvers/shard/DbBasedShardResolver.java) to understand one example use-case.
In [this example, refer this SQL script](https://github.com/ClearTax/dropwizard-db-sharding-bundle/blob/0f8fc581eb/sharding-example/src/test/resources/default_shard_config.sql#L31) where the mappings are done.

**Connect to right shard, by**
1. **Setup shard-key for every incoming HTTP request** - Refer [ShardKeyFeature](https://github.com/ClearTax/dropwizard-db-sharding-bundle/blob/fd0e46ed71/sharding-example/src/main/java/in/cleartax/dropwizard/sharding/application/ShardKeyFeature.java) in the example project. Note: ```ShardKeyProvider``` in the example is bounded to it's implementation in the [Guice module](https://github.com/ClearTax/dropwizard-db-sharding-bundle/blob/0f8fc581ebf340c3bcd2a0907539b36714af6b34/sharding-example/src/main/java/in/cleartax/dropwizard/sharding/application/TestModule.java#L60) described earlier.

2. **Setup shard-key manually** - Get an instance of ```ShardKeyProvider``` and then do:
```java
try {
  shardKeyProvider.setKey("shard-key")
  // Call your method which is annotated with @UnitOfWork
} finally {
  shardKeyProvider.clear();
}
```
Example use-case: In case you're invoking your code outside of HTTP layer, or you're creating a child-thread which may not have all the context of parent.

3. **Connect to a shard by explicitly mentioning shard-id**
```java
try {
  DelegatingTenantResolver.getInstance().setDelegate(new ConstTenantIdentifierResolver("your shard-id/tenant-id"));
  // Call your method which is annotated with @UnitOfWork
} finally {
  if (DelegatingTenantResolver.getInstance().hasTenantIdentifier()) {
      DelegatingTenantResolver.getInstance().setDelegate(null);
  }
}
```
Example use-case: This might be useful in case you're aggregating data from across all the shards

4. **Connect to a shard without using @UnitOfWork**

This is not recommended because then you'll need hibernate specific objects in your business code. In case required, you can instantiate [TransactionRunner](https://github.com/ClearTax/dropwizard-db-sharding-bundle/blob/master/sharding-core/src/main/java/in/cleartax/dropwizard/sharding/transactions/TransactionRunner.java) and use it as [mentioned here](https://github.com/ClearTax/dropwizard-db-sharding-bundle/blob/0f8fc581eb/sharding-core/src/main/java/in/cleartax/dropwizard/sharding/transactions/UnitOfWorkModule.java#L79)


**Note:** In case you don't need sharding but still need flexibility of using ```@UnitOfWork``` outside of resources and ability to use nested ```@UnitOfWork``` you can still do so. Refer [these tests](https://github.com/ClearTax/dropwizard-db-sharding-bundle/blob/master/sharding-example/src/test/java/in/cleartax/dropwizard/sharding/test/sampleapp/TestSuiteWithoutMultiTenancy.java) to understand the use-case.

package io.appform.dropwizard.sharding;


import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class ShardInfoProviderTest {

    @Test
    public void testGetShardId() {
        ShardInfoProvider shardInfoProvider = new ShardInfoProvider("default");
        int shardId = shardInfoProvider.shardId("connectionpool-default-2");
        Assertions.assertEquals(2, shardId);

        shardId = shardInfoProvider.shardId("connectionpool");
        Assertions.assertEquals(-1, shardId);

        shardId = shardInfoProvider.shardId("connectionpool-test-1");
        Assertions.assertEquals(1, shardId);

        shardId = shardInfoProvider.shardId("connectionpool-1");
        Assertions.assertEquals(-1, shardId);

        shardId = shardInfoProvider.shardId("default-1");
        Assertions.assertEquals(-1, shardId);
    }

    @Test
    public void testGetNamespace() {
        ShardInfoProvider shardInfoProvider = new ShardInfoProvider("default");
        String namespace = shardInfoProvider.namespace("connectionpool-default-2");
        Assertions.assertEquals("default", namespace);

        namespace = shardInfoProvider.namespace("connectionpool");
        Assertions.assertNull(namespace);

        namespace = shardInfoProvider.namespace("connectionpool-default");
        Assertions.assertNull(namespace);

        namespace = shardInfoProvider.namespace("connectionpool-test-1");
        Assertions.assertEquals("test", namespace);
    }

    @Test
    public void testGetShardName() {
        ShardInfoProvider shardInfoProvider = new ShardInfoProvider("default");
        String shardName = shardInfoProvider.shardName(1);
        Assertions.assertEquals("connectionpool-default-1", shardName);
    }
}

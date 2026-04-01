package io.appform.dropwizard.sharding;


import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

public class ShardInfoProviderTest {

    @Test
    public void testGetShardId() {
        ShardInfoProvider shardInfoProvider = new ShardInfoProvider("default");
        int shardId = shardInfoProvider.shardId("connectionpool-default-2");
        assertEquals(2, shardId);

        shardId = shardInfoProvider.shardId("connectionpool");
        assertEquals(-1, shardId);

        shardId = shardInfoProvider.shardId("connectionpool-test-1");
        assertEquals(1, shardId);

        shardId = shardInfoProvider.shardId("connectionpool-1");
        assertEquals(-1, shardId);

        shardId = shardInfoProvider.shardId("default-1");
        assertEquals(-1, shardId);
    }

    @Test
    public void testGetNamespace() {
        ShardInfoProvider shardInfoProvider = new ShardInfoProvider("default");
        String namespace = shardInfoProvider.namespace("connectionpool-default-2");
        assertEquals("default", namespace);

        namespace = shardInfoProvider.namespace("connectionpool");
        assertNull(namespace);

        namespace = shardInfoProvider.namespace("connectionpool-default");
        assertNull(namespace);

        namespace = shardInfoProvider.namespace("connectionpool-test-1");
        assertEquals("test", namespace);
    }

    @Test
    public void testGetShardName() {
        ShardInfoProvider shardInfoProvider = new ShardInfoProvider("default");
        String shardName = shardInfoProvider.shardName(1);
        assertEquals("connectionpool-default-1", shardName);
    }
}

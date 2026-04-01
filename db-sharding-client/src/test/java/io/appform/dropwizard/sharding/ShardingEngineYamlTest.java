package io.appform.dropwizard.sharding;

import io.appform.dropwizard.sharding.dao.LookupDao;
import io.appform.dropwizard.sharding.testdata.SimpleTestEntity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests that {@link ShardingEngine} can be bootstrapped from a YAML config file (local.yml).
 */
class ShardingEngineYamlTest {

    private ShardingEngine engine;

    @AfterEach
    void tearDown() {
        if (engine != null) {
            engine.stop();
        }
    }

    @Test
    void testFromYamlInputStream() {
        InputStream yamlStream = getClass().getResourceAsStream("/local.yml");
        assertNotNull(yamlStream, "local.yml should be on classpath");

        engine = ShardingEngine.fromYaml(yamlStream, SimpleTestEntity.class);
        assertNotNull(engine);
        assertEquals(2, engine.getSessionFactories().get("default").size(),
                "Should have 2 shards");
    }

    @Test
    void testFromYamlSaveAndGet() throws Exception {
        InputStream yamlStream = getClass().getResourceAsStream("/local.yml");
        engine = ShardingEngine.fromYaml(yamlStream, SimpleTestEntity.class);

        LookupDao<SimpleTestEntity> dao = engine.createLookupDao(SimpleTestEntity.class);
        assertNotNull(dao);

        // Save an entity
        SimpleTestEntity entity = SimpleTestEntity.builder()
                .externalId("test-id-1")
                .value("hello")
                .build();
        Optional<SimpleTestEntity> saved = dao.save(entity);
        assertTrue(saved.isPresent());
        assertEquals("test-id-1", saved.get().getExternalId());

        // Get it back
        Optional<SimpleTestEntity> fetched = dao.get("test-id-1");
        assertTrue(fetched.isPresent());
        assertEquals("hello", fetched.get().getValue());
    }

    @Test
    void testFromYamlStringPath() {
        String path = getClass().getResource("/local.yml").getFile();
        engine = ShardingEngine.fromYaml(path, SimpleTestEntity.class);
        assertNotNull(engine);
        assertEquals(2, engine.getSessionFactories().get("default").size());
    }
}


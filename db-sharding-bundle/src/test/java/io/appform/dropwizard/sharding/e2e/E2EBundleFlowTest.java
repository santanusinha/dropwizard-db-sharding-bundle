/*
 * Copyright 2026 Santanu Sinha <santanu.sinha@gmail.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package io.appform.dropwizard.sharding.e2e;

import com.codahale.metrics.Meter;
import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.Timer;
import com.codahale.metrics.health.HealthCheck;
import io.appform.dropwizard.sharding.dao.testdata.entities.TestEntity;
import io.dropwizard.configuration.ResourceConfigurationSourceProvider;
import io.dropwizard.testing.junit5.DropwizardAppExtension;
import io.dropwizard.testing.junit5.DropwizardExtensionsSupport;
import io.dropwizard.core.setup.Environment;
import lombok.val;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end test: boots the real application (Jetty, Jackson YAML config parsing,
 * validation) with the sharding bundle over two in-memory H2 shards, then asserts
 * DAO flow over HTTP, transaction metrics, health checks and admin task registration.
 *
 * <p>Covers the paths no other test reaches: full {@code Application.run()} lifecycle,
 * YAML deserialization of {@link io.appform.dropwizard.sharding.config.ShardedHibernateFactory},
 * and {@code TransactionMetricObserver} wiring into the environment metric registry.</p>
 */
@ExtendWith(DropwizardExtensionsSupport.class)
class E2EBundleFlowTest {

    private static final DropwizardAppExtension<E2ETestConfig> APP =
            new DropwizardAppExtension<>(E2ETestApplication.class, "e2e/valid-config.yml",
                    new ResourceConfigurationSourceProvider());

    private static final String BASE_URL = "http://localhost:%d/e2e";

    @Test
    void serverBootsAndServesRequests() {
        val port = APP.getLocalPort();
        assertEquals(200, put(port, "key1", "hello"));
        assertEquals(200, get(port, "key1"));
        assertEquals("hello", getBody(port, "key1"));
    }

    @Test
    void transactionMetricsAreRecordedForSaveAndGet() {
        // warm up: one write and one read through the HTTP path
        val port = APP.getLocalPort();
        put(port, "metrics-key", "metrics");
        get(port, "metrics-key");

        val metrics = APP.getEnvironment().metrics();
        assertEntityOpMetricExists(metrics, "save");
        assertEntityOpMetricExists(metrics, "get");

        val total = metrics.getMeters().get(metricName("save", "total"));
        assertNotNull(total, "save total meter must exist");
        assertTrue(total.getCount() >= 1, "save total meter must have counted at least one op");

        val success = metrics.getMeters().get(metricName("save", "success"));
        assertNotNull(success, "save success meter must exist");
        assertTrue(success.getCount() >= 1, "save success meter must have counted at least one op");

        val latency = metrics.getTimers().get(metricName("save", "latency"));
        assertNotNull(latency, "save latency timer must exist");
        assertEquals(1, latency.getCount(), "save latency timer must have exactly one recorded call");
    }

    @Test
    void healthChecksAreRegisteredAndHealthy() {
        val healthChecks = APP.getEnvironment().healthChecks();
        val names = healthChecks.getNames();

        // SessionFactoryHealthCheck registers per shard, named connectionpool-<namespace>-<shardId>
        assertTrue(names.contains("connectionpool-default-0"),
                "expected per-shard health check 'connectionpool-default-0', got: " + names);
        assertTrue(names.contains("connectionpool-default-1"),
                "expected per-shard health check 'connectionpool-default-1', got: " + names);

        for (val name : names) {
            val result = healthChecks.runHealthCheck(name);
            assertTrue(result.isHealthy(), "health check '" + name + "' must be healthy: "
                    + Optional.ofNullable(result.getMessage()).orElse(""));
        }
    }

    @Test
    void adminBlacklistTasksAreRegistered() {
        // Tasks are registered with the admin environment; assert through an HTTP call
        // to the admin tasks servlet — blacklist task should exist and respond (even if it errors).
        val port = APP.getAdminPort();
        val response = APP.client()
                .target(String.format("http://localhost:%d/tasks/default.blacklist?shard=0", port))
                .request()
                .post(jakarta.ws.rs.client.Entity.text(""));
        // Task exists: it executes (200/400-family), not 404 (unknown task)
        assertTrue(response.getStatus() != 404,
                "admin task 'default.blacklist' must be registered, got HTTP " + response.getStatus());
        val unblacklist = APP.client()
                .target(String.format("http://localhost:%d/tasks/default.unblacklist?shard=0", port))
                .request()
                .post(jakarta.ws.rs.client.Entity.text(""));
        assertTrue(unblacklist.getStatus() != 404,
                "admin task 'default.unblacklist' must be registered, got HTTP " + unblacklist.getStatus());
    }

    private static void assertEntityOpMetricExists(final MetricRegistry metrics, final String command) {
        assertTrue(metrics.getTimers().containsKey(metricName(command, "latency"))
                        || metrics.getMeters().containsKey(metricName(command, "total")),
                "expected entity-op metrics for command '" + command + "', timers: "
                        + metrics.getTimers().keySet());
    }

    private static String metricName(final String command, final String suffix) {
        return MetricRegistry.name("db.sharding.entity",
                TestEntity.class.getCanonicalName().replace('.', '_'),
                "io_appform_dropwizard_sharding_dao_LookupDao",
                command,
                suffix);
    }

    private static int put(final int port, final String key, final String body) {
        return APP.client()
                .target(String.format(BASE_URL + "/%s", port, key))
                .request()
                .put(jakarta.ws.rs.client.Entity.text(body))
                .getStatus();
    }

    private static int get(final int port, final String key) {
        return APP.client()
                .target(String.format(BASE_URL + "/%s", port, key))
                .request()
                .get()
                .getStatus();
    }

    private static String getBody(final int port, final String key) {
        return APP.client()
                .target(String.format(BASE_URL + "/%s", port, key))
                .request()
                .get(String.class);
    }
}

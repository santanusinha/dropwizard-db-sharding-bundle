package io.appform.dropwizard.sharding.healthcheck;

import com.codahale.metrics.health.HealthCheck;
import io.appform.dropwizard.sharding.ShardInfoProvider;
import io.appform.dropwizard.sharding.config.ShardingBundleOptions;
import io.appform.dropwizard.sharding.sharding.NoopShardBlacklistingStore;
import io.appform.dropwizard.sharding.sharding.ShardBlacklistingStore;
import io.dropwizard.core.setup.Environment;
import lombok.extern.slf4j.Slf4j;

import java.util.AbstractMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Slf4j
public class HealthCheckManager {

    private final String namespace;
    private final Environment environment;
    private final ShardInfoProvider shardInfoProvider;
    private final ShardBlacklistingStore blacklistingStore;
    private final ShardingBundleOptions shardingBundleOptions;

    private final Map<String, ShardHealthCheckMeta> dbHealthChecks = new ConcurrentHashMap<>();

    public HealthCheckManager(final String namespace,
                              final Environment environment,
                              final ShardInfoProvider shardInfoProvider,
                              final ShardBlacklistingStore blacklistingStore,
                              final ShardingBundleOptions shardingBundleOptions) {
        this.namespace = namespace;
        this.environment = environment;
        this.shardInfoProvider = shardInfoProvider;
        this.blacklistingStore = blacklistingStore;
        this.shardingBundleOptions = shardingBundleOptions;
    }

    public void register(final String name,
                         final HealthCheck healthCheck) {
        /*
         * If skipNativeHealthcheck is set, or blacklisting store is not NoopShardBlacklistingStore
         * we don't register any health checks
         */
        if (shardingBundleOptions.isSkipNativeHealthcheck() ||
                !(blacklistingStore instanceof NoopShardBlacklistingStore)) {
            return;
        }
        final var dbNamespace = shardInfoProvider.namespace(name);
        if (!Objects.equals(dbNamespace, this.namespace)) {
            return;
        }
        final var shardId = shardInfoProvider.shardId(name);
        if (shardId == -1) {
            return;
        }
        dbHealthChecks.put(name, ShardHealthCheckMeta.builder()
                .healthCheck(healthCheck)
                .shardId(shardId)
                .build());
        environment.healthChecks().register(name, healthCheck);

    }

    public Map<Integer, Boolean> status() {
        return dbHealthChecks.values()
                .stream()
                .map(shardHealthCheckMeta -> new AbstractMap.SimpleEntry<>(shardHealthCheckMeta.getShardId(),
                        shardHealthCheckMeta.getHealthCheck().execute().isHealthy()))
                .collect(Collectors.toMap(AbstractMap.SimpleEntry::getKey, AbstractMap.SimpleEntry::getValue));
    }
}

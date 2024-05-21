package io.appform.dropwizard.sharding.healthcheck;

import com.codahale.metrics.health.HealthCheck;
import com.codahale.metrics.health.HealthCheckRegistryListener;
import io.appform.dropwizard.sharding.ShardInfoProvider;
import io.appform.dropwizard.sharding.config.BlacklistConfig;
import io.appform.dropwizard.sharding.sharding.ShardBlacklistingStore;
import io.appform.dropwizard.sharding.sharding.ShardManager;
import io.dropwizard.hibernate.SessionFactoryHealthCheck;
import io.dropwizard.core.setup.Environment;
import lombok.extern.slf4j.Slf4j;
import lombok.val;

import java.util.AbstractMap;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Slf4j
public class HealthCheckManager implements HealthCheckRegistryListener {

    private final String namespace;
    private final Map<String, ShardHealthCheckMeta> dbHealthChecks = new HashMap<>();
    private final Map<String, ShardHealthCheckMeta> wrappedHealthChecks = new HashMap<>();
    private final ShardInfoProvider shardInfoProvider;
    private final ShardBlacklistingStore blacklistingStore;
    private final ShardManager shardManager;

    public HealthCheckManager(final String namespace,
                              final ShardInfoProvider shardInfoProvider,
                              final ShardBlacklistingStore blacklistingStore,
                              final ShardManager shardManager) {
        this.namespace = namespace;
        this.shardInfoProvider = shardInfoProvider;
        this.blacklistingStore = blacklistingStore;
        this.shardManager = shardManager;
    }


    @Override
    public void onHealthCheckAdded(final String name,
                                   final HealthCheck healthCheck) {
        if (!(healthCheck instanceof SessionFactoryHealthCheck)) {
            return;
        }

        String dbNamespace = shardInfoProvider.namespace(name);
        if (!Objects.equals(dbNamespace, this.namespace)) {
            return;
        }

        int shardId = shardInfoProvider.shardId(name);
        if (shardId == -1) {
            return;
        }

        log.info("db health check added {}", shardId);
        dbHealthChecks.put(name, ShardHealthCheckMeta.builder()
                .healthCheck(healthCheck)
                .shardId(shardId)
                .build());
    }

    @Override
    public void onHealthCheckRemoved(final String name,
                                     final HealthCheck healthCheck) {
        /*
        nothing needs to be done here
        */
    }

    public void manageHealthChecks(final BlacklistConfig blacklistConfig,
                                   final Environment environment) {
        if (blacklistingStore == null) {
            wrappedHealthChecks.putAll(dbHealthChecks);
            return;
        }

        val blacklistingConfig = blacklistConfig != null
                ? blacklistConfig
                : BlacklistConfig.builder().skipNativeHealthcheck(false).build();

        dbHealthChecks.forEach((name, healthCheck) -> {
            environment.healthChecks().unregister(name);
            val hc = new BlacklistingAwareHealthCheck(healthCheck.getShardId(),
                    healthCheck.getHealthCheck(),
                    shardManager,
                    blacklistingConfig);
            environment.healthChecks().register(name, hc);
            wrappedHealthChecks.put(name, ShardHealthCheckMeta.builder()
                    .healthCheck(hc)
                    .shardId(healthCheck.getShardId())
                    .build());
        });
    }


    public Map<Integer, Boolean> status() {
        return wrappedHealthChecks.values()
                .stream()
                .map(shardHealthCheckMeta -> new AbstractMap.SimpleEntry<>(shardHealthCheckMeta.getShardId(),
                        shardHealthCheckMeta.getHealthCheck().execute().isHealthy()))
                .collect(Collectors.toMap(AbstractMap.SimpleEntry::getKey, AbstractMap.SimpleEntry::getValue));
    }
}

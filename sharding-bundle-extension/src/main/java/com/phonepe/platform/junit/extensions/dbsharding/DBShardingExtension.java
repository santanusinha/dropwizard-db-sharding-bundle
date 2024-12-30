package com.phonepe.platform.junit.extensions.dbsharding;

import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.health.HealthCheckRegistry;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.phonepe.platform.traceview.interceptors.DbShardingBundleTraceViewObserver;
import io.appform.dropwizard.sharding.BalancedDBShardingBundle;
import io.appform.dropwizard.sharding.DBShardingBundle;
import io.appform.dropwizard.sharding.config.ShardedHibernateFactory;
import io.dropwizard.Configuration;
import io.dropwizard.db.DataSourceFactory;
import io.dropwizard.jersey.DropwizardResourceConfig;
import io.dropwizard.jersey.setup.JerseyEnvironment;
import io.dropwizard.lifecycle.setup.LifecycleEnvironment;
import io.dropwizard.setup.AdminEnvironment;
import io.dropwizard.setup.Bootstrap;
import io.dropwizard.setup.Environment;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import lombok.val;
import org.apache.commons.lang3.StringUtils;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.Transaction;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolver;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;


@Slf4j
public class DBShardingExtension implements BeforeAllCallback, BeforeEachCallback, ParameterResolver {

    public static final String PROPERTY_BUNDLE_TO_PREPARE = "db.cp.bundles_to_prepare";

    public static class TestConfig extends Configuration {
        protected final ShardedHibernateFactory shards = new ShardedHibernateFactory();
    }

    protected DBShardingBundle<TestConfig> bundle;
    protected BalancedDBShardingBundle<TestConfig> balancedBundle;

    @Override
    public void beforeAll(ExtensionContext context) {
        val threadId = Thread.currentThread().getId();
        val store = context.getRoot().getStore(ExtensionContext.Namespace.create(DBShardingExtension.class, threadId));
        store.getOrComputeIfAbsent(Environment.class, key -> setupEnvironment());
        store.getOrComputeIfAbsent(Bootstrap.class, key -> setupBootstrap());
        store.getOrComputeIfAbsent(DBBundle.class, key -> {
            setupBundle(store.get(Environment.class, Environment.class),
                    store.get(Bootstrap.class, Bootstrap.class), threadId);
            return new DBBundle(bundle, balancedBundle);
        });
    }

    @Override
    public void beforeEach(ExtensionContext context) {
        val threadId = Thread.currentThread().getId();
        val dbStore = context.getStore(ExtensionContext.Namespace.create(DBShardingExtension.class, threadId));
        val dbBundle = dbStore.get(DBBundle.class, DBBundle.class);
        if (dbBundle.getShardingBundle() != null) {
            dbBundle.getShardingBundle().getSessionFactories().forEach(this::truncateTables);
        }
        if (dbBundle.getBalancedDBShardingBundle() != null) {
            dbBundle.getBalancedDBShardingBundle().getSessionFactories().forEach(this::truncateTables);
        }
    }

    @Override
    public boolean supportsParameter(final ParameterContext parameterContext,
                                     final ExtensionContext extensionContext) {
        val parameterType = parameterContext.getParameter().getType();
        return parameterType == DBBundle.class;
    }

    @Override
    public Object resolveParameter(final ParameterContext parameterContext,
                                   final ExtensionContext extensionContext) {
        val threadId = Thread.currentThread().getId();
        val store = extensionContext.getStore(ExtensionContext.Namespace.create(DBShardingExtension.class, threadId));
        return store.get(parameterContext.getParameter().getType());
    }

    @SneakyThrows
    private void setupBundle(final Environment environment,
                             final Bootstrap<?> bootstrap,
                             final long threadId) {
        val props = loadProperties();
        val classPathPackage = props.getProperty("db.cp.package");
        val numberOfShards = Integer.parseInt(props.getProperty("db.cp.shard_count", "2"));
        val useSameNameForShards = Boolean
                .parseBoolean(props.getProperty("db.cp.use_same_name", Boolean.FALSE.toString()));
        val bundlesToPrepare = BundleImplType.valueOf(
                props.getProperty(PROPERTY_BUNDLE_TO_PREPARE, BundleImplType.ALL.toString()));
        val traceViewDbName = props.getProperty("db.cp.db_name", "default");
        val dbShardingBundleTraceViewObserver = new DbShardingBundleTraceViewObserver(traceViewDbName);
        val testConfig = new TestConfig();
        testConfig.shards.setShards(
                IntStream.range(0, numberOfShards)
                        .mapToObj(value -> {
                            val dbName = useSameNameForShards
                                    ? String.valueOf(threadId)
                                    : String.valueOf(value) + threadId;
                            return createConfig(dbName);
                        })
                        .collect(Collectors.toList()));
        if (isUnbalancedShardingBundleRequired(bundlesToPrepare)) {
            bundle = new DBShardingBundle<>(classPathPackage) {
                @Override
                protected ShardedHibernateFactory getConfig(TestConfig config) {
                    return testConfig.shards;
                }
            };
            bundle.registerObserver(dbShardingBundleTraceViewObserver);
            bundle.initialize(bootstrap);
            bundle.initBundles(bootstrap);
            bundle.runBundles(testConfig, environment);
            bundle.run(testConfig, environment);
        }

        if (isBalancedShardingBundleRequired(bundlesToPrepare)) {
            balancedBundle = new BalancedDBShardingBundle<>(classPathPackage) {
                @Override
                protected ShardedHibernateFactory getConfig(TestConfig config) {
                    return testConfig.shards;
                }
            };
            balancedBundle.registerObserver(dbShardingBundleTraceViewObserver);
            balancedBundle.initialize(bootstrap);
            balancedBundle.initBundles(bootstrap);
            balancedBundle.runBundles(testConfig, environment);
            balancedBundle.run(testConfig, environment);
        }
    }

    @SneakyThrows
    private Properties loadProperties() {
        val props = new Properties();
        props.load(getClass().getResourceAsStream("/junit5.db.properties"));

        /*
            If any property is overridden through system property, override through that
        */
        val bundlesToPrepareFromSysProperty = System.getProperty(PROPERTY_BUNDLE_TO_PREPARE);
        if (!StringUtils.isBlank(bundlesToPrepareFromSysProperty)) {
            props.setProperty(PROPERTY_BUNDLE_TO_PREPARE, bundlesToPrepareFromSysProperty);
        }
        return props;
    }

    private boolean isBalancedShardingBundleRequired(BundleImplType bundlesToPrepare) {
        return bundlesToPrepare == BundleImplType.ALL
                || bundlesToPrepare == BundleImplType.BALANCED;
    }

    private boolean isUnbalancedShardingBundleRequired(BundleImplType bundlesToPrepare) {
        return bundlesToPrepare == BundleImplType.ALL
                || bundlesToPrepare == BundleImplType.UNBALANCED;
    }

    private DataSourceFactory createConfig(String dbName) {
        Map<String, String> properties = Maps.newHashMap();
        properties.put("hibernate.dialect", "org.hibernate.dialect.H2Dialect");
        properties.put("hibernate.hbm2ddl.auto", "create");
        properties.put("hibernate.format_sql", Boolean.FALSE.toString());
        properties.put("hibernate.use_sql_comments", Boolean.FALSE.toString());
        DataSourceFactory shard = new DataSourceFactory();
        shard.setDriverClass("org.h2.Driver");
        shard.setInitialSize(1);
        shard.setMinSize(1);
        shard.setMaxSize(4);
        shard.setUrl("jdbc:h2:mem:" + dbName + ";MODE=MySQL;");
        shard.setValidationQuery("select 1");
        shard.setProperties(properties);
        return shard;
    }

    private void truncateTables(SessionFactory sessionFactory) {
        Session session = sessionFactory.openSession();
        val query = session.createNativeQuery("show tables");
        Set<String> tables = Sets.newHashSet();
        List<Object[]> rows = query.list();
        for (Object[] row : rows) {
            tables.add(row[0].toString());
        }

        List<String> queries = Lists.newArrayList();
        queries.add("SET foreign_key_checks = 0");
        tables.forEach(table -> queries.add(String.format("delete from %s", table)));
        queries.add("SET foreign_key_checks = 1");

        val truncateQuery = String.join(";", queries);
        Transaction transaction = session.beginTransaction();
        session.createNativeQuery(truncateQuery).executeUpdate();
        transaction.commit();
        session.close();
    }

    private Environment setupEnvironment() {
        val environment = Mockito.mock(Environment.class);
        HealthCheckRegistry healthChecks = mock(HealthCheckRegistry.class);
        JerseyEnvironment jerseyEnvironment = mock(JerseyEnvironment.class);
        LifecycleEnvironment lifecycleEnvironment = mock(LifecycleEnvironment.class);
        AdminEnvironment admin = mock(AdminEnvironment.class);

        when(jerseyEnvironment.getResourceConfig()).thenReturn(new DropwizardResourceConfig());
        when(environment.jersey()).thenReturn(jerseyEnvironment);
        when(environment.lifecycle()).thenReturn(lifecycleEnvironment);
        when(environment.healthChecks()).thenReturn(healthChecks);
        when(environment.admin()).thenReturn(admin);
        when(environment.metrics()).thenReturn(new MetricRegistry());
        return environment;

    }

    private Bootstrap<?> setupBootstrap() {
        Bootstrap<?> bootstrap = mock(Bootstrap.class);
        when(bootstrap.getHealthCheckRegistry()).thenReturn(Mockito.mock(HealthCheckRegistry.class));
        return bootstrap;
    }

}

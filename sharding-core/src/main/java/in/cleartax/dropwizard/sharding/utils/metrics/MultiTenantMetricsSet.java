package in.cleartax.dropwizard.sharding.utils.metrics;

import com.codahale.metrics.Metric;
import com.codahale.metrics.MetricSet;
import in.cleartax.dropwizard.sharding.transactions.TransactionContext;
import in.cleartax.dropwizard.sharding.utils.exception.InvalidTenantArgumentException;
import in.cleartax.dropwizard.sharding.hibernate.ConstTenantIdentifierResolver;
import in.cleartax.dropwizard.sharding.hibernate.MultiTenantDataSourceFactory;
import in.cleartax.dropwizard.sharding.hibernate.MultiTenantUnitOfWorkAwareProxyFactory;
import in.cleartax.dropwizard.sharding.transactions.DefaultUnitOfWorkImpl;
import in.cleartax.dropwizard.sharding.transactions.TransactionRunner;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.hibernate.SessionFactory;

import java.util.Map;
import java.util.stream.Collectors;


/**
 * Created on 15/10/18
 * Inspired by: https://www.oreilly.com/ideas/handling-checked-exceptions-in-java-streams
 */
@RequiredArgsConstructor
@Getter
public abstract class MultiTenantMetricsSet implements MetricSet {

    private final MultiTenantUnitOfWorkAwareProxyFactory proxyFactory;
    private final SessionFactory sessionFactory;
    private final MultiTenantDataSourceFactory multiTenantDataSourceFactory;

    @Override
    public Map<String, Metric> getMetrics() {
        return multiTenantDataSourceFactory.getTenantDbMap().keySet().stream()
                .map(tenantId -> {
                    try {
                        return new TransactionRunner<Map<String, Metric>>(proxyFactory, sessionFactory,
                                new ConstTenantIdentifierResolver(tenantId), TransactionContext.create(this.getClass().getEnclosingMethod())) {

                            @Override
                            public Map<String, Metric> run() {
                                return runOnTenant(tenantId);
                            }
                        }.start(false, new DefaultUnitOfWorkImpl());
                    } catch (Throwable t) {
                        throw new RuntimeException(t);
                    }
                })
                .flatMap(map -> map.entrySet().stream())
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (e1, e2) -> {
                    throw new InvalidTenantArgumentException("Metric key on different tenants cannot be same");
                }));
    }

    public abstract Map<String, Metric> runOnTenant(String tenantId);
}

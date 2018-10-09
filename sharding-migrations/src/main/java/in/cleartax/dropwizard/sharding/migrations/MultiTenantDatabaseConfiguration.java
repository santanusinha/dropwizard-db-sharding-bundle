package in.cleartax.dropwizard.sharding.migrations;

import in.cleartax.dropwizard.sharding.hibernate.MultiTenantDataSourceFactory;
import io.dropwizard.Configuration;

/**
 * Created on 09/10/18
 */
public interface MultiTenantDatabaseConfiguration<T extends Configuration> {
    MultiTenantDataSourceFactory getDataSourceFactory(T configuration);
}

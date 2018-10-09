package in.cleartax.dropwizard.sharding.migrations;

import in.cleartax.dropwizard.sharding.hibernate.MultiTenantDataSourceFactory;
import io.dropwizard.Configuration;

public class TestMigrationConfiguration extends Configuration {

    private MultiTenantDataSourceFactory dataSource;

    public TestMigrationConfiguration(MultiTenantDataSourceFactory dataSource) {
        this.dataSource = dataSource;
    }

    public MultiTenantDataSourceFactory getDataSource() {
        return dataSource;
    }
}

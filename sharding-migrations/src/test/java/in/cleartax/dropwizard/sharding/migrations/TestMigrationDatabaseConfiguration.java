package in.cleartax.dropwizard.sharding.migrations;

import in.cleartax.dropwizard.sharding.hibernate.MultiTenantDataSourceFactory;

public class TestMigrationDatabaseConfiguration implements MultiTenantDatabaseConfiguration<TestMigrationConfiguration> {

    @Override
    public MultiTenantDataSourceFactory getDataSourceFactory(TestMigrationConfiguration configuration) {
        return configuration.getDataSource();
    }
}

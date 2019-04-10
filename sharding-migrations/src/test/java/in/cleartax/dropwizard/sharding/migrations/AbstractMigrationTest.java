package in.cleartax.dropwizard.sharding.migrations;

import in.cleartax.dropwizard.sharding.hibernate.ExtendedDataSourceFactory;
import in.cleartax.dropwizard.sharding.hibernate.MultiTenantDataSourceFactory;
import net.sourceforge.argparse4j.ArgumentParsers;
import net.sourceforge.argparse4j.inf.Subparser;
import org.assertj.core.util.Maps;

import java.util.Map;
import java.util.UUID;

public class AbstractMigrationTest {

    protected static final String UTF_8 = "UTF-8";

    static {
        ArgumentParsers.setTerminalWidthDetection(false);
    }

    protected static Subparser createSubparser(AbstractLiquibaseCommand<?> command) {
        final Subparser subparser = ArgumentParsers.newArgumentParser("db")
                .addSubparsers()
                .addParser(command.getName())
                .description(command.getDescription());
        command.configure(subparser);
        return subparser;
    }

    protected static TestMigrationConfiguration createConfiguration(String databaseUrl) {
        final ExtendedDataSourceFactory dataSource = new ExtendedDataSourceFactory();
        dataSource.setDriverClass("org.h2.Driver");
        dataSource.setUser("sa");
        dataSource.setUrl(databaseUrl);
        MultiTenantDataSourceFactory multiTenantDataSourceFactory = new MultiTenantDataSourceFactory();
        multiTenantDataSourceFactory.setDefaultTenant("shard1");
        Map<String, ExtendedDataSourceFactory> tenantDbMap = Maps.newHashMap("shard1", dataSource);
        multiTenantDataSourceFactory.setTenantDbMap(tenantDbMap);
        return new TestMigrationConfiguration(multiTenantDataSourceFactory);
    }

    protected static String getDatabaseUrl() {
        return "jdbc:h2:mem:" + UUID.randomUUID() + ";db_close_delay=-1";
    }
}

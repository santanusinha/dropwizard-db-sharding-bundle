package in.cleartax.dropwizard.sharding.hibernate;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.dropwizard.db.DataSourceFactory;

import javax.annotation.Nullable;

/**
 * Created on 2019-04-10
 */
public class ExtendedDataSourceFactory extends DataSourceFactory {

    @Nullable
    private ReadReplicaDataSourceFactory readReplica;

    @Nullable
    public ReadReplicaDataSourceFactory getReadReplica() {
        return readReplica;
    }

    @JsonProperty
    public void setReadReplica(@Nullable ReadReplicaDataSourceFactory readReplica) {
        this.readReplica = readReplica;
    }
}

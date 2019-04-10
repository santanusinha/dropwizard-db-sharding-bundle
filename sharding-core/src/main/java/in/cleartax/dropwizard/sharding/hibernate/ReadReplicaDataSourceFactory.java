package in.cleartax.dropwizard.sharding.hibernate;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.dropwizard.db.DataSourceFactory;

/**
 * Created on 2019-04-10
 */
public class ReadReplicaDataSourceFactory extends DataSourceFactory {
    private boolean isEnabled;

    @JsonProperty("isEnabled")
    public boolean isEnabled() {
        return isEnabled;
    }

    @JsonProperty("isEnabled")
    public void setEnabled(boolean enabled) {
        isEnabled = enabled;
    }
}

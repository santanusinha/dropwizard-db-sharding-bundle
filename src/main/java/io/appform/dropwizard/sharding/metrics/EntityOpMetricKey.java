package io.appform.dropwizard.sharding.metrics;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class EntityOpMetricKey {

    Class<?> entityClass;
    Class<?> daoClass;
    String commandName;
    String lockedContextMode;

    /**
     * @deprecated Field opType got renamed to commandName. This is here for the backward compatibility.
     */
    @Deprecated(forRemoval = true)
    public String getOpType() {
        return commandName;
    }
}

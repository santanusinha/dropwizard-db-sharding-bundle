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
     * @deprecated This is here for the backward compatibility to older version of spyglass tracing
     * module. (field opType got renamed to commandName)
     */
    @Deprecated(forRemoval=true)
    public String getOpType() {
        return commandName;
    }
}

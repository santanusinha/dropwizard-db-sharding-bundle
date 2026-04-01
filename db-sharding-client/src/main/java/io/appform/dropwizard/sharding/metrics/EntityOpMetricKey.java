package io.appform.dropwizard.sharding.metrics;

import io.appform.dropwizard.sharding.execution.DaoType;
import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class EntityOpMetricKey {

    Class<?> entityClass;
    DaoType daoType;
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

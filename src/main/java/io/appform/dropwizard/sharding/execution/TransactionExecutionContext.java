package io.appform.dropwizard.sharding.execution;

import io.appform.dropwizard.sharding.dao.operations.OpContext;
import io.appform.dropwizard.sharding.dao.operations.lockedcontext.LockAndExecute;
import lombok.Builder;
import lombok.NonNull;
import lombok.Value;

@Value
@Builder
public class TransactionExecutionContext {
    String commandName; // method or command name that gets executed across shards. Ex. ScatterGather.
    String shardName;
    Class<?> daoClass;
    Class<?> entityClass;

    @NonNull
    OpContext<?> opContext;

    /**
     * @deprecated This is here for the backward compatibility to older version of spyglass tracing
     * module. (field lockedContextMode got removed)
     */
    @Deprecated(forRemoval = true)
    public String getLockedContextMode() {
        return this.opContext instanceof LockAndExecute ?
                ((LockAndExecute<?>) this.opContext).getMode().name() : null;
    }

    /**
     * @deprecated This is here for the backward compatibility to older version of spyglass tracing
     * module. (field opType got renamed to commandName)
     */
    @Deprecated(forRemoval=true)
    public String getOpType() {
        return commandName;
    }
}

package io.appform.dropwizard.sharding.execution;

import io.appform.dropwizard.sharding.dao.operations.OpContext;
import io.appform.dropwizard.sharding.dao.operations.lockedcontext.LockAndExecute;
import lombok.Builder;
import lombok.NonNull;
import lombok.Value;

@Value
@Builder
public class TransactionExecutionContext {
    @NonNull
    String commandName; // method or command name that gets executed across shards. Ex. ScatterGather.
    @NonNull
    String shardName;
    @NonNull
    DaoType daoType;
    @NonNull
    Class<?> entityClass;
    @NonNull
    OpContext<?> opContext;

    /**
     * @deprecated Field lockedContextMode got removed with the introduction of opcontext.
     * This is here for the backward compatibility.
     */
    @Deprecated(forRemoval = true)
    public String getLockedContextMode() {
        return this.opContext instanceof LockAndExecute ? ((LockAndExecute<?>) this.opContext).getMode().name() : null;
    }

    /**
     * @deprecated Field opType got renamed to commandName.
     * This is here for the backward compatibility.
     */
    @Deprecated(forRemoval = true)
    public String getOpType() {
        return commandName;
    }
}

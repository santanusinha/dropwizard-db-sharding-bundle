package io.appform.dropwizard.sharding.dao.operations;

import io.appform.dropwizard.sharding.dao.operations.lockedcontext.LockAndExecute;
import io.appform.dropwizard.sharding.dao.operations.lookupdao.CreateOrUpdateByLookupKey;
import io.appform.dropwizard.sharding.dao.operations.lookupdao.DeleteByLookupKey;
import io.appform.dropwizard.sharding.dao.operations.lookupdao.GetAndUpdateByLookupKey;
import io.appform.dropwizard.sharding.dao.operations.lookupdao.GetByLookupKey;
import io.appform.dropwizard.sharding.dao.operations.lookupdao.readonlycontext.ReadOnlyForLookupDao;
import io.appform.dropwizard.sharding.dao.operations.relationaldao.CreateOrUpdate;
import io.appform.dropwizard.sharding.dao.operations.relationaldao.CreateOrUpdateInLockedContext;
import io.appform.dropwizard.sharding.dao.operations.relationaldao.readonlycontext.ReadOnlyForRelationalDao;
import lombok.Data;
import org.hibernate.Session;

import java.util.function.Function;

/**
 * Operation to be executed as a transaction on a single shard.
 *
 * @param <T> return type of the operation.
 */
@Data
public abstract class OpContext<T> implements Function<Session, T> {
    public abstract OpType getOpType();

    public abstract <P> P visit(OpContextVisitor<P> visitor);

    public interface OpContextVisitor<P> {

        P visit(Count opContext);

        P visit(CountByQuerySpec opContext);

        <T, R> P visit(Get<T, R> opContext);

        <T> P visit(GetAndUpdate<T> opContext);

        <T, R> P visit(GetByLookupKey<T, R> opContext);

        <T> P visit(GetAndUpdateByLookupKey<T> opContext);

        <T> P visit(ReadOnlyForLookupDao<T> opContext);

        <T> P visit(ReadOnlyForRelationalDao<T> opContext);

        <T> P visit(LockAndExecute<T> opContext);

        P visit(UpdateByQuery opContext);

        <T> P visit(UpdateWithScroll<T> opContext);

        <T> P visit(UpdateAll<T> opContext);

        <T> P visit(SelectAndUpdate<T> opContext);

        <T> P visit(RunInSession<T> opContext);

        <T> P visit(RunWithCriteria<T> opContext);

        P visit(DeleteByLookupKey opContext);

        <U, V> P visit(Save<U, V> opContext);

        <T> P visit(SaveAll<T> opContext);

        <T> P visit(CreateOrUpdateByLookupKey<T> opContext);

        <T> P visit(CreateOrUpdate<T> opContext);

        <T, U> P visit(CreateOrUpdateInLockedContext<T, U> opContext);

        <T, R> P visit(Select<T, R> opContext);

    }

}

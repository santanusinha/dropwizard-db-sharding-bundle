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

    /**
     * Visits a {@link SaveWithParent}, i.e. a {@link Save} of a child entity within a
     * {@link io.appform.dropwizard.sharding.dao.LockedContext} that also carries the locked parent.
     * <p>
     * The default delegates to {@link #visit(Save)} with <b>the same instance</b> ({@link SaveWithParent} is a
     * {@link Save}), so visitors that predate {@link SaveWithParent} keep their exact {@link Save} behaviour: anything
     * they change on the op (saver, afterSave, entity), now or later, is what gets executed. Such visitors can still
     * reach the parent via {@code instanceof SaveWithParent}. Visitors that need the parent override this method.
     * <p>
     * Why this is a default and not abstract: LockedContext child saves were emitted as {@link Save} before
     * {@link SaveWithParent} existed. An abstract method would make every visitor compiled against an older version of
     * this library fail at runtime with {@link AbstractMethodError} on every such save.
     * <p>
     * <b>This must remain the only default method of this interface.</b> A default is only acceptable for an op that
     * specialises (extends) an op already emitted on the same code path, and it must delegate to that op's
     * {@code visit}. Any new, unrelated {@link OpContext} must be added as an abstract method so every visitor is
     * forced to handle it. This rule is enforced by {@code OpContextVisitorContractTest}.
     */
    default <T, R, U> P visit(SaveWithParent<T, R, U> opContext) {
      // The upcast is required: without it overload resolution picks this very method again (infinite recursion).
      return visit((Save<T, R>) opContext);
    }

  }

}

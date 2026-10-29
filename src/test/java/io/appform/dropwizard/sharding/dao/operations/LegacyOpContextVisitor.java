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

/**
 * Test helper modelling a visitor written before {@link SaveWithParent} existed (e.g. an external lease/audit
 * interceptor): it implements every abstract {@code visit} method as a no-op, leaves {@link #visit(Save)} to the
 * subclass, and does NOT override {@code visit(SaveWithParent)}.
 */
public abstract class LegacyOpContextVisitor<P> implements OpContext.OpContextVisitor<P> {

    @Override
    public abstract <T, R> P visit(Save<T, R> opContext);

    @Override
    public P visit(Count opContext) { return null; }

    @Override
    public P visit(CountByQuerySpec opContext) { return null; }

    @Override
    public <T, R> P visit(Get<T, R> opContext) { return null; }

    @Override
    public <T> P visit(GetAndUpdate<T> opContext) { return null; }

    @Override
    public <T, R> P visit(GetByLookupKey<T, R> opContext) { return null; }

    @Override
    public <T> P visit(GetAndUpdateByLookupKey<T> opContext) { return null; }

    @Override
    public <T> P visit(ReadOnlyForLookupDao<T> opContext) { return null; }

    @Override
    public <T> P visit(ReadOnlyForRelationalDao<T> opContext) { return null; }

    @Override
    public <T> P visit(LockAndExecute<T> opContext) { return null; }

    @Override
    public P visit(UpdateByQuery opContext) { return null; }

    @Override
    public <T> P visit(UpdateWithScroll<T> opContext) { return null; }

    @Override
    public <T> P visit(UpdateAll<T> opContext) { return null; }

    @Override
    public <T> P visit(SelectAndUpdate<T> opContext) { return null; }

    @Override
    public <T> P visit(RunInSession<T> opContext) { return null; }

    @Override
    public <T> P visit(RunWithCriteria<T> opContext) { return null; }

    @Override
    public P visit(DeleteByLookupKey opContext) { return null; }

    @Override
    public <T> P visit(SaveAll<T> opContext) { return null; }

    @Override
    public <T> P visit(CreateOrUpdateByLookupKey<T> opContext) { return null; }

    @Override
    public <T> P visit(CreateOrUpdate<T> opContext) { return null; }

    @Override
    public <T, U> P visit(CreateOrUpdateInLockedContext<T, U> opContext) { return null; }

    @Override
    public <T, R> P visit(Select<T, R> opContext) { return null; }
}

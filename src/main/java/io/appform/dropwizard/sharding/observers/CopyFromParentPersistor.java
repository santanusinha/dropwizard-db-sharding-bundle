package io.appform.dropwizard.sharding.observers;

import io.appform.dropwizard.sharding.dao.operations.SaveWithParent;
import io.appform.dropwizard.sharding.dao.operations.Count;
import io.appform.dropwizard.sharding.dao.operations.CountByQuerySpec;
import io.appform.dropwizard.sharding.dao.operations.Get;
import io.appform.dropwizard.sharding.dao.operations.GetAndUpdate;
import io.appform.dropwizard.sharding.dao.operations.OpContext;
import io.appform.dropwizard.sharding.dao.operations.RunInSession;
import io.appform.dropwizard.sharding.dao.operations.RunWithCriteria;
import io.appform.dropwizard.sharding.dao.operations.Save;
import io.appform.dropwizard.sharding.dao.operations.SaveAll;
import io.appform.dropwizard.sharding.dao.operations.Select;
import io.appform.dropwizard.sharding.dao.operations.SelectAndUpdate;
import io.appform.dropwizard.sharding.dao.operations.UpdateAll;
import io.appform.dropwizard.sharding.dao.operations.UpdateByQuery;
import io.appform.dropwizard.sharding.dao.operations.UpdateWithScroll;
import io.appform.dropwizard.sharding.dao.operations.lockedcontext.LockAndExecute;
import io.appform.dropwizard.sharding.dao.operations.lookupdao.CreateOrUpdateByLookupKey;
import io.appform.dropwizard.sharding.dao.operations.lookupdao.DeleteByLookupKey;
import io.appform.dropwizard.sharding.dao.operations.lookupdao.GetAndUpdateByLookupKey;
import io.appform.dropwizard.sharding.dao.operations.lookupdao.GetByLookupKey;
import io.appform.dropwizard.sharding.dao.operations.lookupdao.readonlycontext.ReadOnlyForLookupDao;
import io.appform.dropwizard.sharding.dao.operations.relationaldao.CreateOrUpdate;
import io.appform.dropwizard.sharding.dao.operations.relationaldao.CreateOrUpdateInLockedContext;
import io.appform.dropwizard.sharding.dao.operations.relationaldao.readonlycontext.ReadOnlyForRelationalDao;
import io.appform.dropwizard.sharding.utils.CopyFromParentUtils;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * Visitor that intercepts {@link SaveWithParent} operations to perform
 * mismatch detection and field copying based on configuration.
 * <p>
 * This is an internal implementation detail of {@link CopyFromParentObserver}.
 */
@Slf4j
class CopyFromParentPersistor implements OpContext.OpContextVisitor<Void> {

    private final boolean copyEnabled;
    private final boolean mismatchDetectionEnabled;
    private final boolean copyIfDefaultOnly;
    private final CopyFromParentMismatchListener mismatchListener;

    CopyFromParentPersistor(boolean copyEnabled,
                            boolean mismatchDetectionEnabled,
                            boolean copyIfDefaultOnly,
                            CopyFromParentMismatchListener mismatchListener) {
        this.copyEnabled = copyEnabled;
        this.mismatchDetectionEnabled = mismatchDetectionEnabled;
        this.copyIfDefaultOnly = copyIfDefaultOnly;
        this.mismatchListener = mismatchListener;
    }

    @Override
    public <T, R, U> Void visit(SaveWithParent<T, R, U> opContext) {
        final U parent = opContext.getParent();
        final var oldSaver = opContext.getSaver();
        opContext.setSaver((T entity) -> {
            if (mismatchDetectionEnabled && mismatchListener != null) {
                try {
                    List<CopyFromParentUtils.FieldMismatch> mismatches =
                            CopyFromParentUtils.detectMismatches(parent, entity);
                    if (!mismatches.isEmpty()) {
                        mismatchListener.onMismatches(parent, entity, mismatches);
                    }
                } catch (Exception e) {
                    log.error("Mismatch detection failed for child={}, proceeding with save",
                            entity.getClass().getSimpleName(), e);
                }
            }
            if (copyEnabled) {
                CopyFromParentUtils.copyFields(parent, entity, copyIfDefaultOnly);
            }
            return oldSaver.apply(entity);
        });
        return null;
    }

    @Override
    public Void visit(Count opContext) { return null; }

    @Override
    public Void visit(CountByQuerySpec opContext) { return null; }

    @Override
    public <T, R> Void visit(Get<T, R> opContext) { return null; }

    @Override
    public <T> Void visit(GetAndUpdate<T> opContext) { return null; }

    @Override
    public <T, R> Void visit(GetByLookupKey<T, R> opContext) { return null; }

    @Override
    public <T> Void visit(GetAndUpdateByLookupKey<T> opContext) { return null; }

    @Override
    public <T> Void visit(ReadOnlyForLookupDao<T> opContext) { return null; }

    @Override
    public <T> Void visit(ReadOnlyForRelationalDao<T> opContext) { return null; }

    @Override
    public <T> Void visit(LockAndExecute<T> opContext) { return null; }

    @Override
    public Void visit(UpdateByQuery opContext) { return null; }

    @Override
    public <T> Void visit(UpdateWithScroll<T> opContext) { return null; }

    @Override
    public <T> Void visit(UpdateAll<T> opContext) { return null; }

    @Override
    public <T> Void visit(SelectAndUpdate<T> opContext) { return null; }

    @Override
    public <T> Void visit(RunInSession<T> opContext) { return null; }

    @Override
    public <T> Void visit(RunWithCriteria<T> opContext) { return null; }

    @Override
    public Void visit(DeleteByLookupKey opContext) { return null; }

    @Override
    public <U, V> Void visit(Save<U, V> opContext) { return null; }

    @Override
    public <T> Void visit(SaveAll<T> opContext) { return null; }

    @Override
    public <T> Void visit(CreateOrUpdateByLookupKey<T> opContext) { return null; }

    @Override
    public <T> Void visit(CreateOrUpdate<T> opContext) { return null; }

    @Override
    public <T, U> Void visit(CreateOrUpdateInLockedContext<T, U> opContext) { return null; }

    @Override
    public <T, R> Void visit(Select<T, R> opContext) { return null; }
}

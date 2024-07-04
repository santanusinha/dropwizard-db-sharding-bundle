package io.appform.dropwizard.sharding.observers.bucket;
import com.google.common.base.Preconditions;
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
import io.appform.dropwizard.sharding.dao.operations.relationaldao.CreateOrUpdateInLockedContext;
import io.appform.dropwizard.sharding.dao.operations.relationaldao.readonlycontext.ReadOnlyForRelationalDao;
import io.appform.dropwizard.sharding.sharding.BucketId;
import io.appform.dropwizard.sharding.utils.BucketCalculator;
import lombok.extern.slf4j.Slf4j;
import lombok.val;
import org.apache.commons.lang3.reflect.FieldUtils;

import javax.persistence.Id;
import java.lang.reflect.Field;
import java.util.Collection;

@Slf4j
public class BucketIdSaver implements OpContext.OpContextVisitor<Void> {
    private static final String OPERATION_NOT_SUPPORTED = " operation not supported";
    private BucketCalculator<String> bucketCalculator;

    public BucketIdSaver(BucketCalculator<String> bucketCalculator) {
        this.bucketCalculator = bucketCalculator;
    }

    @Override
    public Void visit(Count count) {
        return null;
    }

    @Override
    public Void visit(CountByQuerySpec countByQuerySpec) {
        return null;
    }

    @Override
    public <T, R> Void visit(Get<T, R> opContext) {
        return null;
    }

    @Override
    public <T> Void visit(GetAndUpdate<T> opContext) {
        return null;
    }

    @Override
    public <T, R> Void visit(GetByLookupKey<T, R> getByLookupKey) {
        return null;
    }

    @Override
    public <T> Void visit(GetAndUpdateByLookupKey<T> getAndUpdateByLookupKey) {
        return null;
    }

    @Override
    public <T> Void visit(ReadOnlyForLookupDao<T> readOnlyForLookupDao) {
        return null;
    }

    @Override
    public <T> Void visit(ReadOnlyForRelationalDao<T> readOnlyForRelationalDao) {
        return null;
    }

    @Override
    public <T> Void visit(LockAndExecute<T> opContext) {

        val contextMode = opContext.getMode();

        switch (contextMode) {
            case READ:
                return null;
            case INSERT:
                val oldSaver = opContext.getSaver();
                opContext.setSaver(oldSaver.compose((T entity) -> {
                    try {
                        addBucketId(entity);
                    } catch (IllegalAccessException e) {
                        throw new RuntimeException(e);
                    }
                    return entity;
                }));
                break;
            default:
                throw new UnsupportedOperationException(contextMode + OPERATION_NOT_SUPPORTED);
        }
        return null;
    }

    @Override
    public Void visit(UpdateByQuery updateByQuery) {
        return null;
    }

    @Override
    public <T> Void visit(UpdateWithScroll<T> updateWithScroll) {
        return null;
    }

    @Override
    public <T> Void visit(UpdateAll<T> updateAll) {
        return null;
    }

    @Override
    public <T> Void visit(SelectAndUpdate<T> selectAndUpdate) {
        return null;
    }

    @Override
    public <T> Void visit(RunInSession<T> runInSession) {
        return null;
    }

    @Override
    public <T> Void visit(RunWithCriteria<T> runWithCriteria) {
        return null;
    }

    @Override
    public Void visit(DeleteByLookupKey deleteByLookupKey) {
        return null;
    }

    @Override
    public <T, R> Void visit(Save<T, R> opContext) {
        val oldSaver = opContext.getSaver();
        opContext.setSaver((T t) -> {
            try {
                addBucketId(opContext.getEntity());
            } catch (IllegalAccessException e) {
                throw new RuntimeException(e);
            }
            return oldSaver.apply(opContext.getEntity());
        });
        return null;
    }

    @Override
    public <T> Void visit(SaveAll<T> opContext) {
        val oldSaver = opContext.getSaver();
        val beforeExecute = oldSaver.compose((Collection<T> entities) -> {
            opContext.getEntities().stream().forEach(entity -> {
                try {
                    addBucketId(entity);
                } catch (IllegalAccessException e) {
                    throw new RuntimeException(e);
                }
            });
            return entities;
        });
        opContext.setSaver(beforeExecute);
        return null;
    }

    @Override
    public <T> Void visit(CreateOrUpdateByLookupKey<T> createOrUpdateByLookupKey) {
        return null;
    }

    @Override
    public <T> Void visit(io.appform.dropwizard.sharding.dao.operations.relationaldao.CreateOrUpdate<T> createOrUpdate) {
        return null;
    }

    @Override
    public <T, U> Void visit(CreateOrUpdateInLockedContext<T, U> createOrUpdateInLockedContext) {
        return null;
    }

    @Override
    public <T, R> Void visit(Select<T, R> select) {
        return null;
    }

    private <T> void addBucketId(T entity) throws IllegalAccessException {
        val entityClass = entity.getClass();
        Field[] bucketIdFields = FieldUtils.getFieldsWithAnnotation(entityClass, BucketId.class);
        if(bucketIdFields.length == 0) {
            //no bucket_id annotation present
            return;
        }
        Preconditions.checkArgument(bucketIdFields.length == 1, "Only one field can be designated as @bucketId");
        Field[] idFields = FieldUtils.getFieldsWithAnnotation(entityClass, Id.class);
        Preconditions.checkArgument(idFields.length != 0, "A field needs to be designated as @Id");
        Preconditions.checkArgument(idFields.length == 1, "Only one field can be designated as @Id");
        val keyField = idFields[0];
        val bucketIdField = bucketIdFields[0];
        keyField.setAccessible(true);
        bucketIdField.setAccessible(true);

        val id = keyField.get(entity).toString();
        val bucketId = bucketCalculator.bucketId(id);
        bucketIdField.set(entity, bucketId);
        log.info("Added bucketId after computing");
    }
}

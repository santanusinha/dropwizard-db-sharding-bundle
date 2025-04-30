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
import io.appform.dropwizard.sharding.dao.operations.relationaldao.CreateOrUpdate;
import io.appform.dropwizard.sharding.dao.operations.relationaldao.CreateOrUpdateInLockedContext;
import io.appform.dropwizard.sharding.dao.operations.relationaldao.readonlycontext.ReadOnlyForRelationalDao;
import io.appform.dropwizard.sharding.sharding.BucketIdExtractor;
import io.appform.dropwizard.sharding.sharding.EntityMeta;
import lombok.extern.slf4j.Slf4j;
import lombok.val;
import org.apache.commons.collections.MapUtils;
import org.apache.commons.lang3.StringUtils;

import java.lang.reflect.Field;
import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

@Slf4j
public class BucketKeyPersistor implements OpContext.OpContextVisitor<Void> {

    private final String tenantId;
    private final BucketIdExtractor<String> bucketIdExtractor;
    private final Map<String, EntityMeta> initialisedEntityMeta;

    public BucketKeyPersistor(final String tenantId,
                              final BucketIdExtractor<String> bucketIdExtractor,
                              final Map<String, EntityMeta> initialisedEntitiesMeta) {
        Preconditions.checkArgument(!Objects.isNull(bucketIdExtractor), "BucketIdExtractor must not be null");
        Preconditions.checkArgument(!StringUtils.isEmpty(tenantId), "tenantId must not be empty");
        Preconditions.checkArgument(!MapUtils.isEmpty(initialisedEntitiesMeta), "initialisedEntitiesMeta must not" +
                " be null or empty");
        this.tenantId = tenantId;
        this.bucketIdExtractor = bucketIdExtractor;
        this.initialisedEntityMeta = initialisedEntitiesMeta;
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
        val oldMutator = opContext.getMutator();
        opContext.setMutator((T entity) -> {
            T value = oldMutator.apply(entity);
            addBucketId(value);
            return value;
        });
        return null;
    }

    @Override
    public <T, R> Void visit(GetByLookupKey<T, R> getByLookupKey) {
        return null;
    }

    @Override
    public <T> Void visit(GetAndUpdateByLookupKey<T> getAndUpdateByLookupKey) {
        val oldMutator = getAndUpdateByLookupKey.getMutator();
        getAndUpdateByLookupKey.setMutator((Optional<T> entity) -> {
            T value = oldMutator.apply(entity);
            addBucketId(value);
            return value;
        });
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
                opContext.setSaver((T entity) -> {
                    addBucketId(entity);
                    return oldSaver.apply(entity);
                });
                break;
            default:
                throw new UnsupportedOperationException("Operation not supported for mode " + contextMode);
        }
        return null;
    }

    @Override
    public Void visit(UpdateByQuery updateByQuery) {
        return null;
    }

    @Override
    public <T> Void visit(UpdateWithScroll<T> updateWithScroll) {
        val oldMutator = updateWithScroll.getMutator();
        updateWithScroll.setMutator((T entity) -> {
            T value = oldMutator.apply(entity);
            addBucketId(value);
            return value;
        });
        return null;
    }

    @Override
    public <T> Void visit(UpdateAll<T> updateAll) {
        val oldMutator = updateAll.getMutator();
        updateAll.setMutator((T entity) -> {
            T value = oldMutator.apply(entity);
            addBucketId(value);
            return value;
        });
        return null;
    }

    @Override
    public <T> Void visit(SelectAndUpdate<T> selectAndUpdate) {
        val oldMutator = selectAndUpdate.getMutator();
        selectAndUpdate.setMutator((T entity) -> {
            T value = oldMutator.apply(entity);
            addBucketId(value);
            return value;
        });
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
        opContext.setSaver((T entity) -> {
            addBucketId(entity);
            return oldSaver.apply(entity);
        });
        return null;
    }

    @Override
    public <T> Void visit(SaveAll<T> opContext) {
        val oldSaver = opContext.getSaver();
        opContext.setSaver((Collection<T> entities) -> {
            entities.forEach(this::addBucketId);
            return oldSaver.apply(entities);
        });
        return null;
    }

    @Override
    public <T> Void visit(CreateOrUpdateByLookupKey<T> createOrUpdateByLookupKey) {
        val oldMutator = createOrUpdateByLookupKey.getMutator();
        createOrUpdateByLookupKey.setMutator(result -> {
            if (result != null) {
                T value = oldMutator.apply(result);
                addBucketId(value);
                return value;
            }
            return null;
        });

        val oldSaver = createOrUpdateByLookupKey.getSaver();
        createOrUpdateByLookupKey.setSaver((T entity) -> {
            addBucketId(entity);
            return oldSaver.apply(entity);
        });
        return null;
    }

    @Override
    public <T> Void visit(CreateOrUpdate<T> createOrUpdate) {
        val oldMutator = createOrUpdate.getMutator();
        createOrUpdate.setMutator(result -> {
            if (result != null) {
                T value = oldMutator.apply(result);
                addBucketId(value);
                return value;
            }
            return null;
        });

        val oldSaver = createOrUpdate.getSaver();
        createOrUpdate.setSaver((T entity) -> {
            addBucketId(entity);
            return oldSaver.apply(entity);
        });
        return null;
    }

    @Override
    public <T, U> Void visit(CreateOrUpdateInLockedContext<T, U> createOrUpdateInLockedContext) {
        val oldMutator = createOrUpdateInLockedContext.getMutator();
        createOrUpdateInLockedContext.setMutator(entity -> {
            if (entity != null) {
                T value = oldMutator.apply(entity);
                addBucketId(value);
                return value;
            }
            return null;
        });

        val oldSaver = createOrUpdateInLockedContext.getSaver();
        createOrUpdateInLockedContext.setSaver((T entity) -> {
            addBucketId(entity);
            return oldSaver.apply(entity);
        });
        return null;
    }

    @Override
    public <T, R> Void visit(Select<T, R> select) {
        return null;
    }

    private <T> void addBucketId(T entity) {
        if (Objects.isNull(entity)) {
            return;
        }

        val entitymeta = initialisedEntityMeta.get(entity.getClass().getName());
        if (Objects.isNull(entitymeta)) {
            return;
        }

        val bucketKeyField = entitymeta.getBucketKeyField();
        val shardingKeyField = entitymeta.getShardingKeyField();
        if (Objects.isNull(bucketKeyField) || Objects.isNull(shardingKeyField)) {
            return;
        }
        val shardingKey = (String) resolveFieldData(entity, shardingKeyField).toString();
        val bucketId = this.bucketIdExtractor.bucketId(this.tenantId, shardingKey);

        try {
            bucketKeyField.setAccessible(true);
            bucketKeyField.set(entity, bucketId);
        } catch (IllegalAccessException e) {
            log.error("Error setting field {}", bucketKeyField.getName(), e);
            throw new IllegalArgumentException(e);
        }
    }

    private <T> Object resolveFieldData(T entity, Field field) {
        try {
            field.setAccessible(true);
            return field.get(entity);
        } catch (IllegalAccessException e) {
            log.error("Error resolving field {}", field.getName(), e);
            throw new IllegalArgumentException(e);
        }
    }
}
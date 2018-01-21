package io.dropwizard.sharding.dao;

import com.google.common.base.Preconditions;
import io.dropwizard.sharding.sharding.BucketIdExtractor;
import io.dropwizard.sharding.sharding.ShardManager;
import io.dropwizard.sharding.sharding.TransformedField;
import io.dropwizard.sharding.transformer.DataPackingManager;
import io.dropwizard.sharding.transformer.TransformedPair;
import io.dropwizard.sharding.transformer.Transformer;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.ClassUtils;
import org.apache.commons.lang3.reflect.FieldUtils;
import org.hibernate.SessionFactory;
import org.hibernate.criterion.DetachedCriteria;

import javax.persistence.Transient;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * This dao is an extension of the {@link LookupDao}, which has the ability to mark any data item as {@link TransformedField}.
 * A {@link TransformedField} is a field which will be Transformed using a {@link Transformer}
 * <p>
 * <b>Note:</b>
 * - The entity must have only one field marked for transformation
 * - If multiple fields exist, you may chose to wrap create a compound object housing the multiple fields.
 * - The field needs to be annotated with {@link TransformedField} and {@link Transient}
 *
 * @param <T> Something that extends TransformationBase (main data)
 * @param <D> Type of data to be transformed
 * @param <E> Type of transformed data, once D is transformed
 * @param <M> Type of transformation meta, once D is transformed
 * @author tushar.naik
 * @version 1.0  14/11/17 - 7:12 PM
 */
@Slf4j
public class TransformedLookupDao<T extends TransformationBase<E, M>, D, E, M>
        extends LookupDao<T> implements DataPackingManager<T>, Transformer<D, E, M> {

    /* transformer that has a single type of data and transformationMeta for now (need to change this) */
    private final Transformer<D, E, M> transformer;

    /* field which is going to be transformed */
    private final Field transformedField;

    public TransformedLookupDao(List<SessionFactory> sessionFactories, Class<T> entityClass,
                                ShardManager shardManager,
                                BucketIdExtractor<String> bucketIdExtractor,
                                Transformer<D, E, M> transformer) {
        super(sessionFactories, entityClass, shardManager, bucketIdExtractor);
        this.transformer = transformer;

        /* check if entityClass has an TransformationBase */
        Preconditions.checkArgument(ClassUtils.isAssignable(entityClass, TransformationBase.class), entityClass.getSimpleName()
                + " must extend" + TransformationBase.class.getSimpleName());

        /* ensure a single @TransformedField field in entityClass */
        Field[] transformedFields = FieldUtils.getFieldsWithAnnotation(entityClass, TransformedField.class);
        Preconditions.checkArgument(transformedFields.length != 0, "At least one field needs to be annotated with @TransformedField");
        Preconditions.checkArgument(transformedFields.length == 1, "Only one field can be annotated with @TransformedField");

        /* ensure that that field is @Transient (should not be persisted in DB) */
        transformedField = transformedFields[0];
        Transient transientAnnotation = transformedField.getAnnotation(Transient.class);
        Preconditions.checkArgument(transientAnnotation != null, "TransformedField must be annotated with @javax.persistence.Transient");

        if (!transformedField.isAccessible()) {
            try {
                transformedField.setAccessible(true);
            } catch (SecurityException e) {
                log.error("Error making transformation field accessible for LookupDao. Use a public method and mark that as @TransformedField", e);
                throw new IllegalArgumentException("Invalid class, DAO cannot be created.", e);
            }
        }
    }

    /**
     * use this function to get T for the lookup key, without retrieving the {@link TransformedField}
     * This is for cases when you have no reason to access the {@link TransformedField} and don't want to incur unnecessary cost of retrieving it
     *
     * @param key lookup key
     * @return optionally wrapped data
     * @throws Exception db errors while performing the get
     */
    public Optional<T> getTransformed(String key) throws Exception {
        return Optional.ofNullable(super.get(key, Function.identity()));
    }

    @Override
    public Optional<T> get(String key) throws Exception {
        Optional<T> optional = super.get(key);
        return optional.map(this::unPack);
    }

    public Optional<D> getData(String key) throws Exception {
        Optional<T> optional = super.get(key);
        return optional.map(this::retrieve);
    }

    @Override
    public <U> U get(String key, Function<T, U> handler) throws Exception {
        return super.get(key, t -> handler.apply(unPack(t)));
    }

    @Override
    public Optional<T> save(T entity) throws Exception {
        return super.save(pack(entity));
    }

    @Override
    public <U> U save(T entity, Function<T, U> handler) throws Exception {
        Function<T, U> unpackAndHandle = t -> handler.apply(unPack(t));
        return super.save(pack(entity), unpackAndHandle);
    }

    @Override
    public boolean updateInLock(String id, Function<Optional<T>, T> updater) {
        Function<Optional<T>, T> unpackUpdateAndPack = t -> {
            if (t.isPresent()) {
                return pack(updater.apply(Optional.of(unPack(t.get()))));
            }
            return null;
        };
        return super.updateInLock(id, unpackUpdateAndPack);
    }

    @Override
    public boolean update(String id, Function<Optional<T>, T> updater) {
        Function<Optional<T>, T> unpackUpdateAndPack = t -> {
            if (t.isPresent()) {
                return pack(updater.apply(Optional.of(unPack(t.get()))));
            }
            return null;
        };
        return super.update(id, unpackUpdateAndPack);
    }

    public boolean updateData(String id, Function<Optional<D>, D> updater) {
        /* the updater function needs to be applied after the data is retrieved */
        Function<Optional<T>, T> unpackUpdateAndPack = t -> {
            if (t.isPresent()) {
                return pack(t.get(), updater.apply(Optional.ofNullable(retrieve(t.get()))));
            }
            return null;
        };
        return super.update(id, unpackUpdateAndPack);
    }

    @Override
    public LookupDao.LockedContext<T> saveAndGetExecutor(T entity) {
        return super.saveAndGetExecutor(pack(entity));
    }

    @Override
    public List<T> scatterGather(DetachedCriteria criteria) {
        return unPackAll(super.scatterGather(criteria));
    }

    @Override
    public List<T> get(List<String> keys) {
        List<T> ts = super.get(keys);
        return unPackAll(ts);
    }

    @Override
    public List<T> unPackAll(List<T> ts) {
        for (T t : ts) {
            unPack(t);
        }
        return ts;
    }

    @Override
    @SneakyThrows
    public T unPack(T entity) {
        if (entity == null) {
            return null;
        }
        Object retrieve = retrieve(entity);
        transformedField.set(entity, retrieve);
        return entity;
    }

    @Override
    @SneakyThrows
    public T pack(T entity) {
        if (entity == null) {
            return null;
        }
        D o = (D) transformedField.get(entity);
        return pack(entity, o);
    }

    @SneakyThrows
    @Override
    public TransformedPair<E, M> transform(D data) {
        if (data == null) {
            return null;
        }
        return transformer.transform(data);
    }

    @Override
    public D retrieve(E transformedData, M transformationMeta) throws Exception {
        if (transformedData == null) {
            return null;
        }
        return transformer.retrieve(transformedData, transformationMeta);
    }

    @SneakyThrows
    private D retrieve(T entity) {
        if (entity == null) {
            return null;
        }
        return this.retrieve(entity.getTransformedData(), entity.getTransformationMeta());
    }

    private T pack(T entity, D data) {
        if (entity == null) {
            return null;
        }
        TransformedPair<E, M> transformedPair = transform(data);
        if (data != null) {
            entity.setTransformedData(transformedPair.getTransformedData());
            entity.setTransformationMeta(transformedPair.getTransformationMeta());
        }
        return entity;
    }
}

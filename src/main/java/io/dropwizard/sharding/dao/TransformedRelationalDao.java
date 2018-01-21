package io.dropwizard.sharding.dao;

import com.google.common.base.Preconditions;
import io.dropwizard.sharding.sharding.BucketIdExtractor;
import io.dropwizard.sharding.sharding.TransformedField;
import io.dropwizard.sharding.sharding.ShardManager;
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
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * This dao is an extension of the {@link RelationalDao}, which has the ability to mark any data item as {@link TransformedField}.
 * A {@link TransformedField} is a field which will be Transformed using a {@link Transformer}
 * <p>
 * <b>Note:</b>
 * - The entity must have only one field marked for transformation
 * - If multiple fields exist, you may chose to wrap create a compound object housing the multiple fields.
 * - The field needs to be annotated with {@link TransformedField} and {@link Transient}
 *
 * @param <T> Something that extends {@link TransformationBase} (main data)
 * @param <D> Type of data to be transformed
 * @param <E> Type of transformed data (and transformationMeta), once D is transformed
 * @param <M> Type of transformation meta, once D is transformed
 * @author tushar.naik
 * @version 1.0  14/11/17 - 7:12 PM
 */
@Slf4j
public class TransformedRelationalDao<T extends TransformationBase<E, M>, D, E, M>
        extends RelationalDao<T> implements DataPackingManager<T>, Transformer<D, E, M> {

    /* transformer that has a single type of data and transformationMeta for now (need to change this) */
    private final Transformer<D, E, M> transformer;

    /* field which is going to be transformed */
    private final Field transformedField;

    public TransformedRelationalDao(List<SessionFactory> sessionFactories, Class<T> entityClass,
                                    ShardManager shardManager,
                                    BucketIdExtractor<String> bucketIdExtractor,
                                    Transformer<D, E, M> transformer) {
        super(sessionFactories, entityClass, shardManager, bucketIdExtractor);
        this.transformer = transformer;

        /* check if entityClass has a TransformationBase */
        Preconditions.checkArgument(ClassUtils.isAssignable(entityClass, TransformationBase.class), entityClass.getSimpleName()
                + " must extend" + TransformationBase.class.getSimpleName());

        /* ensure a single @TransformedField field in entityClass */
        Field[] fields = FieldUtils.getFieldsWithAnnotation(entityClass, TransformedField.class);
        Preconditions.checkArgument(fields.length != 0, "At least one field needs to be annotated with @TransformedField");
        Preconditions.checkArgument(fields.length == 1, "Only one field can be annotated with @TransformedField");

        /* ensure that that field is @Transient (should not be persisted in DB) */
        transformedField = fields[0];
        Transient transientAnnotation = transformedField.getAnnotation(Transient.class);
        Preconditions.checkArgument(transientAnnotation != null, "TransformedField must be annotated with @javax.persistence.Transient");

        if (!transformedField.isAccessible()) {
            try {
                transformedField.setAccessible(true);
            } catch (SecurityException e) {
                log.error("Error making transformation field accessible for RelationalDao. Use a public method and mark that as @TransformedField", e);
                throw new IllegalArgumentException("Invalid class, DAO cannot be created.", e);
            }
        }

    }

    /**
     * use this function to get T for the parent key and key, without retrieving the {@link TransformedField}
     * This is for cases when you have no reason to access the {@link TransformedField} and don't want to incur unnecessary cost of retrieving it
     *
     * @param parentKey parent key
     * @param key       key
     * @return optionally wrapped data
     * @throws Exception db errors while performing the get
     */
    public T getTransformed(String parentKey, Object key) throws Exception {
        return super.get(parentKey, key, Function.identity());
    }

    @Override
    public Optional<T> get(String parentKey, Object key) throws Exception {
        Optional<T> optional = super.get(parentKey, key);
        return optional.map(this::unPack);
    }

    @Override
    public <U> U get(String parentKey, Object key, Function<T, U> function) throws Exception {
        return super.get(parentKey, key, t -> function.apply(unPack(t)));
    }

    @Override
    public Optional<T> save(String parentKey, T entity) throws Exception {
        return super.save(parentKey, pack(entity));
    }

    @Override
    public <U> U save(String parentKey, T entity, Function<T, U> handler) throws Exception {
        Function<T, U> unpackAndHandle = t -> handler.apply(unPack(t));
        return super.save(parentKey, pack(entity), unpackAndHandle);
    }

    @Override
    public boolean saveAll(String parentKey, Collection<T> entities) throws Exception {
        return super.saveAll(parentKey, entities.stream().map(this::pack).collect(Collectors.toList()));
    }

    @Override
    public boolean update(String parentKey, Object id, Function<T, T> updater) {
        return super.update(parentKey, id, t -> pack(updater.apply(unPack(t))));
    }

    @Override
    public boolean update(String parentKey, DetachedCriteria criteria, Function<T, T> updater) {
        return super.update(parentKey, criteria, t -> pack(updater.apply(unPack(t))));
    }

    public boolean updateData(String parentKey, DetachedCriteria criteria, Function<D, D> updater) {
        /* the updater function needs to be applied after the data is retrieved */
        return super.update(parentKey, criteria, t -> pack(t, updater.apply(retrieve(t))));
    }

    @Override
    public boolean updateAll(String parentKey, int start, int numRows, DetachedCriteria criteria,
                             Function<T, T> updater) {
        return super.updateAll(parentKey, start, numRows, criteria, t -> pack(updater.apply(unPack(t))));
    }

    @Override
    public List<T> select(String parentKey, DetachedCriteria criteria) throws Exception {
        return select(parentKey, criteria, 0, 10);
    }

    @Override
    public List<T> select(String parentKey, DetachedCriteria criteria, int first, int numResults) throws Exception {
        return select(parentKey, criteria, first, numResults, Function.identity());
    }

    @Override
    public <U> U select(String parentKey, DetachedCriteria criteria, Function<List<T>, U> handler) throws Exception {
        return select(parentKey, criteria, 0, 10, handler);
    }

    @Override
    public <U> U select(String parentKey, DetachedCriteria criteria, int first, int numResults,
                        Function<List<T>, U> handler) throws Exception {
        Function<List<T>, U> newFunction = entities -> {
            entities.forEach(this::unPack);
            return handler.apply(entities);
        };
        return super.select(parentKey, criteria, first, numResults, newFunction);
    }

    /**
     * return data post retrieval
     */
    public List<D> selectData(String parentKey, DetachedCriteria criteria, int first, int numResults) throws Exception {
        return super.select(parentKey, criteria, first, numResults, Function.identity())
                    .stream()
                    .map(this::retrieve)
                    .collect(Collectors.toList());
    }

    @Override
    public List<T> scatterGather(DetachedCriteria criteria) {
        return scatterGather(criteria, 0, 10);
    }

    @Override
    public List<T> scatterGather(DetachedCriteria criteria, int start, int numRows) {
        List<T> entities = super.scatterGather(criteria, start, numRows);
        entities.forEach(this::unPack);
        return entities;
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
        return retrieve(entity.getTransformedData(), entity.getTransformationMeta());
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

package io.dropwizard.sharding.transformer;

import io.dropwizard.sharding.dao.TransformationBase;

import java.util.List;

/**
 * @author tushar.naik
 * @version 1.0  16/11/17 - 12:03 PM
 */
public interface DataPackingManager<T extends TransformationBase> {
    List<T> unPackAll(List<T> ts);

    T unPack(T entity);

    T pack(T entity);
}

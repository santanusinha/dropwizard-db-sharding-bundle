package io.dropwizard.sharding.transformer;

/**
 * This is a transformation module, that is responsible for
 * -> transforming some form of Data {@link D} to an {@link TransformedPair} of {@link E} and {@link M}
 * -> The same may be reused to get back the {@link D}
 *
 * @param <D> Data
 * @param <E> Transformed Data
 * @param <M> Meta
 * @author tushar.naik
 * @version 1.0  14/11/17 - 10:48 PM
 */
public interface Transformer<D, E, M> {
    /**
     * transform data to some transformed pair
     *
     * @param data data to be transformed
     * @return pair of transformationMeta and transformedData
     * @throws Exception during transformation
     */
    TransformedPair<E, M> transform(D data) throws Exception;

    /**
     * transform data to some transformed pair
     *
     * @param transformedData transformed data to
     * @return un-transformed data
     * @throws Exception during transformation
     */
    D retrieve(E transformedData, M transformationMeta) throws Exception;
}

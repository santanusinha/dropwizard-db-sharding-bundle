package io.dropwizard.sharding.transformer.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.dropwizard.sharding.transformer.TransformedPair;
import io.dropwizard.sharding.transformer.Transformer;
import lombok.AllArgsConstructor;
import lombok.Builder;

import java.util.Base64;

/**
 * @author tushar.naik
 * @version 1.0  21/11/17 - 8:47 PM
 */
@AllArgsConstructor
@Builder
public class Base64Transformer<T> implements Transformer<T, String, String> {

    private static final String BASE_64 = "base64String";

    private Class<T> dataClass;
    private ObjectMapper mapper;

    @Override
    public TransformedPair<String, String> transform(T data) throws Exception {
        byte[] encodedData = Base64.getEncoder().encode(mapper.writeValueAsBytes(data));
        return new TransformedPair<>(new String(encodedData), BASE_64);
    }

    @Override
    public T retrieve(String transformedData, String transformationMeta) throws Exception {
        byte[] decodedData = Base64.getDecoder().decode(transformedData);
        return mapper.readValue(decodedData, dataClass);
    }
}

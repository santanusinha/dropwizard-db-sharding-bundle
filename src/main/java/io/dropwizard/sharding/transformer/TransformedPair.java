package io.dropwizard.sharding.transformer;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * @author tushar.naik
 * @version 1.0  15/11/17 - 6:03 PM
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class TransformedPair<V, M> {
    private V transformedData;
    private M transformationMeta;
}

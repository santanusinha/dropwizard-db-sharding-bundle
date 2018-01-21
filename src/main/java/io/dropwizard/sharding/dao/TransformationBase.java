package io.dropwizard.sharding.dao;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import javax.persistence.*;

/**
 * @author tushar.naik
 * @version 1.0  14/11/17 - 11:21 PM
 */
@Data
@MappedSuperclass
@NoArgsConstructor
@AllArgsConstructor
public abstract class TransformationBase<E, M> {

    @Column(name = "transformed_data", length = 32000)
    private E transformedData;

    @Column(name = "transformation_meta")
    private M transformationMeta;
}

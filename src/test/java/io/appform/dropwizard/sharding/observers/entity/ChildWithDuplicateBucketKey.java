package io.appform.dropwizard.sharding.observers.entity;

import io.appform.dropwizard.sharding.sharding.BucketKey;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.FieldNameConstants;



@Entity
@Table(name = "child")
@FieldNameConstants
@Getter
@Setter
@ToString
@RequiredArgsConstructor
public class ChildWithDuplicateBucketKey extends BaseChild {

    @Column
    private String value;

    @Column
    @BucketKey
    private int bucketKey;
}

package io.appform.dropwizard.sharding.observers.entity;

import io.appform.dropwizard.sharding.sharding.BucketKey;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.FieldNameConstants;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Table;

@Entity
@Table(name = "simple_children")
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

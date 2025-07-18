package io.appform.dropwizard.sharding.observers.entity;

import io.appform.dropwizard.sharding.sharding.BucketKey;
import io.appform.dropwizard.sharding.sharding.ShardingKey;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.FieldNameConstants;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Table;

@Entity
@Table(name = "child")
@FieldNameConstants
@Getter
@Setter
@ToString
@RequiredArgsConstructor
public class BaseChild {
    @Column
    @BucketKey
    private int bucketKey;

    @Column
    @ShardingKey
    private String parent;
}

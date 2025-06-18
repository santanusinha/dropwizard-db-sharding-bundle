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
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.Table;

@Entity
@Table(name = "heirarchial_child")
@FieldNameConstants
@Getter
@Setter
@ToString
@RequiredArgsConstructor
public class HeirarchialBaseChild {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private long id;

    @Column
    @BucketKey
    private int bucketKey;

    @Column
    @ShardingKey
    private String parent;
}


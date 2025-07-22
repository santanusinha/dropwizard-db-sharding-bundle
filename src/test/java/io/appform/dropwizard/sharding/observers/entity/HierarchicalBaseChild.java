package io.appform.dropwizard.sharding.observers.entity;

import io.appform.dropwizard.sharding.sharding.BucketKey;
import io.appform.dropwizard.sharding.sharding.ShardingKey;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.FieldNameConstants;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "hierarchical_child")
@FieldNameConstants
@Getter
@Setter
@ToString
@RequiredArgsConstructor
public class HierarchicalBaseChild {
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


package io.appform.dropwizard.sharding.observers.entity;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.FieldNameConstants;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "hierarchical_child")
@FieldNameConstants
@Getter
@Setter
@ToString
@RequiredArgsConstructor
public class HierarchicalBaseChildImpl extends HierarchicalBaseChild {

    @Column
    private String value;
}

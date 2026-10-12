package io.appform.dropwizard.sharding.observers.entity;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.FieldNameConstants;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.util.ArrayList;
import java.util.Collection;

@Entity
@Table(name = "hierarchical_child")
@FieldNameConstants
@Getter
@Setter
@ToString
@RequiredArgsConstructor
public class HierarchicalChildImpl extends HierarchicalBaseChildImpl {

    @Transient
    private Collection<SimpleChild> children = new ArrayList<>();
}

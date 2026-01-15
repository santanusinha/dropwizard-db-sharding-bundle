package io.appform.dropwizard.sharding.observers.entity;

import jakarta.persistence.DiscriminatorValue;
import jakarta.persistence.Entity;
import jakarta.persistence.Transient;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.ArrayList;
import java.util.Collection;

@Data
@EqualsAndHashCode(callSuper = true)
@Entity
@DiscriminatorValue("CHILD_IMPL")
public class HierarchicalChildImpl extends HierarchicalBaseChildImpl {

    @Transient
    private Collection<SimpleChild> children = new ArrayList<>();
}

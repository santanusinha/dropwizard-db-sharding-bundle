package io.appform.dropwizard.sharding.observers.entity;

import jakarta.persistence.Column;
import jakarta.persistence.DiscriminatorValue;
import jakarta.persistence.Entity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@Entity
@DiscriminatorValue("BASE_IMPL")
public class HierarchicalBaseChildImpl extends HierarchicalBaseChild {

    @Column(name = "`value`")
    private String value;

}

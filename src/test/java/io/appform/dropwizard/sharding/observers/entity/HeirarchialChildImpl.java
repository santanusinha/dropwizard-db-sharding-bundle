package io.appform.dropwizard.sharding.observers.entity;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.FieldNameConstants;

import javax.persistence.Entity;
import javax.persistence.Table;
import javax.persistence.Transient;
import java.util.ArrayList;
import java.util.Collection;

@Entity
@Table(name = "heirarchial_child")
@FieldNameConstants
@Getter
@Setter
@ToString
@RequiredArgsConstructor
public class HeirarchialChildImpl extends HeirarchialBaseChildImpl {

    @Transient
    private Collection<SimpleChild> children = new ArrayList<>();
}

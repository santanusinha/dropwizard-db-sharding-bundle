package io.appform.dropwizard.sharding.observers.entity;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.FieldNameConstants;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Table;

@Entity
@Table(name = "heirarchial_child")
@FieldNameConstants
@Getter
@Setter
@ToString
@RequiredArgsConstructor
public class HeirarchialBaseChildImpl extends HeirarchialBaseChild {

    @Column
    private String value;
}

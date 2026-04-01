package io.appform.dropwizard.sharding.dao.locktest;

import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import javax.persistence.Column;
import javax.persistence.DiscriminatorColumn;
import javax.persistence.Entity;
import javax.persistence.EnumType;
import javax.persistence.Enumerated;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.Inheritance;
import javax.persistence.InheritanceType;
import javax.persistence.Table;

@Data
@Entity
@Table(name = "MAIN_TABLE")
@Inheritance(strategy = InheritanceType.SINGLE_TABLE)
@DiscriminatorColumn(name = "type")
@Slf4j
public abstract class ParentClass {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private int id;

    @Column(name = "PARENT_KEY", nullable = false)
    private String parentKey;

    @Column(name = "PARENT_COLUMN", nullable = false)
    private String parentColumn;

    @Enumerated(EnumType.STRING)
    @Column(name = "TYPE", insertable = false, updatable = false)
    private Category type;

    protected ParentClass(final Category type) {
        this.type = type;
    }

    public ParentClass(final Category type,
                       final String parentKey,
                       final String parentColumn) {
        this.type = type;
        this.parentKey = parentKey;
        this.parentColumn = parentColumn;
    }
}
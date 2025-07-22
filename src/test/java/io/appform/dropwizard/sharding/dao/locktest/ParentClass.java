package io.appform.dropwizard.sharding.dao.locktest;

import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import jakarta.persistence.Column;
import jakarta.persistence.DiscriminatorColumn;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Inheritance;
import jakarta.persistence.InheritanceType;
import jakarta.persistence.Table;

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
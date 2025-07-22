package io.appform.dropwizard.sharding.dao.locktest;

import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;

import jakarta.persistence.Column;
import jakarta.persistence.DiscriminatorValue;
import jakarta.persistence.Entity;

@Data
@EqualsAndHashCode(callSuper = true)
@Entity
@DiscriminatorValue(Category.CATEGORYB_TEXT)
public class ChildBClass extends ParentClass {

    @Column(name = "CHILD_B_COLUMN")
    private String childBColumn;

    protected ChildBClass() {
        super(Category.CATEGORYB);
    }

    @Builder
    public ChildBClass(final String childBColumn,
                       final String parentKey,
                       final String parentColumn) {
        super(Category.CATEGORYB, parentKey, parentColumn);
        this.childBColumn = childBColumn;
    }
}
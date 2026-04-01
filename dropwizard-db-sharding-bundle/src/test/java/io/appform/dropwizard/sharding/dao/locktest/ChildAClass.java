package io.appform.dropwizard.sharding.dao.locktest;

import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;

import javax.persistence.Column;
import javax.persistence.DiscriminatorValue;
import javax.persistence.Entity;

@Data
@EqualsAndHashCode(callSuper = true)
@Entity
@DiscriminatorValue(Category.CATEGORYA_TEXT)
public class ChildAClass extends ParentClass {

    @Column(name = "CHILD_A_COLUMN")
    private String childAColumn;

    protected ChildAClass() {
        super(Category.CATEGORYA);
    }

    @Builder
    public ChildAClass(final String childAColumn,
                       final String parentKey,
                       final String parentColumn) {
        super(Category.CATEGORYA, parentKey, parentColumn);
        this.childAColumn = childAColumn;
    }
}
package io.appform.dropwizard.sharding.scroll;

import lombok.SneakyThrows;
import lombok.val;

import java.lang.reflect.Field;
import java.util.Comparator;

/**
 * A {@link Comparator} that is used by scroll api for sorting results from multiple shards
 */
public class FieldComparator<T> implements Comparator<ScrollResultItem<T>> {
    private final Field field;

    public FieldComparator(Field field) {
        this.field = field;
    }


    @Override
    @SneakyThrows
    @SuppressWarnings("unchecked")
    public int compare(ScrollResultItem<T> lhs, ScrollResultItem<T> rhs) {
        val lhsValue = (Comparable<T>) field.get(lhs.getData());
        val rhsValue = (T) field.get(rhs.getData());

        return lhsValue.compareTo(rhsValue);
    }
}

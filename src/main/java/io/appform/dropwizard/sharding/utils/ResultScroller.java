package io.appform.dropwizard.sharding.utils;

import org.hibernate.Criteria;
import org.hibernate.ScrollMode;
import org.hibernate.ScrollableResults;

import java.io.Closeable;
import java.util.Iterator;
import java.util.NoSuchElementException;

/**
 *
 */
public class ResultScroller<T> implements Iterator<T>, Closeable {
    private final ScrollableResults results;
    private boolean hasNext;

    public ResultScroller(ScrollableResults results) {
        this.results = results;
    }

    public static <T> ResultScroller<T> fromResults(ScrollableResults sr) {
        return new ResultScroller<>(sr);
    }

    public static <T> ResultScroller<T> fromCriteria(Criteria c) {
        return new ResultScroller<>(c.scroll(ScrollMode.FORWARD_ONLY));
    }

    @Override
    public void close() {
        results.close();
    }

    @Override
    public boolean hasNext() {
        hasNext = results.next();
        return hasNext;
    }

    @Override
    @SuppressWarnings("unchecked")
    public T next() {
        if(hasNext) {
            return (T) results.get(0);
        }
        throw new NoSuchElementException("No result available");
    }

}

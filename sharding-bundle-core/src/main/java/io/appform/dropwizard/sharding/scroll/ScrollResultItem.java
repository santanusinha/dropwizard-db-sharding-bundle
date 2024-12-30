package io.appform.dropwizard.sharding.scroll;

import lombok.Value;

/**
 * A wrapper for individual list item returned by a query per shard.
 * For internal use by the scroller.
 */
@Value
public class ScrollResultItem<T> {
    T data;
    int shardIdx;
}

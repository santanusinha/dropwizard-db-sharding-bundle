package io.appform.dropwizard.sharding.scroll;

import lombok.Value;

import java.util.List;

/**
 * Results of a scroll operation
 */
@Value
public class ScrollResult<T> {
    ScrollPointer pointer;
    List<T> result;
}

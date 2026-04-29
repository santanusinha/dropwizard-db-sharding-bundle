package io.appform.dropwizard.sharding.scroll;

import lombok.ToString;
import lombok.Value;

import java.io.Serializable;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Private data structure for scroll point
 */

@Value
@ToString
public class ScrollPointer implements Serializable {
    public enum Direction {
        UP,
        DOWN
    }

    private static final long serialVersionUID = -3317823670664673152L;

    Direction direction;
    Map<Integer, Integer> pointPerShard = new ConcurrentHashMap<>();

    public ScrollPointer put(int shard, Integer offset) {
        pointPerShard.put(shard, offset);
        return this;
    }

    public int advance(int shard, int advanceBy) {
        return pointPerShard.compute(shard, (key, existing) -> (null == existing ? 0 : existing) + advanceBy);
    }

    public int getCurrOffset(int shard) {
        return pointPerShard.computeIfAbsent(shard, key -> 0);
    }
}

package io.appform.dropwizard.sharding;

import io.appform.dropwizard.sharding.sharding.impl.ConsistentHashBucketIdExtractor;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class MapDaoHolder<T> implements DaoHolder<T> {

    private final Map<Integer, HolderValue> hashMap;

    public MapDaoHolder(final Map<Integer, List<T>> hashMap) {
        this.hashMap = hashMap.entrySet().stream().collect(Collectors.toMap(
                Map.Entry::getKey,
                entry -> new HolderValue(entry.getValue())));
    }

    @Override
    public T get(final int shard, final String key) {
        return hashMap.get(shard).get(key);
    }

    @Override
    public T get(final int shard) {
        return hashMap.get(shard).get();
    }

    @Override
    public int size() {
        return hashMap.size();
    }

    class HolderValue {
        private final List<T> elements;
        private final ConsistentHashBucketIdExtractor<String> bucketIdExtractor;

        public HolderValue(final List<T> elements) {
            this.elements = elements;
            this.bucketIdExtractor = new ConsistentHashBucketIdExtractor<>(elements.size());
        }

        public T get(String key){
            return elements.get(bucketIdExtractor.bucketId(key));
        }

        public T get(){
            return elements.get(0);
        }
    }
}

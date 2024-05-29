package io.appform.dropwizard.sharding.utils;

import io.appform.dropwizard.sharding.sharding.BucketIdExtractor;

public class BucketCalculator<T> {
    private final BucketIdExtractor<T> extractor;

    public BucketCalculator(BucketIdExtractor<T> extractor) {
        this.extractor = extractor;
    }
    public Integer bucketId(T key) {
        return extractor.bucketId(key);
    }
}

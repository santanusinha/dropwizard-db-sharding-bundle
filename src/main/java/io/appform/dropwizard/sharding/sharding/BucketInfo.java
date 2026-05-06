package io.appform.dropwizard.sharding.sharding;

import lombok.Builder;
import lombok.Getter;

/***
 * This class is used to store the bucket information for a field annotated with @BucketKey.
 * It contains the key, which is relational column name and the value of the eligible bucket.
 */
@Builder
@Getter
public class BucketInfo {
    private String key;
    private int value;
}

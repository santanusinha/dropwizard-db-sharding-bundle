package io.appform.dropwizard.sharding.sharding;

import lombok.Builder;
import lombok.Getter;

@Builder
@Getter
public class BucketInfo {
    private String key;
    private int value;
}

package io.appform.dropwizard.sharding.sharding;

import lombok.Builder;
import lombok.Getter;

@Builder
@Getter
public class BucketInfo {
    private int value;
    private String key;
}

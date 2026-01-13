package io.appform.dropwizard.sharding.sharding;

import lombok.Builder;
import lombok.Getter;

@Builder
@Getter
public class BucketKeyInfo {
    private String fieldName;
    private int value;
}

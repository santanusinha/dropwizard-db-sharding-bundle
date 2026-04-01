package io.appform.dropwizard.sharding.sharding;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.lang.invoke.MethodHandle;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EntityMeta {
    private MethodHandle bucketKeySetter;
    private MethodHandle shardingKeyGetter;
}
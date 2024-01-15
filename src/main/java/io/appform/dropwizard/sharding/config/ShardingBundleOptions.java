package io.appform.dropwizard.sharding.config;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ShardingBundleOptions {

    /*
     Whether to skip transaction in a read only operation.
     */
    private boolean skipReadOnlyTransaction = false;
}

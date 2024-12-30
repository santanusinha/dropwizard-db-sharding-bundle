package com.phonepe.platform.junit.extensions.dbsharding;

import io.appform.dropwizard.sharding.BalancedDBShardingBundle;
import io.appform.dropwizard.sharding.DBShardingBundle;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DBBundle {

    private DBShardingBundle<DBShardingExtension.TestConfig> shardingBundle;
    private BalancedDBShardingBundle<DBShardingExtension.TestConfig> balancedDBShardingBundle;


}

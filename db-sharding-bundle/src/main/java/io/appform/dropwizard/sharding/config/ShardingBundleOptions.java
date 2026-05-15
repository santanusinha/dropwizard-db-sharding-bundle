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
    @Builder.Default
    private boolean skipNativeHealthcheck = true;

    @Builder.Default
    private boolean encryptionSupportEnabled = false;

    private String encryptionAlgorithm;

    private String encryptionPassword;

    private String encryptionIv;

    private int shardInitializationParallelism;

}

package io.appform.dropwizard.sharding;

import lombok.Builder;
import lombok.extern.slf4j.Slf4j;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
public class ShardInfoProvider {

    private final String namespace;
    private static final String SHARD_NAMING_FORMAT = "connectionpool-%s-%d";
    private static final String SHARD_NAMING_REGEX = "connectionpool-(\\w+)-(\\d+)";
    private static final Pattern SHARD_NAMING_PATTERN = Pattern.compile(SHARD_NAMING_REGEX);


    @Builder
    public ShardInfoProvider(final String namespace) {
        this.namespace = namespace;
    }

    public int shardId(String name) {
        Matcher matcher = SHARD_NAMING_PATTERN.matcher(name);
        if (matcher.matches()) {
            return Integer.parseInt(matcher.group(2));
        }
        return -1;
    }

    public String namespace(String name) {
        Matcher matcher = SHARD_NAMING_PATTERN.matcher(name);
        if (matcher.matches()) {
            return matcher.group(1);
        }
        return null;
    }

    public String shardName(int shardId) {
        return String.format(SHARD_NAMING_FORMAT, namespace, shardId);
    }


}

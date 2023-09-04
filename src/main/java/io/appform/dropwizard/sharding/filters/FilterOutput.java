package io.appform.dropwizard.sharding.filters;

/**
 * Output for a filter
 */
public enum FilterOutput {
    /**
     * The filter has let the action through
     */
    PROCEED,

    /**
     * The action has been blocked
     */
    BLOCK
}

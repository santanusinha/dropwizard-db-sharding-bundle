package io.appform.dropwizard.sharding.observers;

import io.appform.dropwizard.sharding.utils.CopyFromParentUtils;

import java.util.List;

/**
 * Callback interface for handling field mismatches between parent and child entities
 * during {@code @CopyFromParent} processing.
 * <p>
 * Consumers implement this interface to define what happens when a mismatch is detected
 * (e.g. publish a Foxtrot event, log a warning, increment a metric).
 * <p>
 *
 * @see CopyFromParentObserver.Builder#mismatchListener
 */
@FunctionalInterface
public interface CopyFromParentMismatchListener {

    /**
     * Called when one or more field mismatches are detected between the parent and child entities.
     *
     * @param parent     the parent entity
     * @param child      the child entity
     * @param mismatches non-empty list of detected field mismatches
     */
    void onMismatches(Object parent, Object child, List<CopyFromParentUtils.FieldMismatch> mismatches);
}

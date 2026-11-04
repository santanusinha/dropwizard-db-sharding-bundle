package io.appform.dropwizard.sharding.sanity.base;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Thread-safe event logger that records the chronological sequence of
 * JDBC and transaction lifecycle events during DAO operations.
 *
 * <p>Each event has a {@link #label} — either a well-known constant
 * (e.g., {@code GET_CONNECTION}, {@code COMMIT}) or a custom string
 * for any JDBC method call (e.g., {@code "prepareStatement"}, {@code "setAutoCommit"}).
 *
 * <p>Usage in tests:
 * <pre>
 *   DbEventTracker.clear();
 *   dao.get(key);
 *   List&lt;DbEvent&gt; events = DbEventTracker.getEvents();
 *
 *   // Filter by well-known events
 *   long commits = events.stream().filter(e -&gt; e.is(DbEvent.COMMIT)).count();
 *
 *   // Filter by custom label
 *   long prepares = events.stream().filter(e -&gt; e.is("prepareStatement")).count();
 * </pre>
 */
public class DbEventTracker {

    /**
     * Represents a single lifecycle event. Well-known events are provided as
     * constants. Custom events can be created with {@link #of(String)}.
     */
    @Getter
    @EqualsAndHashCode
    @ToString
    public static class DbEvent {
        // Well-known events
        public static final DbEvent BEGIN_TRANSACTION = new DbEvent("BEGIN_TRANSACTION");
        public static final DbEvent GET_CONNECTION = new DbEvent("GET_CONNECTION");
        public static final DbEvent COMMIT = new DbEvent("COMMIT");
        public static final DbEvent ROLLBACK = new DbEvent("ROLLBACK");
        public static final DbEvent RELEASE_CONNECTION = new DbEvent("RELEASE_CONNECTION");
        public static final DbEvent PREPARE_STATEMENT = new DbEvent("PREPARE_STATEMENT");
        public static final DbEvent CREATE_STATEMENT = new DbEvent("CREATE_STATEMENT");

        private final String label;

        private DbEvent(String label) {
            this.label = label;
        }

        /**
         * Creates a custom event with an arbitrary label.
         * Use this for JDBC methods not covered by the well-known constants.
         */
        public static DbEvent of(String label) {
            return new DbEvent(label);
        }

        /**
         * Checks if this event matches a given label string.
         */
        public boolean is(String label) {
            return this.label.equals(label);
        }

        /**
         * Checks if this event matches another DbEvent.
         */
        public boolean is(DbEvent other) {
            return this.label.equals(other.label);
        }
    }

    private static final List<DbEvent> events = Collections.synchronizedList(new ArrayList<>());

    /**
     * AND-latch for autoCommit state. Starts {@code true} (optimistic: assume autoCommit
     * is disabled). At each checkpoint (prepareStatement, createStatement, commit, rollback),
     * the proxy calls {@link #checkAutoCommit(boolean, String)}. If autoCommit is ever
     * {@code true} at any checkpoint, this latch permanently flips to {@code false}.
     *
     * <p>Assert with {@code assertTrue(DbEventTracker.wasAutoCommitDisabledThroughout())}
     * to verify autoCommit was disabled during the entire transaction lifecycle.
     */
    private static volatile boolean autoCommitDisabledThroughout = true;

    /**
     * Returns {@code true} only if autoCommit was disabled at EVERY checkpoint
     * since the last {@link #clear()}. If autoCommit was {@code true} at any
     * checkpoint, returns {@code false}.
     */
    public static boolean wasAutoCommitDisabledThroughout() {
        return autoCommitDisabledThroughout;
    }

    /**
     * AND-latch update: if {@code autoCommitValue} is {@code true} (meaning autoCommit
     * is ON, which is bad), the latch flips to {@code false} permanently, and an
     * {@code AUTOCOMMIT_VIOLATION_AT_<stage>} event is logged for diagnosability.
     *
     * @param autoCommitValue the current value of {@code connection.getAutoCommit()}
     * @param stage           the method name where this check was performed
     */
    public static void checkAutoCommit(boolean autoCommitValue, String stage) {
        if (autoCommitValue) {
            autoCommitDisabledThroughout = false;
            events.add(DbEvent.of("AUTOCOMMIT_VIOLATION_AT_" + stage));
        }
    }

    public static void log(DbEvent event) {
        events.add(event);
    }

    /**
     * Convenience: log a custom event by label string.
     */
    public static void log(String label) {
        events.add(DbEvent.of(label));
    }

    // resets the tracker for a new test case. Clears all events and resets the autoCommit latch.
    public static void clear() {
        events.clear();
        autoCommitDisabledThroughout = true;
    }

    public static List<DbEvent> getEvents() {
        return new ArrayList<>(events);
    }
}

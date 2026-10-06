package io.github.nitesh050.sched.common.api;

import io.github.nitesh050.sched.common.config.Mode;

/**
 * Receives scheduler events. Calls come from worker threads on the hot path, so
 * implementations must be cheap and must not share a contended counter between workers
 * (use per-worker, cache-line-padded counters and merge them on read).
 */
public interface MetricsSink {

    MetricsSink NOOP = new MetricsSink() {
    };

    default void taskSpawned(int worker, long taskId) {
    }

    default void taskStarted(int worker, long taskId) {
    }

    default void taskFinished(int worker, long taskId) {
    }

    /** A thief posted a steal request to a victim. */
    default void stealAttempted(int thief, int victim) {
    }

    /** A victim handed a task to a thief. Recorded by the victim. */
    default void stealSucceeded(int thief, int victim) {
    }

    /** A worker found its queues empty. */
    default void workerIdle(int worker) {
    }

    /** Recorded by the controller when it flips the mode flag. */
    default void modeSwitched(Mode from, Mode to) {
    }
}

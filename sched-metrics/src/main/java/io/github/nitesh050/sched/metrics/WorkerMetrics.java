package io.github.nitesh050.sched.metrics;

/**
 * Counters for one worker.
 *
 * @param stealAttempts  requests this worker posted as a thief
 * @param stealSuccesses tasks this worker handed over as a victim
 * @param idleEvents     times this worker went from busy to idle
 */
public record WorkerMetrics(
        int worker,
        long spawned,
        long started,
        long finished,
        long stealAttempts,
        long stealSuccesses,
        long idleEvents) {
}

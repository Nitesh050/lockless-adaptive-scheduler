package io.github.nitesh050.sched.common.api;

import io.github.nitesh050.sched.common.config.Mode;
import java.util.Arrays;

/**
 * Point-in-time view of the scheduler, taken without stopping the workers, so values are
 * approximate and not mutually consistent. Counters are cumulative since the run started;
 * the collector computes rates from the difference between two snapshots.
 *
 * <p>Note that no steals happen in {@link Mode#STATIC_ROUND_ROBIN}, so steal counters carry no
 * information in that mode: leaving round-robin must be decided from imbalance and idleness.
 */
public record SignalSnapshot(
        long timestampNanos,
        Mode mode,
        int[] queueLengths,
        int idleWorkers,
        long stealAttempts,
        long stealSuccesses,
        long tasksCompleted) {

    public SignalSnapshot {
        queueLengths = queueLengths.clone();
    }

    public int workerCount() {
        return queueLengths.length;
    }

    public int queueLength(int worker) {
        return queueLengths[worker];
    }

    @Override
    public int[] queueLengths() {
        return queueLengths.clone();
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof SignalSnapshot s
                && timestampNanos == s.timestampNanos
                && mode == s.mode
                && Arrays.equals(queueLengths, s.queueLengths)
                && idleWorkers == s.idleWorkers
                && stealAttempts == s.stealAttempts
                && stealSuccesses == s.stealSuccesses
                && tasksCompleted == s.tasksCompleted;
    }

    @Override
    public int hashCode() {
        return 31 * Long.hashCode(timestampNanos) + Arrays.hashCode(queueLengths);
    }

    @Override
    public String toString() {
        return "SignalSnapshot[t=" + timestampNanos + ", mode=" + mode
                + ", queues=" + Arrays.toString(queueLengths) + ", idle=" + idleWorkers
                + ", steals=" + stealSuccesses + "/" + stealAttempts
                + ", completed=" + tasksCompleted + "]";
    }
}

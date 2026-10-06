package io.github.nitesh050.sched.adaptive;

import io.github.nitesh050.sched.common.api.SignalSnapshot;

/**
 * Turns raw snapshots into {@link Signals}: queue imbalance, idle ratio, and per-interval
 * steal and completion counts (from the difference to the previous snapshot). Not thread-safe;
 * owned by the controller's monitor thread.
 */
public final class SignalCollector {

    private SignalSnapshot previous;

    public Signals collect(SignalSnapshot now) {
        int n = now.workerCount();
        long total = 0;
        int max = 0;
        for (int w = 0; w < n; w++) {
            int len = now.queueLength(w);
            total += len;
            max = Math.max(max, len);
        }
        double mean = n == 0 ? 0 : (double) total / n;
        double imbalance = mean == 0 ? 1.0 : max / mean;

        long attempts = now.stealAttempts();
        long successes = now.stealSuccesses();
        long completed = now.tasksCompleted();
        if (previous != null) {
            attempts -= previous.stealAttempts();
            successes -= previous.stealSuccesses();
            completed -= previous.tasksCompleted();
        }
        previous = now;

        return new Signals(
                now.timestampNanos(),
                now.mode(),
                n,
                imbalance,
                (int) Math.min(Integer.MAX_VALUE, total),
                n == 0 ? 0 : (double) now.idleWorkers() / n,
                attempts,
                successes,
                completed);
    }
}

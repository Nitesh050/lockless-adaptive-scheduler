package io.github.nitesh050.sched.engine;

import io.github.nitesh050.sched.common.api.SignalSnapshot;
import io.github.nitesh050.sched.common.api.SignalSource;

/** Reads the workers' queue sizes and counters without stopping or slowing them. */
final class EngineSignalSource implements SignalSource {

    private final WorkerPool pool;

    EngineSignalSource(WorkerPool pool) {
        this.pool = pool;
    }

    @Override
    public SignalSnapshot snapshot() {
        int n = pool.workerCount();
        WorkerStats stats = pool.stats();
        int[] lengths = new int[n];
        int idle = 0;
        for (int w = 0; w < n; w++) {
            lengths[w] = pool.worker(w).localSize();
            if (stats.get(w, WorkerStats.IDLE) != 0) {
                idle++;
            }
        }
        return new SignalSnapshot(
                System.nanoTime(),
                pool.modeFlag().currentMode(),
                lengths,
                idle,
                stats.sum(WorkerStats.STEAL_ATTEMPTS),
                stats.sum(WorkerStats.STEAL_SUCCESSES),
                stats.sum(WorkerStats.COMPLETED));
    }
}

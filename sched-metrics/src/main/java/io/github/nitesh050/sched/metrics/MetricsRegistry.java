package io.github.nitesh050.sched.metrics;

import io.github.nitesh050.sched.common.api.MetricsSink;
import io.github.nitesh050.sched.common.config.Mode;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;

/**
 * Event counters per worker. Each event is counted in the slot of the worker that reports it,
 * and each worker's slots sit on their own cache line, so workers never contend while
 * counting. {@link #report()} can be called at any time; mid-run it is approximate.
 */
public final class MetricsRegistry implements MetricsSink {

    private static final int SPAWNED = 0;
    private static final int STARTED = 1;
    private static final int FINISHED = 2;
    private static final int STEAL_ATTEMPTS = 3;
    private static final int STEAL_SUCCESSES = 4;
    private static final int IDLE_EVENTS = 5;
    /** 16 longs = 128 bytes, one Apple Silicon cache line. */
    private static final int STRIDE = 16;

    private final int workers;
    private final AtomicLongArray slots;
    private final AtomicLong modeSwitches = new AtomicLong();

    public MetricsRegistry(int workers) {
        if (workers < 1) {
            throw new IllegalArgumentException("workers must be >= 1");
        }
        this.workers = workers;
        this.slots = new AtomicLongArray((workers + 1) * STRIDE);
    }

    private void inc(int worker, int field) {
        slots.getAndIncrement((worker + 1) * STRIDE + field);
    }

    private long get(int worker, int field) {
        return slots.get((worker + 1) * STRIDE + field);
    }

    @Override
    public void taskSpawned(int worker, long taskId) {
        inc(worker, SPAWNED);
    }

    @Override
    public void taskStarted(int worker, long taskId) {
        inc(worker, STARTED);
    }

    @Override
    public void taskFinished(int worker, long taskId) {
        inc(worker, FINISHED);
    }

    @Override
    public void stealAttempted(int thief, int victim) {
        inc(thief, STEAL_ATTEMPTS);
    }

    @Override
    public void stealSucceeded(int thief, int victim) {
        inc(victim, STEAL_SUCCESSES);
    }

    @Override
    public void workerIdle(int worker) {
        inc(worker, IDLE_EVENTS);
    }

    @Override
    public void modeSwitched(Mode from, Mode to) {
        modeSwitches.incrementAndGet();
    }

    public MetricsReport report() {
        List<WorkerMetrics> rows = new ArrayList<>(workers);
        for (int w = 0; w < workers; w++) {
            rows.add(new WorkerMetrics(
                    w,
                    get(w, SPAWNED),
                    get(w, STARTED),
                    get(w, FINISHED),
                    get(w, STEAL_ATTEMPTS),
                    get(w, STEAL_SUCCESSES),
                    get(w, IDLE_EVENTS)));
        }
        return new MetricsReport(rows, modeSwitches.get());
    }
}

package io.github.nitesh050.sched.engine;

import java.util.concurrent.atomic.AtomicLongArray;

/**
 * Per-worker counters the engine needs for itself (signals, run results). Each worker writes
 * only its own slots, and slots of different workers sit on different cache lines, so
 * counting never makes workers contend. Other threads may read at any time.
 */
final class WorkerStats {

    static final int COMPLETED = 0;
    static final int SPAWNED = 1;
    /** Tasks run immediately by their creator because every queue it could use was full. */
    static final int INLINE = 2;
    static final int STEAL_ATTEMPTS = 3;
    static final int STEAL_SUCCESSES = 4;
    /** 1 while the worker has found its queues empty, 0 while it has work. */
    static final int IDLE = 5;

    /** 16 longs = 128 bytes, a full cache line on Apple Silicon (and two on x86). */
    private static final int STRIDE = 16;

    private final int workers;
    private final AtomicLongArray slots;

    WorkerStats(int workers) {
        this.workers = workers;
        // one extra stride of padding before the first worker's slots
        this.slots = new AtomicLongArray((workers + 1) * STRIDE);
    }

    private static int index(int worker, int field) {
        return (worker + 1) * STRIDE + field;
    }

    void increment(int worker, int field) {
        slots.getAndIncrement(index(worker, field));
    }

    void set(int worker, int field, long value) {
        slots.set(index(worker, field), value);
    }

    long get(int worker, int field) {
        return slots.get(index(worker, field));
    }

    long sum(int field) {
        long total = 0;
        for (int w = 0; w < workers; w++) {
            total += get(w, field);
        }
        return total;
    }

    long[] perWorker(int field) {
        long[] values = new long[workers];
        for (int w = 0; w < workers; w++) {
            values[w] = get(w, field);
        }
        return values;
    }
}

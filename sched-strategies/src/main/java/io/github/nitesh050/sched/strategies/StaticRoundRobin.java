package io.github.nitesh050.sched.strategies;

import io.github.nitesh050.sched.common.api.SchedulingStrategy;
import io.github.nitesh050.sched.common.api.WorkerView;
import io.github.nitesh050.sched.common.config.Mode;
import io.github.nitesh050.sched.common.model.TaskControlBlock;

/**
 * Deals spawned tasks out like cards: each worker sends its next child to the next worker in
 * rotation, starting with itself. No stealing, so an idle worker just waits.
 *
 * <p>Very cheap, and fair as long as tasks are about the same size.
 */
public final class StaticRoundRobin implements SchedulingStrategy {

    /** Ints per worker slot, so each worker's cursor is on its own 128-byte cache line. */
    private static final int STRIDE = 32;

    private final int workers;
    /** cursor[w * STRIDE] is worker w's next target. Each worker touches only its own slot. */
    private final int[] cursor;

    public StaticRoundRobin(int workers) {
        if (workers < 1) {
            throw new IllegalArgumentException("workers must be >= 1");
        }
        this.workers = workers;
        this.cursor = new int[workers * STRIDE];
        for (int w = 0; w < workers; w++) {
            cursor[w * STRIDE] = w;
        }
    }

    @Override
    public Mode mode() {
        return Mode.STATIC_ROUND_ROBIN;
    }

    @Override
    public int onSpawn(WorkerView w, TaskControlBlock child) {
        int slot = w.id() * STRIDE;
        int target = cursor[slot];
        cursor[slot] = target + 1 == workers ? 0 : target + 1;
        return target;
    }

    @Override
    public void onDequeue(WorkerView w) {
        // no steal requests to answer
    }

    @Override
    public boolean onIdle(WorkerView w) {
        return false;
    }
}

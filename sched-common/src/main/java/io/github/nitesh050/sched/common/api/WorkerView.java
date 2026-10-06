package io.github.nitesh050.sched.common.api;

import io.github.nitesh050.sched.common.model.TaskControlBlock;
import java.util.random.RandomGenerator;

/**
 * What a {@link SchedulingStrategy} may do on behalf of one worker. Provided by the engine;
 * every method must be called from that worker's own thread unless stated otherwise.
 *
 * <p>Queue layout (from X-OpenMP's XQueue): each worker owns one queue per producer, so every
 * queue has exactly one producer and one consumer. Worker {@code i} pushing to worker {@code j}
 * writes into the queue that {@code i} produces and {@code j} consumes. The strategy never sees
 * the queues directly, which is what keeps the SPSC rule intact.
 */
public interface WorkerView {

    /** This worker's index. */
    int id();

    int workerCount();

    /**
     * Enqueues {@code tcb} for worker {@code target} (which may be this worker).
     *
     * @return false if that queue is full; the caller still owns {@code tcb}
     */
    boolean pushTo(int target, TaskControlBlock tcb);

    /** Enqueues {@code tcb} on this worker. Never fails (spills to a worker-private overflow). */
    void pushLocal(TaskControlBlock tcb);

    /** Takes the next task from this worker's own queues, or null if they are all empty. */
    TaskControlBlock pollLocal();

    /** Tasks waiting in this worker's queues. */
    int localSize();

    /** Approximate number of tasks waiting at {@code worker}; safe to call for any worker. */
    int approximateSize(int worker);

    /** Per-worker generator seeded from the run seed, for reproducible victim selection. */
    RandomGenerator random();

    /** Sink for steal events; the engine counts them for {@link SignalSource} too. */
    MetricsSink metrics();
}

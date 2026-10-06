package io.github.nitesh050.sched.common.api;

import io.github.nitesh050.sched.common.config.Mode;
import io.github.nitesh050.sched.common.model.TaskControlBlock;

/**
 * One scheduling behaviour. A single instance is shared by all workers, so per-worker state
 * (steal request cells, round numbers, round-robin cursors) lives in arrays indexed by
 * {@link WorkerView#id()}. Every hook runs on the calling worker's own thread.
 *
 * <p><b>Worker loop</b> (see docs/architecture.md):
 * <pre>
 *   loop:
 *     if mode changed:  old.onExit(w); new.onEnter(w)      // only between tasks
 *     tcb = w.pollLocal()
 *     if tcb != null:   strategy.onDequeue(w); run(tcb)    // victim side
 *     else:             strategy.onIdle(w)                 // thief side
 *   spawn inside a task: target = strategy.onSpawn(w, child); push to target
 * </pre>
 *
 * <p><b>Invariant that makes mode switching safe:</b> a strategy never holds a
 * {@link TaskControlBlock} across hook calls. Tasks only move from one queue straight into
 * another (victim: {@code pollLocal} then {@code pushTo(thief)}, falling back to
 * {@code pushLocal}). Every mode drains all of a worker's queues, so a task can never be
 * stranded by a switch; at worst a steal answer arrives after the thief stopped waiting, and
 * the thief simply finds it in its own queue.
 */
public interface SchedulingStrategy {

    Mode mode();

    /** Called when this worker adopts this strategy, including at startup. */
    default void onEnter(WorkerView w) {
    }

    /**
     * Called when this worker leaves this strategy. Must withdraw the worker's own outstanding
     * steal request and decline any request waiting for it, so no other worker waits on it.
     */
    default void onExit(WorkerView w) {
    }

    /**
     * Chooses where a newly spawned task goes.
     *
     * @return target worker index; the engine pushes there, or locally if that queue is full
     */
    int onSpawn(WorkerView w, TaskControlBlock child);

    /**
     * Victim side. Called each time the worker took a task from its own queues, before running
     * it. This is where pending steal requests are answered.
     */
    void onDequeue(WorkerView w);

    /**
     * Thief side. Called when this worker's queues are empty.
     *
     * @return true if work may now be in this worker's queues (the engine polls again at once),
     *     false if the engine should back off before polling again
     */
    boolean onIdle(WorkerView w);
}

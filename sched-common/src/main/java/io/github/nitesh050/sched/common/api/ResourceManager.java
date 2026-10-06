package io.github.nitesh050.sched.common.api;

import java.util.Optional;

/**
 * Grants named, single-instance resources to tasks and detects deadlocks between them.
 *
 * <p>The engine calls this on behalf of tasks (via {@link TaskContext}); it never depends on the
 * implementation module. {@code sched-cli} wires the real implementation in.
 */
public interface ResourceManager {

    /**
     * Blocks until {@code resource} is granted to {@code taskId}.
     *
     * @throws DeadlockException if this request closes a wait-for cycle and {@code taskId} is
     *     chosen as the victim
     */
    void acquire(long taskId, String resource) throws DeadlockException, InterruptedException;

    /** Grants {@code resource} only if it is free right now. */
    boolean tryAcquire(long taskId, String resource);

    /** @throws IllegalStateException if {@code taskId} does not hold {@code resource} */
    void release(long taskId, String resource);

    /** Releases everything {@code taskId} holds and cancels any request it is waiting on. */
    void releaseAll(long taskId);

    /** Checks the current wait-for graph for a cycle, without changing anything. */
    Optional<DeadlockReport> detectDeadlock();
}

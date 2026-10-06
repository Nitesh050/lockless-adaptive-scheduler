package io.github.nitesh050.sched.common.api;

import io.github.nitesh050.sched.common.model.Task;

/**
 * What a running task is allowed to do. One context is valid only for the duration of one
 * {@link Task#run} call, on the worker thread that is running it.
 *
 * <p>Resources still held when {@code run} returns or throws are released by the engine.
 */
public interface TaskContext {

    long taskId();

    /** Index of the worker running this task. */
    int workerIndex();

    /** Creates a child task. Where it runs is decided by the active {@link SchedulingStrategy}. */
    void spawn(Task task);

    /**
     * Blocks until the resource is granted. While blocked, this task's worker runs nothing else.
     *
     * @throws DeadlockException if granting would close a wait-for cycle and this task was chosen
     *     as the victim; the task should give up (its held resources are released when it ends)
     */
    void acquire(String resource) throws DeadlockException, InterruptedException;

    /** Grants the resource only if it is free right now. Never blocks. */
    boolean tryAcquire(String resource);

    void release(String resource);
}

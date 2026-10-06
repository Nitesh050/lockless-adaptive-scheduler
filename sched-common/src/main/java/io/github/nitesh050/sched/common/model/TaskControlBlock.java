package io.github.nitesh050.sched.common.model;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.Objects;

/**
 * Per-task bookkeeping, the scheduler's equivalent of an OS process control block.
 * This is the object that travels through queues; the {@link Task} itself is just the code.
 *
 * <p>State changes go through {@link #transition}, a CAS, so two workers can never both
 * move the same task from READY to RUNNING without one of them seeing a failure.
 */
public final class TaskControlBlock {

    /** Parent id for tasks submitted by the workload rather than spawned by another task. */
    public static final long NO_PARENT = -1;
    /** Worker index for tasks that have not been picked up yet. */
    public static final int NO_WORKER = -1;

    private static final VarHandle STATE;
    private static final VarHandle EXECUTED_BY;

    static {
        try {
            MethodHandles.Lookup lookup = MethodHandles.lookup();
            STATE = lookup.findVarHandle(TaskControlBlock.class, "state", TaskState.class);
            EXECUTED_BY = lookup.findVarHandle(TaskControlBlock.class, "executedBy", int.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private final long id;
    private final long parentId;
    private final Task task;
    private final int spawnedBy;
    private final long createdNanos;

    @SuppressWarnings("unused") // accessed through STATE
    private volatile TaskState state = TaskState.READY;
    @SuppressWarnings("unused") // accessed through EXECUTED_BY
    private volatile int executedBy = NO_WORKER;

    public TaskControlBlock(long id, long parentId, Task task, int spawnedBy) {
        this.id = id;
        this.parentId = parentId;
        this.task = Objects.requireNonNull(task, "task");
        this.spawnedBy = spawnedBy;
        this.createdNanos = System.nanoTime();
    }

    public long id() {
        return id;
    }

    public long parentId() {
        return parentId;
    }

    public Task task() {
        return task;
    }

    /** Worker that created this task, or {@link #NO_WORKER} if the workload submitted it. */
    public int spawnedBy() {
        return spawnedBy;
    }

    public long createdNanos() {
        return createdNanos;
    }

    public TaskState state() {
        return (TaskState) STATE.getAcquire(this);
    }

    /**
     * Atomically moves the task from {@code expected} to {@code next}.
     *
     * @return false if the task was not in {@code expected}; callers treat a failed
     *     READY → RUNNING as a duplicated task
     */
    public boolean transition(TaskState expected, TaskState next) {
        return STATE.compareAndSet(this, expected, next);
    }

    /** Worker that ran (or is running) this task, or {@link #NO_WORKER}. */
    public int executedBy() {
        return (int) EXECUTED_BY.getAcquire(this);
    }

    public void markExecutedBy(int worker) {
        EXECUTED_BY.setRelease(this, worker);
    }

    @Override
    public String toString() {
        return "TCB[id=" + id + ", parent=" + parentId + ", state=" + state()
                + ", spawnedBy=" + spawnedBy + ", executedBy=" + executedBy() + "]";
    }
}

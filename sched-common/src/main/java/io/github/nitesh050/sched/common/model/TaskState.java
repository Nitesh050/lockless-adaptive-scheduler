package io.github.nitesh050.sched.common.model;

/**
 * Lifecycle of a task, as tracked in its {@link TaskControlBlock}.
 *
 * <pre>
 *   READY ──► RUNNING ──► FINISHED
 *                │  ▲         ▲
 *                ▼  │         │
 *              BLOCKED ───────┘ (FAILED is also terminal)
 * </pre>
 *
 * READY means the task sits in exactly one queue. RUNNING and BLOCKED mean exactly one
 * worker owns it. A task that is ever moved READY → RUNNING twice has been duplicated,
 * which is the bug the exactly-once tests look for.
 */
public enum TaskState {
    READY,
    RUNNING,
    /** Waiting inside {@code TaskContext.acquire} for a resource. */
    BLOCKED,
    FINISHED,
    /** Threw an exception or was aborted to break a deadlock. */
    FAILED;

    public boolean isTerminal() {
        return this == FINISHED || this == FAILED;
    }
}

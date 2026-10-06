package io.github.nitesh050.sched.common.api;

/** Thrown to the victim task of a detected deadlock. */
public class DeadlockException extends Exception {

    private static final long serialVersionUID = 1L;

    private final transient DeadlockReport report;

    public DeadlockException(DeadlockReport report) {
        super("deadlock detected, aborting task " + report.victimTaskId() + ": " + report.taskCycle());
        this.report = report;
    }

    public DeadlockReport report() {
        return report;
    }
}

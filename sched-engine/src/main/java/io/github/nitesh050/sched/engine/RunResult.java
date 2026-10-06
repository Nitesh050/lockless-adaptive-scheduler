package io.github.nitesh050.sched.engine;

import java.util.List;

/**
 * Outcome of one {@link WorkerPool#run}. Task counts exclude the engine's internal root task.
 *
 * @param completed        false if the run hit its timeout (a hang) or a worker crashed
 * @param elapsedNanos     from starting the workers until the last task finished (or the timeout)
 * @param tasksExecuted    workload tasks that ran, including spawned ones
 * @param tasksFailed      tasks that threw
 * @param duplicateClaims  times a worker tried to start an already-started task; must be 0
 * @param perWorkerExecuted tasks executed by each worker
 * @param inlineExecutions tasks run by their creator because every usable queue was full
 * @param errors           first few task failures and worker crashes, for diagnosis
 */
public record RunResult(
        boolean completed,
        long elapsedNanos,
        long tasksExecuted,
        long tasksFailed,
        long duplicateClaims,
        long[] perWorkerExecuted,
        long inlineExecutions,
        List<Throwable> errors) {

    public RunResult {
        perWorkerExecuted = perWorkerExecuted.clone();
        errors = List.copyOf(errors);
    }

    @Override
    public long[] perWorkerExecuted() {
        return perWorkerExecuted.clone();
    }

    public double elapsedMillis() {
        return elapsedNanos / 1e6;
    }

    /** Executed tasks per second. */
    public double throughput() {
        return elapsedNanos == 0 ? 0 : tasksExecuted * 1e9 / elapsedNanos;
    }

    /** True if the run finished, nothing failed and nothing ran twice. */
    public boolean isClean() {
        return completed && tasksFailed == 0 && duplicateClaims == 0;
    }
}

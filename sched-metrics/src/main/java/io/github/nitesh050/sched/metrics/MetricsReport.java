package io.github.nitesh050.sched.metrics;

import java.util.List;
import java.util.function.ToLongFunction;

/** All worker counters at one point in time. */
public record MetricsReport(List<WorkerMetrics> workers, long modeSwitches) {

    public MetricsReport {
        workers = List.copyOf(workers);
    }

    public long total(ToLongFunction<WorkerMetrics> field) {
        return workers.stream().mapToLong(field).sum();
    }

    /** Fraction of steal requests that got a task, or NaN if there were none. */
    public double stealSuccessRate() {
        long attempts = total(WorkerMetrics::stealAttempts);
        return attempts == 0 ? Double.NaN : (double) total(WorkerMetrics::stealSuccesses) / attempts;
    }
}

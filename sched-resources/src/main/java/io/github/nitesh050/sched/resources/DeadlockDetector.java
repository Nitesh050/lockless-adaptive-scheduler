package io.github.nitesh050.sched.resources;

import io.github.nitesh050.sched.common.api.DeadlockReport;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Cycle detection on the wait-for graph. With single-instance resources, a cycle is a
 * deadlock (necessary and sufficient), and since a blocked task waits for exactly one
 * resource, each task has at most one outgoing edge: finding a cycle is just following a path.
 */
public final class DeadlockDetector {

    private DeadlockDetector() {
    }

    /**
     * The cycle through {@code start}, if any. Any cycle created by a new wait edge must pass
     * through the task that added it, so checking the requester on every blocking request
     * finds every deadlock the moment it forms. The requester is reported as the victim.
     */
    public static Optional<DeadlockReport> cycleThrough(AllocationGraph g, long start) {
        List<Long> tasks = new ArrayList<>();
        List<String> resources = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        long task = start;
        while (seen.add(task)) {
            String resource = g.waitingFor(task);
            if (resource == null) {
                return Optional.empty();
            }
            Long holder = g.holder(resource);
            if (holder == null) {
                return Optional.empty();
            }
            tasks.add(task);
            resources.add(resource);
            if (holder == start) {
                return Optional.of(new DeadlockReport(tasks, resources, start));
            }
            task = holder;
        }
        return Optional.empty(); // reached a cycle that does not include start
    }

    /** Any cycle in the graph (a full scan, for on-demand checks). */
    public static Optional<DeadlockReport> findAny(AllocationGraph g) {
        for (long task : g.waitingTasks()) {
            Optional<DeadlockReport> cycle = cycleThrough(g, task);
            if (cycle.isPresent()) {
                return cycle;
            }
        }
        return Optional.empty();
    }
}

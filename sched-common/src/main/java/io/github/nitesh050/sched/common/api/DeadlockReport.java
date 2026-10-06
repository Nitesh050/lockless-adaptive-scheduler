package io.github.nitesh050.sched.common.api;

import java.util.List;

/**
 * A detected wait-for cycle.
 *
 * @param taskCycle    task ids in cycle order: each waits for a resource held by the next
 * @param resources    resources on the cycle, aligned with {@code taskCycle}
 * @param victimTaskId task chosen to abort so the others can proceed
 */
public record DeadlockReport(List<Long> taskCycle, List<String> resources, long victimTaskId) {

    public DeadlockReport {
        taskCycle = List.copyOf(taskCycle);
        resources = List.copyOf(resources);
    }
}

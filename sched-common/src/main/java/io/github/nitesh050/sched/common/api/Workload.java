package io.github.nitesh050.sched.common.api;

import io.github.nitesh050.sched.common.model.Task;
import java.util.List;
import java.util.OptionalLong;

/**
 * Produces the tasks for one run. Workloads take their parameters and seed through their
 * constructor, so the same configuration always produces the same work.
 *
 * <p>Mid-run shape changes (as in the shifting workload) are expressed by tasks spawning
 * differently shaped children through {@link TaskContext#spawn}.
 */
public interface Workload {

    String name();

    /** Tasks submitted before the workers start; distributed by the active strategy. */
    List<Task> initialTasks();

    /** Total tasks the run will execute, including spawned ones, if known in advance. */
    default OptionalLong expectedTaskCount() {
        return OptionalLong.empty();
    }
}

package io.github.nitesh050.sched.common.model;

import io.github.nitesh050.sched.common.api.TaskContext;

/**
 * A unit of work. Tasks run to completion on one worker and must not be re-run.
 *
 * <p>A task may spawn further tasks and use resources only through the {@link TaskContext}
 * it is given; it must not keep the context after {@code run} returns.
 */
@FunctionalInterface
public interface Task {

    void run(TaskContext ctx) throws Exception;
}

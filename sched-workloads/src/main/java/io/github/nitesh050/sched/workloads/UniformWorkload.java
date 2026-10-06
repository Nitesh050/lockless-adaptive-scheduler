package io.github.nitesh050.sched.workloads;

import io.github.nitesh050.sched.common.api.Workload;
import io.github.nitesh050.sched.common.model.Task;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;

/** {@code tasks} independent delay tasks, all the same length: the best case for round-robin. */
public final class UniformWorkload implements Workload {

    private final int tasks;
    private final long taskNanos;

    public UniformWorkload(int tasks, long taskNanos) {
        if (tasks < 0) {
            throw new IllegalArgumentException("tasks must be >= 0");
        }
        if (taskNanos < 0) {
            throw new IllegalArgumentException("taskNanos must be >= 0");
        }
        this.tasks = tasks;
        this.taskNanos = taskNanos;
    }

    @Override
    public String name() {
        return "uniform";
    }

    @Override
    public List<Task> initialTasks() {
        DelayTask task = new DelayTask(taskNanos);
        List<Task> list = new ArrayList<>(tasks);
        for (int i = 0; i < tasks; i++) {
            list.add(task);
        }
        return list;
    }

    @Override
    public OptionalLong expectedTaskCount() {
        return OptionalLong.of(tasks);
    }
}

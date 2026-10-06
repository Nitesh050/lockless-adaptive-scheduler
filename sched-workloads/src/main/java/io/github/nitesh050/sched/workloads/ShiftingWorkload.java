package io.github.nitesh050.sched.workloads;

import io.github.nitesh050.sched.common.api.Workload;
import io.github.nitesh050.sched.common.model.Task;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;

/**
 * A workload whose shape changes halfway through: the main experiment for the adaptive layer.
 *
 * <ul>
 *   <li><b>Phase 1 (balanced):</b> equal tasks of {@code taskNanos}. Round-robin's best case;
 *       stealing one task per handshake from the spawning worker is ~3x slower here.</li>
 *   <li><b>Phase 2 (lopsided):</b> every {@code heavyEveryNth}-th task, starting at
 *       {@code heavyOffset}, is {@code heavyMultiplier} times longer. With
 *       {@code heavyEveryNth} equal to the worker count, round-robin's rotation sends every
 *       heavy task to the same worker, so one worker is buried while the rest idle;
 *       stealing spreads them out.</li>
 * </ul>
 *
 * Tasks are spawned in order by the root task and run roughly in that order, so the shift
 * happens at about {@code shiftAtFraction} of the run.
 */
public final class ShiftingWorkload implements Workload {

    private final int tasks;
    private final long taskNanos;
    private final double shiftAtFraction;
    private final int heavyEveryNth;
    private final int heavyOffset;
    private final int heavyMultiplier;

    public ShiftingWorkload(int tasks, long taskNanos, double shiftAtFraction,
                            int heavyEveryNth, int heavyOffset, int heavyMultiplier) {
        if (tasks < 0 || taskNanos < 0) {
            throw new IllegalArgumentException("tasks and taskNanos must be >= 0");
        }
        if (shiftAtFraction < 0 || shiftAtFraction > 1) {
            throw new IllegalArgumentException("shiftAtFraction must be in [0, 1]");
        }
        if (heavyEveryNth < 1 || heavyOffset < 0 || heavyOffset >= heavyEveryNth) {
            throw new IllegalArgumentException("need heavyEveryNth >= 1 and 0 <= heavyOffset < heavyEveryNth");
        }
        if (heavyMultiplier < 1) {
            throw new IllegalArgumentException("heavyMultiplier must be >= 1");
        }
        this.tasks = tasks;
        this.taskNanos = taskNanos;
        this.shiftAtFraction = shiftAtFraction;
        this.heavyEveryNth = heavyEveryNth;
        this.heavyOffset = heavyOffset;
        this.heavyMultiplier = heavyMultiplier;
    }

    @Override
    public String name() {
        return "shifting";
    }

    /** Index of the first phase-2 task. */
    public int shiftIndex() {
        return (int) Math.round(tasks * shiftAtFraction);
    }

    /** True if task {@code i} is one of phase 2's heavy tasks. */
    public boolean isHeavy(int i) {
        return i >= shiftIndex() && i % heavyEveryNth == heavyOffset;
    }

    @Override
    public List<Task> initialTasks() {
        DelayTask light = new DelayTask(taskNanos);
        DelayTask heavy = new DelayTask(taskNanos * heavyMultiplier);
        List<Task> list = new ArrayList<>(tasks);
        for (int i = 0; i < tasks; i++) {
            list.add(isHeavy(i) ? heavy : light);
        }
        return list;
    }

    @Override
    public OptionalLong expectedTaskCount() {
        return OptionalLong.of(tasks);
    }

    /** Total work in nanoseconds, for computing the ideal run time. */
    public long totalWorkNanos() {
        long total = 0;
        for (int i = 0; i < tasks; i++) {
            total += isHeavy(i) ? taskNanos * heavyMultiplier : taskNanos;
        }
        return total;
    }
}

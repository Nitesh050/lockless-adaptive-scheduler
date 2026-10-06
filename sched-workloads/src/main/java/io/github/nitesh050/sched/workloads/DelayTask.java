package io.github.nitesh050.sched.workloads;

import io.github.nitesh050.sched.common.api.TaskContext;
import io.github.nitesh050.sched.common.model.Task;

/**
 * Burns CPU for a fixed time, like the paper's delay tasks. It busy-waits on
 * {@link System#nanoTime()} rather than sleeping, so the worker really is occupied and the
 * duration does not depend on loop speed or JIT tuning.
 */
public final class DelayTask implements Task {

    private final long nanos;

    public DelayTask(long nanos) {
        if (nanos < 0) {
            throw new IllegalArgumentException("nanos must be >= 0");
        }
        this.nanos = nanos;
    }

    public static DelayTask micros(long micros) {
        return new DelayTask(micros * 1_000);
    }

    public long nanos() {
        return nanos;
    }

    @Override
    public void run(TaskContext ctx) {
        spin(nanos);
    }

    /** Busy-waits for {@code nanos}. Usable by other workloads that need a delay inside a task. */
    public static void spin(long nanos) {
        if (nanos == 0) {
            return;
        }
        long end = System.nanoTime() + nanos;
        while (System.nanoTime() - end < 0) {
            Thread.onSpinWait();
        }
    }
}

package io.github.nitesh050.sched.workloads;

import io.github.nitesh050.sched.common.api.DeadlockException;
import io.github.nitesh050.sched.common.api.TaskContext;
import io.github.nitesh050.sched.common.api.Workload;
import io.github.nitesh050.sched.common.model.Task;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Dining philosophers: {@code philosophers} tasks, each picking up fork {@code i}, pausing,
 * then fork {@code i+1}. Everyone holding their left fork and waiting for their right one is a
 * deadlock, and the pause makes that likely.
 *
 * <p>A philosopher that gets a {@link DeadlockException} puts its fork down, waits a little
 * (longer for higher-numbered philosophers, to break the symmetry) and tries again, so the run
 * still completes. Needs a {@code ResourceManager} and at least as many workers as
 * philosophers for the deadlock to form (blocked tasks block their workers).
 */
public final class DeadlockScenario implements Workload {

    private final int philosophers;
    private final int meals;
    private final long holdNanos;
    private final AtomicLong deadlocksSeen = new AtomicLong();
    private final AtomicLong mealsEaten = new AtomicLong();

    public DeadlockScenario(int philosophers, int meals, long holdNanos) {
        if (philosophers < 2) {
            throw new IllegalArgumentException("need at least 2 philosophers");
        }
        if (meals < 1 || holdNanos < 0) {
            throw new IllegalArgumentException("need meals >= 1 and holdNanos >= 0");
        }
        this.philosophers = philosophers;
        this.meals = meals;
        this.holdNanos = holdNanos;
    }

    @Override
    public String name() {
        return "deadlock";
    }

    @Override
    public List<Task> initialTasks() {
        List<Task> tasks = new ArrayList<>(philosophers);
        for (int i = 0; i < philosophers; i++) {
            tasks.add(new Philosopher(i));
        }
        return tasks;
    }

    @Override
    public OptionalLong expectedTaskCount() {
        return OptionalLong.of(philosophers);
    }

    /** Deadlocks the philosophers ran into (each one is detected, reported and retried). */
    public long deadlocksSeen() {
        return deadlocksSeen.get();
    }

    public long mealsEaten() {
        return mealsEaten.get();
    }

    private static String fork(int i) {
        return "fork-" + i;
    }

    private final class Philosopher implements Task {
        private final int seat;

        Philosopher(int seat) {
            this.seat = seat;
        }

        @Override
        public void run(TaskContext ctx) throws InterruptedException {
            String left = fork(seat);
            String right = fork((seat + 1) % philosophers);
            int eaten = 0;
            while (eaten < meals) {
                try {
                    // Holding nothing, this request cannot close a cycle, so it never throws.
                    ctx.acquire(left);
                } catch (DeadlockException e) {
                    throw new IllegalStateException("deadlock reported for a task holding nothing", e);
                }
                DelayTask.spin(holdNanos);
                try {
                    ctx.acquire(right);
                } catch (DeadlockException e) {
                    deadlocksSeen.incrementAndGet();
                    ctx.release(left);
                    DelayTask.spin(holdNanos * (seat + 1)); // back off, asymmetrically
                    continue;
                }
                DelayTask.spin(holdNanos); // eat
                mealsEaten.incrementAndGet();
                eaten++;
                ctx.release(right);
                ctx.release(left);
            }
        }
    }
}

package io.github.nitesh050.sched.engine;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Knows when all work is finished, using one counter of outstanding tasks: incremented when a
 * TCB is created (before it is enqueued), decremented when it reaches a terminal state.
 *
 * <p>A child is always counted while its parent is still running, so the counter cannot hit
 * zero while any task exists, and once it is zero no task is left that could spawn another.
 *
 * <p>This must be a single atomic counter. A striped counter such as {@code LongAdder} can
 * read zero falsely: its sum might see a parent's decrement but miss its child's increment.
 */
public final class TerminationDetector {

    private final AtomicLong outstanding = new AtomicLong();
    private final CountDownLatch done = new CountDownLatch(1);
    private volatile long doneNanos;

    public void taskCreated() {
        if (isDone()) {
            throw new IllegalStateException("task created after termination");
        }
        outstanding.getAndIncrement();
    }

    public void taskCompleted() {
        long left = outstanding.decrementAndGet();
        if (left == 0) {
            doneNanos = System.nanoTime();
            done.countDown();
        } else if (left < 0) {
            throw new IllegalStateException("more tasks completed than were created");
        }
    }

    public boolean isDone() {
        return done.getCount() == 0;
    }

    public long outstanding() {
        return outstanding.get();
    }

    /** @return true if all work finished within the timeout */
    public boolean await(long timeout, TimeUnit unit) throws InterruptedException {
        return done.await(timeout, unit);
    }

    /** {@link System#nanoTime()} at the moment the last task finished; 0 if not done. */
    public long doneNanos() {
        return doneNanos;
    }
}

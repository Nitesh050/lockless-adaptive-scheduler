package io.github.nitesh050.sched.engine;

import io.github.nitesh050.sched.common.api.MetricsSink;
import io.github.nitesh050.sched.common.api.SchedulingStrategy;
import io.github.nitesh050.sched.common.api.WorkerView;
import io.github.nitesh050.sched.common.config.Mode;
import io.github.nitesh050.sched.common.model.Task;
import io.github.nitesh050.sched.common.model.TaskControlBlock;
import io.github.nitesh050.sched.common.model.TaskState;
import io.github.nitesh050.sched.queue.WorkerQueueSet;
import java.util.ArrayDeque;
import java.util.SplittableRandom;
import java.util.concurrent.locks.LockSupport;
import java.util.random.RandomGenerator;

/**
 * One worker thread: runs the scheduling loop and is the {@link WorkerView} its strategy
 * acts through. Everything here except {@link #localSize()} runs on this worker's thread.
 */
final class Worker implements WorkerView, Runnable {

    private static final int SPIN_ROUNDS = 64;
    private static final int YIELD_ROUNDS = 128;
    private static final long PARK_NANOS = 20_000;

    private final int id;
    private final WorkerPool pool;
    private final WorkerQueueSet<TaskControlBlock> incoming;
    private final RandomGenerator random;

    /** Spill-over when the master queue is full. Only this worker touches it. */
    private final ArrayDeque<TaskControlBlock> overflow = new ArrayDeque<>();
    /** Mirrors {@code overflow.size()} for other threads; written only by this worker. */
    private volatile int overflowSize;

    private Mode mode;
    private SchedulingStrategy strategy;

    Worker(int id, WorkerPool pool, long seed) {
        this.id = id;
        this.pool = pool;
        this.incoming = pool.queueSet(id);
        this.random = new SplittableRandom(seed + 0x9E3779B97F4A7C15L * (id + 1));
    }

    // ---- scheduling loop ------------------------------------------------------------------

    @Override
    public void run() {
        mode = pool.modeFlag().currentMode();
        strategy = pool.strategyFor(mode);
        try {
            strategy.onEnter(this);
            loop();
        } catch (Throwable t) {
            pool.workerCrashed(id, t);
        } finally {
            try {
                strategy.onExit(this);
            } catch (Throwable t) {
                pool.workerCrashed(id, t);
            }
            pool.stats().set(id, WorkerStats.IDLE, 1);
        }
    }

    private void loop() {
        WorkerStats stats = pool.stats();
        boolean idle = false;
        int idleRounds = 0;
        while (true) {
            Mode current = pool.modeFlag().currentMode();
            if (current != mode) {
                switchTo(current);
            }

            TaskControlBlock tcb = pollLocal();
            if (tcb != null) {
                if (idle) {
                    idle = false;
                    stats.set(id, WorkerStats.IDLE, 0);
                }
                idleRounds = 0;
                strategy.onDequeue(this);
                execute(tcb);
                continue;
            }

            if (pool.termination().isDone() || pool.isStopRequested()) {
                return;
            }
            if (!idle) {
                idle = true;
                stats.set(id, WorkerStats.IDLE, 1);
                pool.metrics().workerIdle(id);
            }
            if (strategy.onIdle(this)) {
                idleRounds = 0;
            } else {
                backOff(idleRounds++);
            }
        }
    }

    /** Only ever called between tasks, so no strategy is interrupted halfway through a hook. */
    private void switchTo(Mode next) {
        strategy.onExit(this);
        mode = next;
        strategy = pool.strategyFor(next);
        strategy.onEnter(this);
    }

    private static void backOff(int round) {
        if (round < SPIN_ROUNDS) {
            Thread.onSpinWait();
        } else if (round < YIELD_ROUNDS) {
            Thread.yield();
        } else {
            LockSupport.parkNanos(PARK_NANOS);
        }
    }

    // ---- running and spawning tasks -------------------------------------------------------

    void execute(TaskControlBlock tcb) {
        if (!tcb.transition(TaskState.READY, TaskState.RUNNING)) {
            pool.duplicateClaim(id, tcb);
            return;
        }
        tcb.markExecutedBy(id);
        boolean counted = !pool.isRoot(tcb);
        if (counted) {
            pool.metrics().taskStarted(id, tcb.id());
        }
        TaskContextImpl ctx = new TaskContextImpl(this, tcb, pool.resourceManager());
        try {
            tcb.task().run(ctx);
            tcb.transition(TaskState.RUNNING, TaskState.FINISHED);
        } catch (Throwable t) {
            if (!tcb.transition(TaskState.RUNNING, TaskState.FAILED)) {
                tcb.transition(TaskState.BLOCKED, TaskState.FAILED);
            }
            pool.taskFailed(tcb, t);
            if (t instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
        } finally {
            ctx.close();
            if (counted) {
                pool.stats().increment(id, WorkerStats.COMPLETED);
                pool.metrics().taskFinished(id, tcb.id());
            }
            pool.termination().taskCompleted();
        }
    }

    /**
     * Creates a child of {@code parentId} and places it where the strategy says. If both the
     * target's queue and this worker's own queue are full, the child runs right here
     * (undeferred), as OpenMP runtimes do when their task queues fill up.
     */
    void spawn(long parentId, Task task) {
        TaskControlBlock child = pool.tcbs().create(parentId, task, id);
        pool.termination().taskCreated();
        pool.stats().increment(id, WorkerStats.SPAWNED);
        pool.metrics().taskSpawned(id, child.id());

        int target = strategy.onSpawn(this, child);
        if (pushTo(target, child) || (target != id && pushTo(id, child))) {
            return;
        }
        pool.stats().increment(id, WorkerStats.INLINE);
        execute(child);
    }

    // ---- WorkerView -----------------------------------------------------------------------

    @Override
    public int id() {
        return id;
    }

    @Override
    public int workerCount() {
        return pool.workerCount();
    }

    @Override
    public boolean pushTo(int target, TaskControlBlock tcb) {
        if (target < 0 || target >= pool.workerCount()) {
            throw new IllegalArgumentException("target worker " + target + " out of range");
        }
        return pool.queueSet(target).from(id).offer(tcb);
    }

    @Override
    public void pushLocal(TaskControlBlock tcb) {
        if (!incoming.from(id).offer(tcb)) {
            overflow.addLast(tcb);
            overflowSize = overflow.size();
        }
    }

    @Override
    public TaskControlBlock pollLocal() {
        TaskControlBlock tcb = incoming.poll();
        if (tcb == null && !overflow.isEmpty()) {
            tcb = overflow.pollFirst();
            overflowSize = overflow.size();
        }
        return tcb;
    }

    /** Safe from any thread; approximate when not called by this worker. */
    @Override
    public int localSize() {
        return incoming.size() + overflowSize;
    }

    @Override
    public int approximateSize(int worker) {
        return pool.worker(worker).localSize();
    }

    @Override
    public RandomGenerator random() {
        return random;
    }

    @Override
    public MetricsSink metrics() {
        return pool.metrics();
    }
}

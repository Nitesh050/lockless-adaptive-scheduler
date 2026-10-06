package io.github.nitesh050.sched.engine;

import io.github.nitesh050.sched.common.api.MetricsSink;
import io.github.nitesh050.sched.common.api.ResourceManager;
import io.github.nitesh050.sched.common.api.SchedulingStrategy;
import io.github.nitesh050.sched.common.api.SignalSource;
import io.github.nitesh050.sched.common.api.Workload;
import io.github.nitesh050.sched.common.config.Mode;
import io.github.nitesh050.sched.common.config.SchedulerConfig;
import io.github.nitesh050.sched.common.model.Task;
import io.github.nitesh050.sched.common.model.TaskControlBlock;
import io.github.nitesh050.sched.queue.QueueType;
import io.github.nitesh050.sched.queue.WorkerQueueSet;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Owns the N workers and their queues for exactly one run.
 *
 * <pre>
 *   WorkerPool pool = WorkerPool.builder(config).strategy(new StaticRoundRobin(n)).build();
 *   RunResult result = pool.run(workload, Duration.ofMinutes(1));
 * </pre>
 *
 * <p>The workload's initial tasks are spawned by a root task on worker 0, through the same
 * {@code onSpawn} path as any child. So the active strategy also decides how the initial work
 * is spread, as when an OpenMP program creates its tasks from a single thread.
 */
public final class WorkerPool {

    private static final int MAX_RECORDED_ERRORS = 10;

    private final SchedulerConfig config;
    private final Map<Mode, SchedulingStrategy> strategies;
    private final ResourceManager resourceManager;
    private final ModeFlag modeFlag;
    private final TcbTable tcbs;
    private final TerminationDetector termination = new TerminationDetector();
    private final WorkerStats stats;
    private final MetricsSink metrics;
    private final SignalSource signals;
    private final List<WorkerQueueSet<TaskControlBlock>> queueSets;
    private final Worker[] workers;

    private final AtomicBoolean started = new AtomicBoolean();
    private volatile boolean stopRequested;
    private volatile long rootId = Long.MIN_VALUE;
    private final AtomicLong failures = new AtomicLong();
    private final AtomicLong duplicates = new AtomicLong();
    private final AtomicBoolean crashed = new AtomicBoolean();
    private final ConcurrentLinkedQueue<Throwable> errors = new ConcurrentLinkedQueue<>();

    private WorkerPool(Builder b) {
        this.config = b.config;
        this.strategies = new EnumMap<>(b.strategies);
        this.resourceManager = b.resourceManager;
        this.modeFlag = new ModeFlag(config.initialMode());
        this.tcbs = new TcbTable(b.trackTasks);
        int n = config.workers();
        this.stats = new WorkerStats(n);
        this.metrics = new CountingSink(stats, b.metrics);
        this.queueSets = new ArrayList<>(n);
        for (int w = 0; w < n; w++) {
            queueSets.add(new WorkerQueueSet<>(w, n, config.queueCapacity(), b.queueType));
        }
        this.workers = new Worker[n];
        for (int w = 0; w < n; w++) {
            workers[w] = new Worker(w, this, config.seed());
        }
        this.signals = new EngineSignalSource(this);
    }

    public static Builder builder(SchedulerConfig config) {
        return new Builder(config);
    }

    /**
     * Runs the workload to completion and stops the workers. Can be called once per pool.
     *
     * @param timeout how long to wait before declaring the run hung; the result then has
     *     {@code completed == false}
     */
    public RunResult run(Workload workload, Duration timeout) throws InterruptedException {
        if (!started.compareAndSet(false, true)) {
            throw new IllegalStateException("a WorkerPool runs exactly once; build a new one");
        }
        List<Task> initial = List.copyOf(workload.initialTasks());
        TaskControlBlock root = tcbs.create(TaskControlBlock.NO_PARENT, rootTask(initial), TaskControlBlock.NO_WORKER);
        rootId = root.id();
        termination.taskCreated();
        // Worker 0 is the only producer of its master queue. Offering from this thread is safe
        // because it happens before Thread.start(), which orders it before everything worker 0 does.
        if (!queueSets.get(0).from(0).offer(root)) {
            throw new IllegalStateException("cannot enqueue the root task");
        }

        Thread[] threads = new Thread[workers.length];
        for (int w = 0; w < workers.length; w++) {
            threads[w] = new Thread(workers[w], "worker-" + w);
            threads[w].setDaemon(true);
        }
        long start = System.nanoTime();
        for (Thread t : threads) {
            t.start();
        }

        boolean finished = awaitTermination(start + timeout.toNanos());
        long end = finished ? termination.doneNanos() : System.nanoTime();
        if (!finished) {
            stopRequested = true;
        }
        for (Thread t : threads) {
            t.join(TimeUnit.SECONDS.toMillis(5));
        }
        stopRequested = true;

        return new RunResult(
                finished && !crashed.get(),
                end - start,
                stats.sum(WorkerStats.COMPLETED),
                failures.get(),
                duplicates.get(),
                stats.perWorker(WorkerStats.COMPLETED),
                stats.sum(WorkerStats.INLINE),
                new ArrayList<>(errors));
    }

    /** Waits for all work to finish; gives up early if a worker crashed. */
    private boolean awaitTermination(long deadlineNanos) throws InterruptedException {
        final long checkNanos = TimeUnit.MILLISECONDS.toNanos(50);
        while (true) {
            long remaining = deadlineNanos - System.nanoTime();
            if (remaining <= 0 || stopRequested) {
                return termination.isDone();
            }
            if (termination.await(Math.min(remaining, checkNanos), TimeUnit.NANOSECONDS)) {
                return true;
            }
        }
    }

    private static Task rootTask(List<Task> initial) {
        return ctx -> {
            for (Task t : initial) {
                ctx.spawn(t);
            }
        };
    }

    // ---- what the adaptive controller and tools use --------------------------------------

    /** The mode flag; the adaptive controller uses it as a {@code ModeController}. */
    public ModeFlag modeFlag() {
        return modeFlag;
    }

    public SignalSource signalSource() {
        return signals;
    }

    public TcbTable tcbs() {
        return tcbs;
    }

    public int workerCount() {
        return workers.length;
    }

    public SchedulerConfig config() {
        return config;
    }

    // ---- package-private plumbing for Worker ----------------------------------------------

    WorkerQueueSet<TaskControlBlock> queueSet(int worker) {
        return queueSets.get(worker);
    }

    Worker worker(int index) {
        return workers[index];
    }

    SchedulingStrategy strategyFor(Mode mode) {
        SchedulingStrategy s = strategies.get(mode);
        if (s == null) {
            throw new IllegalStateException("no strategy registered for mode " + mode);
        }
        return s;
    }

    TerminationDetector termination() {
        return termination;
    }

    WorkerStats stats() {
        return stats;
    }

    MetricsSink metrics() {
        return metrics;
    }

    ResourceManager resourceManager() {
        return resourceManager;
    }

    boolean isStopRequested() {
        return stopRequested;
    }

    boolean isRoot(TaskControlBlock tcb) {
        return tcb.id() == rootId;
    }

    void taskFailed(TaskControlBlock tcb, Throwable t) {
        failures.incrementAndGet();
        recordError(new RuntimeException("task " + tcb.id() + " failed", t));
    }

    void duplicateClaim(int worker, TaskControlBlock tcb) {
        duplicates.incrementAndGet();
        recordError(new IllegalStateException("worker " + worker + " tried to start " + tcb + " twice"));
    }

    void workerCrashed(int worker, Throwable t) {
        crashed.set(true);
        stopRequested = true;
        recordError(new IllegalStateException("worker " + worker + " crashed", t));
    }

    private void recordError(Throwable t) {
        if (errors.size() < MAX_RECORDED_ERRORS) {
            errors.add(t);
        }
    }

    /** Counts steal events for the signal source, then forwards everything to the real sink. */
    private static final class CountingSink implements MetricsSink {
        private final WorkerStats stats;
        private final MetricsSink delegate;

        CountingSink(WorkerStats stats, MetricsSink delegate) {
            this.stats = stats;
            this.delegate = delegate;
        }

        @Override
        public void taskSpawned(int worker, long taskId) {
            delegate.taskSpawned(worker, taskId);
        }

        @Override
        public void taskStarted(int worker, long taskId) {
            delegate.taskStarted(worker, taskId);
        }

        @Override
        public void taskFinished(int worker, long taskId) {
            delegate.taskFinished(worker, taskId);
        }

        @Override
        public void stealAttempted(int thief, int victim) {
            stats.increment(thief, WorkerStats.STEAL_ATTEMPTS);
            delegate.stealAttempted(thief, victim);
        }

        @Override
        public void stealSucceeded(int thief, int victim) {
            stats.increment(victim, WorkerStats.STEAL_SUCCESSES);
            delegate.stealSucceeded(thief, victim);
        }

        @Override
        public void workerIdle(int worker) {
            delegate.workerIdle(worker);
        }

        @Override
        public void modeSwitched(Mode from, Mode to) {
            delegate.modeSwitched(from, to);
        }
    }

    public static final class Builder {
        private final SchedulerConfig config;
        private final Map<Mode, SchedulingStrategy> strategies = new EnumMap<>(Mode.class);
        private MetricsSink metrics = MetricsSink.NOOP;
        private ResourceManager resourceManager;
        private QueueType queueType = QueueType.SPSC;
        private boolean trackTasks = true;

        private Builder(SchedulerConfig config) {
            this.config = Objects.requireNonNull(config, "config");
        }

        /** Registers a strategy under its own {@link SchedulingStrategy#mode()}. */
        public Builder strategy(SchedulingStrategy strategy) {
            strategies.put(strategy.mode(), strategy);
            return this;
        }

        public Builder metrics(MetricsSink metrics) {
            this.metrics = Objects.requireNonNull(metrics, "metrics");
            return this;
        }

        /** Optional; without one, {@code TaskContext.acquire} throws. */
        public Builder resourceManager(ResourceManager resourceManager) {
            this.resourceManager = resourceManager;
            return this;
        }

        public Builder queueType(QueueType queueType) {
            this.queueType = Objects.requireNonNull(queueType, "queueType");
            return this;
        }

        /** Keep every TCB for auditing (default true). Turn off for benchmarks. */
        public Builder trackTasks(boolean trackTasks) {
            this.trackTasks = trackTasks;
            return this;
        }

        public WorkerPool build() {
            if (!strategies.containsKey(config.initialMode())) {
                throw new IllegalStateException("no strategy registered for initial mode " + config.initialMode());
            }
            if (config.adaptive() && strategies.size() < Mode.values().length) {
                throw new IllegalStateException("an adaptive run needs a strategy for every mode, got " + strategies.keySet());
            }
            return new WorkerPool(this);
        }
    }
}

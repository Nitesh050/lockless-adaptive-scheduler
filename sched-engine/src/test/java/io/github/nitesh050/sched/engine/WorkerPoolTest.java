package io.github.nitesh050.sched.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.nitesh050.sched.common.api.TaskContext;
import io.github.nitesh050.sched.common.api.Workload;
import io.github.nitesh050.sched.common.config.Mode;
import io.github.nitesh050.sched.common.config.SchedulerConfig;
import io.github.nitesh050.sched.common.model.Task;
import io.github.nitesh050.sched.common.model.TaskState;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import io.github.nitesh050.sched.queue.QueueType;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

@Timeout(60)
class WorkerPoolTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private static SchedulerConfig config(int workers, int capacity) {
        return SchedulerConfig.builder().workers(workers).queueCapacity(capacity).build();
    }

    private static WorkerPool roundRobinPool(SchedulerConfig config) {
        return roundRobinPool(config, QueueType.SPSC);
    }

    private static WorkerPool roundRobinPool(SchedulerConfig config, QueueType queues) {
        return WorkerPool.builder(config)
                .strategy(new TestStrategies.RoundRobin(Mode.STATIC_ROUND_ROBIN, config.workers()))
                .queueType(queues)
                .build();
    }

    private static Workload workload(List<Task> tasks) {
        return new Workload() {
            @Override
            public String name() {
                return "test";
            }

            @Override
            public List<Task> initialTasks() {
                return tasks;
            }
        };
    }

    /** n independent tasks; task i bumps runs[i]. */
    private static Workload counting(AtomicIntegerArray runs) {
        List<Task> tasks = new ArrayList<>();
        for (int i = 0; i < runs.length(); i++) {
            int index = i;
            tasks.add(ctx -> runs.incrementAndGet(index));
        }
        return workload(tasks);
    }

    private static void assertEachRanOnce(AtomicIntegerArray runs) {
        for (int i = 0; i < runs.length(); i++) {
            assertEquals(1, runs.get(i), "task " + i + " ran " + runs.get(i) + " times");
        }
    }

    @ParameterizedTest
    @CsvSource({"1, SPSC", "2, SPSC", "4, SPSC", "8, SPSC", "1, LOCKING", "8, LOCKING"})
    void everyTaskRunsExactlyOnce(int workers, QueueType queues) throws Exception {
        AtomicIntegerArray runs = new AtomicIntegerArray(20_000);
        RunResult r = roundRobinPool(config(workers, 1024), queues).run(counting(runs), TIMEOUT);

        assertTrue(r.isClean(), r.errors().toString());
        assertEquals(runs.length(), r.tasksExecuted());
        assertEquals(r.tasksExecuted(), Arrays.stream(r.perWorkerExecuted()).sum());
        assertEachRanOnce(runs);
    }

    @Test
    void roundRobinSpreadsIndependentTasksEvenly() throws Exception {
        AtomicIntegerArray runs = new AtomicIntegerArray(8_000);
        RunResult r = roundRobinPool(config(4, 4096)).run(counting(runs), TIMEOUT);

        assertTrue(r.isClean());
        for (long perWorker : r.perWorkerExecuted()) {
            assertEquals(2_000, perWorker, Arrays.toString(r.perWorkerExecuted()));
        }
    }

    /** Recursive spawning: a full binary tree of the given depth has 2^(depth+1) - 1 nodes. */
    @ParameterizedTest
    @EnumSource(QueueType.class)
    void spawnedTasksAllRunAndTerminationWaitsForThem(QueueType queues) throws Exception {
        int depth = 12;
        AtomicLong executed = new AtomicLong();
        Task root = new Task() {
            @Override
            public void run(TaskContext ctx) {
                tree(ctx, depth);
            }

            private void tree(TaskContext ctx, int level) {
                executed.incrementAndGet();
                if (level > 0) {
                    ctx.spawn(c -> tree(c, level - 1));
                    ctx.spawn(c -> tree(c, level - 1));
                }
            }
        };
        RunResult r = roundRobinPool(config(4, 64), queues).run(workload(List.of(root)), TIMEOUT);

        long expected = (1L << (depth + 1)) - 1;
        assertTrue(r.isClean(), r.errors().toString());
        assertEquals(expected, executed.get());
        assertEquals(expected, r.tasksExecuted());
    }

    /** With tiny queues, spawns overflow and run inline; nothing may be lost or repeated. */
    @ParameterizedTest
    @EnumSource(QueueType.class)
    void fullQueuesFallBackToInlineExecution(QueueType queues) throws Exception {
        AtomicIntegerArray runs = new AtomicIntegerArray(5_000);
        RunResult r = roundRobinPool(config(2, 2), queues).run(counting(runs), TIMEOUT);

        assertTrue(r.isClean(), r.errors().toString());
        assertTrue(r.inlineExecutions() > 0, "expected some inline executions with capacity 2");
        assertEachRanOnce(runs);
    }

    @Test
    void emptyWorkloadTerminates() throws Exception {
        RunResult r = roundRobinPool(config(4, 16)).run(workload(List.of()), TIMEOUT);
        assertTrue(r.isClean());
        assertEquals(0, r.tasksExecuted());
    }

    @Test
    void failingTaskIsRecordedAndTheRunStillFinishes() throws Exception {
        AtomicIntegerArray runs = new AtomicIntegerArray(100);
        List<Task> tasks = new ArrayList<>(counting(runs).initialTasks());
        tasks.add(ctx -> {
            throw new IllegalStateException("boom");
        });
        RunResult r = roundRobinPool(config(4, 64)).run(workload(tasks), TIMEOUT);

        assertTrue(r.completed());
        assertEquals(1, r.tasksFailed());
        assertFalse(r.isClean());
        assertEachRanOnce(runs);
    }

    @Test
    void acquireWithoutResourceManagerFailsTheTask() throws Exception {
        RunResult r = roundRobinPool(config(2, 16))
                .run(workload(List.of(ctx -> ctx.acquire("printer"))), TIMEOUT);
        assertTrue(r.completed());
        assertEquals(1, r.tasksFailed());
    }

    @Test
    void trackedTcbsAllEndFinished() throws Exception {
        AtomicIntegerArray runs = new AtomicIntegerArray(1_000);
        WorkerPool pool = roundRobinPool(config(4, 64));
        pool.run(counting(runs), TIMEOUT);

        // +1 for the engine's root task
        assertEquals(runs.length() + 1, pool.tcbs().created());
        assertEquals(runs.length() + 1L, pool.tcbs().countByState().get(TaskState.FINISHED));
    }

    /**
     * Flips the mode flag as fast as possible while tasks run. Every task must still run
     * exactly once, and every worker must pair each onEnter with an onExit.
     */
    @ParameterizedTest
    @EnumSource(QueueType.class)
    void modeSwitchesMidRunLoseNothing(QueueType queues) throws Exception {
        int workers = 4;
        SchedulerConfig cfg = SchedulerConfig.builder().workers(workers).queueCapacity(64).adaptive(true).build();
        TestStrategies.Counting rr = new TestStrategies.RoundRobin(Mode.STATIC_ROUND_ROBIN, workers);
        TestStrategies.Counting waitBased = TestStrategies.local(Mode.WAIT_BASED_STEAL);
        TestStrategies.Counting noWait = TestStrategies.local(Mode.NO_WAIT_STEAL);
        WorkerPool pool = WorkerPool.builder(cfg).strategy(rr).strategy(waitBased).strategy(noWait).queueType(queues).build();

        AtomicBoolean stop = new AtomicBoolean();
        AtomicLong switches = new AtomicLong();
        Thread flipper = new Thread(() -> {
            Mode[] modes = Mode.values();
            int i = 0;
            while (!stop.get()) {
                if (pool.modeFlag().requestMode(modes[i++ % modes.length])) {
                    switches.incrementAndGet();
                }
                Thread.onSpinWait();
            }
        });

        AtomicIntegerArray runs = new AtomicIntegerArray(50_000);
        flipper.start();
        RunResult r;
        try {
            r = pool.run(counting(runs), TIMEOUT);
        } finally {
            stop.set(true);
            flipper.join();
        }

        assertTrue(r.isClean(), r.errors().toString());
        assertEachRanOnce(runs);
        assertTrue(switches.get() > 0);
        int enters = rr.enters.get() + waitBased.enters.get() + noWait.enters.get();
        int exits = rr.exits.get() + waitBased.exits.get() + noWait.exits.get();
        assertEquals(enters, exits, "every onEnter needs a matching onExit");
        assertTrue(enters >= workers);
    }

    @Test
    void poolRunsOnlyOnce() throws Exception {
        WorkerPool pool = roundRobinPool(config(2, 16));
        pool.run(workload(List.of()), TIMEOUT);
        assertThrows(IllegalStateException.class, () -> pool.run(workload(List.of()), TIMEOUT));
    }

    @Test
    void adaptiveRunNeedsAStrategyForEveryMode() {
        SchedulerConfig cfg = config(2, 16).toBuilder().adaptive(true).build();
        assertThrows(IllegalStateException.class, () -> roundRobinPool(cfg));
    }

    @Test
    void signalSnapshotReportsWorkersAndCompletions() throws Exception {
        AtomicIntegerArray runs = new AtomicIntegerArray(500);
        WorkerPool pool = roundRobinPool(config(3, 64));
        pool.run(counting(runs), TIMEOUT);

        var snap = pool.signalSource().snapshot();
        assertEquals(3, snap.workerCount());
        assertEquals(500, snap.tasksCompleted());
        assertEquals(3, snap.idleWorkers(), "all workers are idle after the run");
        assertEquals(Mode.STATIC_ROUND_ROBIN, snap.mode());
    }
}

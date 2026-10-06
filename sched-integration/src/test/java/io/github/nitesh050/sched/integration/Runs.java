package io.github.nitesh050.sched.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.nitesh050.sched.common.api.MetricsSink;
import io.github.nitesh050.sched.common.api.Workload;
import io.github.nitesh050.sched.common.config.Mode;
import io.github.nitesh050.sched.common.config.SchedulerConfig;
import io.github.nitesh050.sched.common.model.Task;
import io.github.nitesh050.sched.common.model.TaskControlBlock;
import io.github.nitesh050.sched.common.model.TaskState;
import io.github.nitesh050.sched.engine.RunResult;
import io.github.nitesh050.sched.engine.WorkerPool;
import io.github.nitesh050.sched.queue.QueueType;
import io.github.nitesh050.sched.strategies.NoWaitSteal;
import io.github.nitesh050.sched.strategies.StaticRoundRobin;
import io.github.nitesh050.sched.strategies.StealRequestCell;
import io.github.nitesh050.sched.strategies.WaitBasedSteal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicIntegerArray;

/** Shared setup for the end-to-end tests: real strategies, real queues, full auditing. */
final class Runs {

    static final Duration TIMEOUT = Duration.ofSeconds(60);

    private Runs() {
    }

    static SchedulerConfig config(int workers, Mode mode, boolean adaptive) {
        return SchedulerConfig.builder()
                .workers(workers)
                .initialMode(mode)
                .adaptive(adaptive)
                .queueCapacity(256)
                .stealWaitNanos(20_000)
                .seed(42)
                .build();
    }

    /** A pool with every strategy registered (stealing ones share cells), tracking every TCB. */
    static WorkerPool pool(SchedulerConfig config, QueueType queues, int stealBatch, MetricsSink metrics) {
        StealRequestCell cells = new StealRequestCell(config.workers());
        return WorkerPool.builder(config)
                .strategy(new StaticRoundRobin(config.workers()))
                .strategy(new WaitBasedSteal(cells, config.stealWaitNanos(), stealBatch))
                .strategy(new NoWaitSteal(cells, config.stealWaitNanos(), stealBatch))
                .queueType(queues)
                .metrics(metrics)
                .trackTasks(true)
                .build();
    }

    static Workload workload(String name, List<Task> tasks) {
        return new Workload() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public List<Task> initialTasks() {
                return tasks;
            }
        };
    }

    /** {@code runs.length()} independent tasks; task i bumps runs[i] and burns {@code nanos}. */
    static Workload counting(AtomicIntegerArray runs, long nanos) {
        List<Task> tasks = new ArrayList<>(runs.length());
        for (int i = 0; i < runs.length(); i++) {
            int index = i;
            tasks.add(ctx -> {
                runs.incrementAndGet(index);
                spin(nanos);
            });
        }
        return workload("counting", tasks);
    }

    static void spin(long nanos) {
        long end = System.nanoTime() + nanos;
        while (System.nanoTime() - end < 0) {
            Thread.onSpinWait();
        }
    }

    static void assertEachRanOnce(AtomicIntegerArray runs) {
        for (int i = 0; i < runs.length(); i++) {
            assertEquals(1, runs.get(i), "task " + i + " ran " + runs.get(i) + " times");
        }
    }

    /** Clean result, expected count, and every tracked TCB (plus the root) ended FINISHED. */
    static void assertCleanRun(WorkerPool pool, RunResult r, long expectedTasks) {
        assertTrue(r.completed(), "run did not complete: " + r.errors());
        assertEquals(0, r.duplicateClaims(), "a task was started twice: " + r.errors());
        assertEquals(0, r.tasksFailed(), "tasks failed: " + r.errors());
        assertEquals(expectedTasks, r.tasksExecuted());
        assertEquals(expectedTasks + 1, pool.tcbs().created(), "TCBs created (incl. root)");
        long finished = pool.tcbs().countByState().get(TaskState.FINISHED);
        assertEquals(expectedTasks + 1, finished, () -> "not every task finished: " + pool.tcbs().countByState());
        for (TaskControlBlock tcb : pool.tcbs().all()) {
            assertTrue(tcb.executedBy() >= 0, () -> tcb + " never ran");
        }
    }
}

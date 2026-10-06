package io.github.nitesh050.sched.integration;

import static io.github.nitesh050.sched.integration.Runs.TIMEOUT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.nitesh050.sched.common.api.MetricsSink;
import io.github.nitesh050.sched.common.api.TaskContext;
import io.github.nitesh050.sched.common.config.Mode;
import io.github.nitesh050.sched.common.model.Task;
import io.github.nitesh050.sched.engine.RunResult;
import io.github.nitesh050.sched.engine.WorkerPool;
import io.github.nitesh050.sched.queue.QueueType;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** Runs neither hang nor stop early, whatever the shape of the work. */
@Timeout(120)
class TerminationTest {

    private static WorkerPool pool(int workers, Mode mode) {
        return Runs.pool(Runs.config(workers, mode, false), QueueType.SPSC, 1, MetricsSink.NOOP);
    }

    /**
     * A chain where each task works for a while before spawning the next: at most one task
     * exists at any moment, so a detector that trusted "all queues empty" would stop early.
     */
    @ParameterizedTest
    @EnumSource(Mode.class)
    void longChainIsNotCutShort(Mode mode) throws Exception {
        int length = 300;
        AtomicInteger executed = new AtomicInteger();
        Task link = new Task() {
            @Override
            public void run(TaskContext ctx) {
                int n = executed.incrementAndGet();
                Runs.spin(20_000);
                if (n < length) {
                    ctx.spawn(this);
                }
            }
        };
        WorkerPool p = pool(8, mode);
        RunResult r = p.run(Runs.workload("chain", List.of(link)), TIMEOUT);

        Runs.assertCleanRun(p, r, length);
        assertEquals(length, executed.get());
    }

    @ParameterizedTest
    @EnumSource(Mode.class)
    void emptyWorkloadEndsAtOnce(Mode mode) throws Exception {
        RunResult r = pool(8, mode).run(Runs.workload("empty", List.of()), TIMEOUT);
        assertTrue(r.isClean());
        assertEquals(0, r.tasksExecuted());
        assertTrue(r.elapsedMillis() < 1_000, "took " + r.elapsedMillis() + " ms");
    }

    /** One worker: stealing has no victims and must not spin forever looking for one. */
    @ParameterizedTest
    @EnumSource(Mode.class)
    void singleWorkerFinishes(Mode mode) throws Exception {
        AtomicInteger executed = new AtomicInteger();
        Task leaf = ctx -> executed.incrementAndGet();
        Task fanOut = ctx -> {
            for (int i = 0; i < 1_000; i++) {
                ctx.spawn(leaf);
            }
        };
        WorkerPool p = pool(1, mode);
        RunResult r = p.run(Runs.workload("fan-out", List.of(fanOut)), TIMEOUT);

        Runs.assertCleanRun(p, r, 1_001);
        assertEquals(1_000, executed.get());
    }

    /**
     * Late burst: everyone goes idle while one long task runs, then it spawns a burst. Idle
     * workers (including waiting thieves) must still pick that up rather than having quit.
     */
    @ParameterizedTest
    @EnumSource(Mode.class)
    void lateBurstAfterLongIdlePeriodRuns(Mode mode) throws Exception {
        AtomicInteger executed = new AtomicInteger();
        Task leaf = ctx -> {
            executed.incrementAndGet();
            Runs.spin(10_000);
        };
        Task late = ctx -> {
            Runs.spin(50_000_000); // 50 ms with every other worker idle
            for (int i = 0; i < 2_000; i++) {
                ctx.spawn(leaf);
            }
        };
        WorkerPool p = pool(8, mode);
        RunResult r = p.run(Runs.workload("late-burst", List.of(late)), TIMEOUT);

        Runs.assertCleanRun(p, r, 2_001);
        assertEquals(2_000, executed.get());
    }
}

package io.github.nitesh050.sched.integration;

import static io.github.nitesh050.sched.integration.Runs.TIMEOUT;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.nitesh050.sched.common.api.MetricsSink;
import io.github.nitesh050.sched.common.api.Workload;
import io.github.nitesh050.sched.common.config.Mode;
import io.github.nitesh050.sched.engine.RunResult;
import io.github.nitesh050.sched.engine.WorkerPool;
import io.github.nitesh050.sched.queue.QueueType;
import io.github.nitesh050.sched.workloads.FibonacciTree;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Timeout;

/**
 * Switching between the real strategies mid-run, as fast as possible and while steal
 * handshakes are in flight, must never lose or duplicate a task, or hang.
 *
 * <p>Phase 3 replaces the flipper thread with the adaptive controller; the property is the same.
 */
@Timeout(120)
class ModeSwitchE2ETest {

    /** Runs {@code workload} while another thread cycles the mode; returns how often it switched. */
    private static long runWhileFlipping(WorkerPool pool, Workload workload, long expected, long pauseNanos)
            throws Exception {
        AtomicBoolean stop = new AtomicBoolean();
        AtomicLong switches = new AtomicLong();
        Thread flipper = new Thread(() -> {
            Mode[] modes = Mode.values();
            int i = 0;
            while (!stop.get()) {
                if (pool.modeFlag().requestMode(modes[i++ % modes.length])) {
                    switches.incrementAndGet();
                }
                Runs.spin(pauseNanos);
            }
        }, "mode-flipper");
        flipper.start();
        RunResult r;
        try {
            r = pool.run(workload, TIMEOUT);
        } finally {
            stop.set(true);
            flipper.join();
        }
        Runs.assertCleanRun(pool, r, expected);
        return switches.get();
    }

    @RepeatedTest(5)
    void fibonacciWithConstantSwitching() throws Exception {
        FibonacciTree fib = new FibonacciTree(20, 1_000);
        WorkerPool pool = Runs.pool(Runs.config(8, Mode.STATIC_ROUND_ROBIN, true), QueueType.SPSC, 2, MetricsSink.NOOP);
        long switches = runWhileFlipping(pool, fib, fib.expectedTaskCount().orElseThrow(), 0);
        assertTrue(switches > 10, "expected many switches, got " + switches);
    }

    @RepeatedTest(5)
    void independentTasksWithSwitchingEvery100Micros() throws Exception {
        AtomicIntegerArray runs = new AtomicIntegerArray(30_000);
        WorkerPool pool = Runs.pool(Runs.config(8, Mode.NO_WAIT_STEAL, true), QueueType.SPSC, 1, MetricsSink.NOOP);
        runWhileFlipping(pool, Runs.counting(runs, 2_000), runs.length(), 100_000);
        Runs.assertEachRanOnce(runs);
    }

    @RepeatedTest(3)
    void lockingQueuesToo() throws Exception {
        FibonacciTree fib = new FibonacciTree(18, 500);
        WorkerPool pool = Runs.pool(Runs.config(4, Mode.WAIT_BASED_STEAL, true), QueueType.LOCKING, 1, MetricsSink.NOOP);
        runWhileFlipping(pool, fib, fib.expectedTaskCount().orElseThrow(), 0);
    }
}

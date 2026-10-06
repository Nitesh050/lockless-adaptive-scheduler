package io.github.nitesh050.sched.integration;

import static io.github.nitesh050.sched.integration.Runs.TIMEOUT;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.nitesh050.sched.common.api.MetricsSink;
import io.github.nitesh050.sched.common.config.Mode;
import io.github.nitesh050.sched.engine.RunResult;
import io.github.nitesh050.sched.engine.WorkerPool;
import io.github.nitesh050.sched.metrics.MetricsRegistry;
import io.github.nitesh050.sched.metrics.WorkerMetrics;
import io.github.nitesh050.sched.queue.QueueType;
import io.github.nitesh050.sched.strategies.StealingStrategy;
import io.github.nitesh050.sched.workloads.FibonacciTree;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.stream.Stream;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

/** Every task runs exactly once, under every mode, queue type and worker count. */
@Timeout(120)
class ExactlyOnceTest {

    static Stream<Arguments> matrix() {
        Stream.Builder<Arguments> b = Stream.builder();
        for (Mode mode : Mode.values()) {
            for (QueueType queues : QueueType.values()) {
                for (int workers : new int[] {1, 2, 8}) {
                    for (int batch : new int[] {1, 2, StealingStrategy.STEAL_HALF}) {
                        if (batch != 1 && mode == Mode.STATIC_ROUND_ROBIN) {
                            continue; // batch only affects the stealing strategies
                        }
                        b.add(Arguments.of(mode, queues, workers, batch));
                    }
                }
            }
        }
        return b.build();
    }

    @ParameterizedTest(name = "{0} {1} workers={2} batch={3}")
    @MethodSource("matrix")
    void independentTasks(Mode mode, QueueType queues, int workers, int batch) throws Exception {
        AtomicIntegerArray runs = new AtomicIntegerArray(20_000);
        WorkerPool pool = Runs.pool(Runs.config(workers, mode, false), queues, batch, MetricsSink.NOOP);
        RunResult r = pool.run(Runs.counting(runs, 0), TIMEOUT);

        Runs.assertCleanRun(pool, r, runs.length());
        Runs.assertEachRanOnce(runs);
    }

    @ParameterizedTest(name = "{0} {1} workers={2} batch={3}")
    @MethodSource("matrix")
    void fibonacciTree(Mode mode, QueueType queues, int workers, int batch) throws Exception {
        FibonacciTree fib = new FibonacciTree(18, 0);
        WorkerPool pool = Runs.pool(Runs.config(workers, mode, false), queues, batch, MetricsSink.NOOP);
        RunResult r = pool.run(fib, TIMEOUT);

        Runs.assertCleanRun(pool, r, fib.expectedTaskCount().orElseThrow());
    }

    /**
     * With stealing, all spawned tasks start on the root's worker; stealing must actually move
     * work, or the other workers would execute nothing.
     */
    @ParameterizedTest
    @EnumSource(value = Mode.class, names = {"WAIT_BASED_STEAL", "NO_WAIT_STEAL"})
    void stealingActuallySpreadsWork(Mode mode) throws Exception {
        MetricsRegistry metrics = new MetricsRegistry(4);
        FibonacciTree fib = new FibonacciTree(20, 2_000);
        WorkerPool pool = Runs.pool(Runs.config(4, mode, false), QueueType.SPSC, 1, metrics);
        RunResult r = pool.run(fib, TIMEOUT);

        Runs.assertCleanRun(pool, r, fib.expectedTaskCount().orElseThrow());
        long[] perWorker = r.perWorkerExecuted();
        assertTrue(Arrays.stream(perWorker).allMatch(n -> n > 0),
                "every worker should have stolen something: " + Arrays.toString(perWorker));
        assertTrue(metrics.report().total(WorkerMetrics::stealSuccesses) > 0);
    }
}

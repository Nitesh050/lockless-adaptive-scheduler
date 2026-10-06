package io.github.nitesh050.sched.bench;

import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.RecursiveAction;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

/**
 * The same workloads on Java's own work-stealing scheduler, {@link ForkJoinPool}, with 8
 * threads: the reference point for {@link StrategyBenchmark}. Tasks are fork-and-forget, like
 * ours (no join), and the run ends when every task has finished.
 *
 * <p>Run: {@code java -jar sched-bench/target/benchmarks.jar ForkJoinBaseline}
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.SingleShotTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3)
@Measurement(iterations = 10)
@Fork(1)
public class ForkJoinBaseline {

    @Param({"uniform", "fibonacci", "shifting"})
    public String workload;

    private ForkJoinPool pool;
    private final LongAdder executed = new LongAdder();

    @Setup(Level.Trial)
    public void setup() {
        pool = new ForkJoinPool(BenchRuns.WORKERS);
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        pool.shutdown();
    }

    static void spin(long nanos) {
        long end = System.nanoTime() + nanos;
        while (System.nanoTime() - end < 0) {
            Thread.onSpinWait();
        }
    }

    private final class Leaf extends RecursiveAction {
        private static final long serialVersionUID = 1L;
        private final long nanos;

        Leaf(long nanos) {
            this.nanos = nanos;
        }

        @Override
        protected void compute() {
            spin(nanos);
            executed.increment();
        }
    }

    /** Forks every top-level task from one worker, like our root task. */
    private final class Root extends RecursiveAction {
        private static final long serialVersionUID = 1L;
        private final String kind;

        Root(String kind) {
            this.kind = kind;
        }

        @Override
        protected void compute() {
            switch (kind) {
                case "uniform" -> {
                    for (int i = 0; i < 100_000; i++) {
                        new Leaf(10_000).fork();
                    }
                }
                case "shifting" -> {
                    for (int i = 0; i < 100_000; i++) {
                        boolean heavy = i >= 50_000 && i % 8 == 1;
                        new Leaf(heavy ? 100_000 : 10_000).fork();
                    }
                }
                case "fibonacci" -> new Fib(25).fork();
                default -> throw new IllegalArgumentException(kind);
            }
        }
    }

    private final class Fib extends RecursiveAction {
        private static final long serialVersionUID = 1L;
        private final int k;

        Fib(int k) {
            this.k = k;
        }

        @Override
        protected void compute() {
            spin(5_000);
            executed.increment();
            if (k >= 2) {
                new Fib(k - 1).fork();
                new Fib(k - 2).fork();
            }
        }
    }

    @Benchmark
    public long run() {
        executed.reset();
        long expected = workload.equals("fibonacci") ? 242_785 : 100_000;
        pool.execute(new Root(workload));
        // Wait for the exact count: awaitQuiescence alone can return before the root starts.
        long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(2);
        while (executed.sum() < expected) {
            if (System.nanoTime() > deadline) {
                throw new IllegalStateException("ForkJoinPool run did not finish");
            }
            Thread.onSpinWait();
        }
        return executed.sum();
    }
}

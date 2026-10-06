package io.github.nitesh050.sched.bench;

import io.github.nitesh050.sched.common.api.Workload;
import io.github.nitesh050.sched.common.config.SchedulerConfig;
import java.util.concurrent.TimeUnit;
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
import org.openjdk.jmh.annotations.Warmup;

/**
 * Whole runs: each fixed strategy and the adaptive controller, on each workload, 8 workers.
 * SingleShotTime because a run is a long, one-off operation, not a microbenchmark; warm-up
 * iterations absorb JIT compilation.
 *
 * <p>Run: {@code java -jar sched-bench/target/benchmarks.jar StrategyBenchmark}
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.SingleShotTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3)
@Measurement(iterations = 10)
@Fork(1)
public class StrategyBenchmark {

    @Param({"uniform", "fibonacci", "shifting"})
    public String workload;

    @Param({"static", "wait", "nowait", "adaptive"})
    public String mode;

    private SchedulerConfig config;
    private Workload work;

    @Setup(Level.Trial)
    public void setup() {
        config = BenchRuns.config(mode, BenchRuns.WORKERS);
    }

    @Setup(Level.Iteration)
    public void newWorkload() {
        work = BenchRuns.workload(workload);
    }

    @Benchmark
    public long run() throws InterruptedException {
        return BenchRuns.run(config, work).tasksExecuted();
    }
}

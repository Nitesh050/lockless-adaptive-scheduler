package io.github.nitesh050.sched.bench;

import io.github.nitesh050.sched.common.config.SchedulerConfig;
import io.github.nitesh050.sched.workloads.UniformWorkload;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OperationsPerInvocation;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

/**
 * Tasking overhead: time per task as tasks get shorter. With {@code taskMicros = 0} the result
 * is pure scheduling cost per task; with longer tasks it shows how quickly that cost is
 * amortised. Compare {@code adaptive} with {@code static} for the cost of the monitor.
 *
 * <p>Run: {@code java -jar sched-bench/target/benchmarks.jar OverheadBenchmark}
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.SingleShotTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3)
@Measurement(iterations = 10)
@Fork(1)
public class OverheadBenchmark {

    static final int TASKS = 50_000;

    @Param({"0", "1", "10"})
    public int taskMicros;

    @Param({"static", "wait", "nowait", "adaptive"})
    public String mode;

    @Param({"1", "8"})
    public int workers;

    private SchedulerConfig config;

    @Setup(Level.Trial)
    public void setup() {
        config = BenchRuns.config(mode, workers);
    }

    /** Reported per task (OperationsPerInvocation), in nanoseconds. */
    @Benchmark
    @OperationsPerInvocation(TASKS)
    public long perTask() throws InterruptedException {
        return BenchRuns.run(config, new UniformWorkload(TASKS, taskMicros * 1_000L)).tasksExecuted();
    }
}

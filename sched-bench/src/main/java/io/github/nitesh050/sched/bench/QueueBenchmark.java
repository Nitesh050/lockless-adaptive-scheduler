package io.github.nitesh050.sched.bench;

import io.github.nitesh050.sched.common.api.TaskQueue;
import io.github.nitesh050.sched.queue.QueueType;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Group;
import org.openjdk.jmh.annotations.GroupThreads;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

/** Producer/consumer throughput of each queue implementation: one thread offers, one polls. */
@State(Scope.Group)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
public class QueueBenchmark {

    private static final Integer ITEM = 42;

    @Param({"locking", "spsc"})
    public String impl;

    @Param({"1024"})
    public int capacity;

    private TaskQueue<Integer> queue;

    @Setup
    public void setup() {
        queue = QueueType.valueOf(impl.toUpperCase(Locale.ROOT)).create(capacity);
    }

    @Benchmark
    @Group("transfer")
    @GroupThreads(1)
    public boolean offer() {
        return queue.offer(ITEM);
    }

    @Benchmark
    @Group("transfer")
    @GroupThreads(1)
    public Integer poll() {
        return queue.poll();
    }
}

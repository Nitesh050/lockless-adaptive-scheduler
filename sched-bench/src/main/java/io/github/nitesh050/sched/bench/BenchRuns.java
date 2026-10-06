package io.github.nitesh050.sched.bench;

import io.github.nitesh050.sched.adaptive.AdaptiveController;
import io.github.nitesh050.sched.common.api.MetricsSink;
import io.github.nitesh050.sched.common.api.Workload;
import io.github.nitesh050.sched.common.config.AdaptiveThresholds;
import io.github.nitesh050.sched.common.config.Mode;
import io.github.nitesh050.sched.common.config.SchedulerConfig;
import io.github.nitesh050.sched.engine.RunResult;
import io.github.nitesh050.sched.engine.WorkerPool;
import io.github.nitesh050.sched.queue.QueueType;
import io.github.nitesh050.sched.strategies.NoWaitSteal;
import io.github.nitesh050.sched.strategies.StaticRoundRobin;
import io.github.nitesh050.sched.strategies.StealRequestCell;
import io.github.nitesh050.sched.strategies.WaitBasedSteal;
import io.github.nitesh050.sched.workloads.FibonacciTree;
import io.github.nitesh050.sched.workloads.ShiftingWorkload;
import io.github.nitesh050.sched.workloads.UniformWorkload;
import java.time.Duration;

/** Builds the same pools and workloads as the experiment configs, for the JMH benchmarks. */
final class BenchRuns {

    static final int WORKERS = 8;
    private static final Duration TIMEOUT = Duration.ofMinutes(2);

    private BenchRuns() {
    }

    /** "uniform", "fibonacci" or "shifting", at the sizes used in experiments/. */
    static Workload workload(String name) {
        return switch (name) {
            case "uniform" -> new UniformWorkload(100_000, 10_000);
            case "fibonacci" -> new FibonacciTree(25, 5_000);
            case "shifting" -> new ShiftingWorkload(100_000, 10_000, 0.5, 8, 1, 10);
            default -> throw new IllegalArgumentException("unknown workload " + name);
        };
    }

    /** "static", "wait", "nowait" or "adaptive" (adaptive starts in round-robin). */
    static SchedulerConfig config(String mode, int workers) {
        Mode initial = switch (mode) {
            case "static", "adaptive" -> Mode.STATIC_ROUND_ROBIN;
            case "wait" -> Mode.WAIT_BASED_STEAL;
            case "nowait" -> Mode.NO_WAIT_STEAL;
            default -> throw new IllegalArgumentException("unknown mode " + mode);
        };
        return SchedulerConfig.builder()
                .workers(workers)
                .initialMode(initial)
                .adaptive(mode.equals("adaptive"))
                .queueCapacity(1024)
                .stealWaitNanos(10_000)
                .seed(42)
                .thresholds(new AdaptiveThresholds(5, 3, 2.0, 1.3, 0.2, 0.25))
                .build();
    }

    static WorkerPool pool(SchedulerConfig config) {
        StealRequestCell cells = new StealRequestCell(config.workers());
        return WorkerPool.builder(config)
                .strategy(new StaticRoundRobin(config.workers()))
                .strategy(new WaitBasedSteal(cells, config.stealWaitNanos()))
                .strategy(new NoWaitSteal(cells, config.stealWaitNanos(), 1))
                .queueType(QueueType.SPSC)
                .trackTasks(false)
                .build();
    }

    /** One complete run, with the adaptive controller when the config asks for it. */
    static RunResult run(SchedulerConfig config, Workload workload) throws InterruptedException {
        WorkerPool pool = pool(config);
        RunResult result;
        if (config.adaptive()) {
            try (AdaptiveController c = new AdaptiveController(
                    pool.signalSource(), pool.modeFlag(), config.thresholds(), MetricsSink.NOOP)) {
                c.start();
                result = pool.run(workload, TIMEOUT);
            }
        } else {
            result = pool.run(workload, TIMEOUT);
        }
        if (!result.isClean()) {
            throw new IllegalStateException("benchmark run was not clean: " + result.errors());
        }
        return result;
    }
}

package io.github.nitesh050.sched.cli;

import io.github.nitesh050.sched.adaptive.AdaptiveController;
import io.github.nitesh050.sched.adaptive.Signals;
import io.github.nitesh050.sched.common.api.DeadlockReport;
import io.github.nitesh050.sched.common.api.SchedulingStrategy;
import io.github.nitesh050.sched.common.api.Workload;
import io.github.nitesh050.sched.common.config.AdaptiveThresholds;
import io.github.nitesh050.sched.common.config.Mode;
import io.github.nitesh050.sched.common.config.SchedulerConfig;
import io.github.nitesh050.sched.engine.RunResult;
import io.github.nitesh050.sched.engine.WorkerPool;
import io.github.nitesh050.sched.metrics.MetricsRegistry;
import io.github.nitesh050.sched.metrics.MetricsReport;
import io.github.nitesh050.sched.metrics.MetricsSinks;
import io.github.nitesh050.sched.metrics.TraceRecorder;
import io.github.nitesh050.sched.metrics.WorkerMetrics;
import io.github.nitesh050.sched.queue.QueueType;
import io.github.nitesh050.sched.resources.DefaultResourceManager;
import io.github.nitesh050.sched.strategies.StealingStrategy;
import io.github.nitesh050.sched.workloads.DeadlockScenario;
import io.github.nitesh050.sched.workloads.FibonacciTree;
import io.github.nitesh050.sched.workloads.ShiftingWorkload;
import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Runs experiments for the dashboard and packages everything the page draws: timing, per-worker
 * counts, steal statistics, progress over time and the adaptive controller's decisions. Uses the
 * same workload sizes as the Phase 4 experiments (scripts/make_sweep.py).
 */
final class DashboardRunner {

    static final List<String> STRATEGIES = List.of("static", "wait", "nowait", "adaptive", "nowait-half");
    static final List<String> WORKLOADS = List.of("uniform", "fibonacci", "shifting");

    private static final Duration TIMEOUT = Duration.ofMinutes(2);
    private static final int MAX_PROGRESS_POINTS = 400;

    private DashboardRunner() {
    }

    static String label(String strategy) {
        return switch (strategy) {
            case "static" -> "Round-robin";
            case "wait" -> "Wait-based steal-1";
            case "nowait" -> "No-wait steal-1";
            case "adaptive" -> "Adaptive";
            case "nowait-half" -> "No-wait steal-half";
            default -> throw new IllegalArgumentException("unknown strategy " + strategy);
        };
    }

    /** Workload parameters for a worker count, matching experiments/final. */
    static Map<String, Object> workloadSpec(String workload, int workers) {
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("type", workload);
        switch (workload) {
            case "uniform" -> {
                spec.put("tasks", 12_500 * workers);
                spec.put("taskMicros", 10);
            }
            case "fibonacci" -> {
                spec.put("n", workers <= 2 ? 22 : workers <= 6 ? 24 : 25);
                spec.put("nodeMicros", 5);
            }
            case "shifting" -> {
                spec.put("tasks", 12_500 * workers);
                spec.put("taskMicros", 10);
                spec.put("shiftAtFraction", 0.5);
                spec.put("heavyEveryNth", workers);
                spec.put("heavyOffset", 1);
                spec.put("heavyMultiplier", 10);
            }
            default -> throw new IllegalArgumentException("unknown workload " + workload);
        }
        return spec;
    }

    static String describe(String workload, int workers) {
        Map<String, Object> s = workloadSpec(workload, workers);
        return switch (workload) {
            case "uniform" -> String.format("%,d equal tasks of 10 µs", (Integer) s.get("tasks"));
            case "fibonacci" -> String.format("fib(%d) call tree: %,d tasks of 5 µs, created recursively",
                    (Integer) s.get("n"), FibonacciTree.nodes((Integer) s.get("n")));
            default -> String.format("%,d tasks of 10 µs; after the halfway point every %d%s task is 10× heavier",
                    (Integer) s.get("tasks"), workers, workers == 2 ? "nd" : "th");
        };
    }

    private static long totalWorkNanos(Workload w, Map<String, Object> spec) {
        return switch (w) {
            case ShiftingWorkload s -> s.totalWorkNanos();
            case FibonacciTree f -> FibonacciTree.nodes((Integer) spec.get("n")) * 5_000L;
            default -> ((Integer) spec.get("tasks")) * ((Integer) spec.get("taskMicros")) * 1_000L;
        };
    }

    private static SchedulerConfig config(String strategy, int workers) {
        Mode initial = switch (strategy) {
            case "wait" -> Mode.WAIT_BASED_STEAL;
            case "nowait", "nowait-half" -> Mode.NO_WAIT_STEAL;
            default -> Mode.STATIC_ROUND_ROBIN;
        };
        return SchedulerConfig.builder()
                .workers(workers)
                .initialMode(initial)
                .adaptive(strategy.equals("adaptive"))
                .queueCapacity(1024)
                .stealWaitNanos(10_000)
                .seed(42)
                .thresholds(AdaptiveThresholds.defaults())
                .build();
    }

    /**
     * One measured run, after {@code warmup} untimed runs of the same configuration (they let the
     * JIT compile the hot code first, as in the Phase 4 experiments).
     */
    static synchronized Map<String, Object> run(String workloadName, int workers, String strategy, int warmup)
            throws InterruptedException {
        if (!WORKLOADS.contains(workloadName) || !STRATEGIES.contains(strategy)) {
            throw new IllegalArgumentException("unknown workload or strategy");
        }
        if (workers < 2 || workers > 16) {
            throw new IllegalArgumentException("workers must be between 2 and 16");
        }
        Map<String, Object> spec = workloadSpec(workloadName, workers);
        SchedulerConfig config = config(strategy, workers);
        int batch = strategy.equals("nowait-half") ? StealingStrategy.STEAL_HALF : 1;

        for (int i = 0; i < warmup; i++) {
            execute(config, batch, Workloads.create(spec), false);
        }
        Workload workload = Workloads.create(spec);
        Execution e = execute(config, batch, workload, true);

        RunResult r = e.result;
        long expected = workload.expectedTaskCount().orElse(r.tasksExecuted());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("workload", workloadName);
        out.put("workloadDescription", describe(workloadName, workers));
        out.put("workers", workers);
        out.put("strategy", strategy);
        out.put("label", label(strategy));
        out.put("elapsedMs", r.elapsedMillis());
        out.put("idealMs", totalWorkNanos(workload, spec) / (double) workers / 1e6);
        out.put("tasks", r.tasksExecuted());
        out.put("expectedTasks", expected);
        out.put("clean", r.isClean() && r.tasksExecuted() == expected);
        out.put("duplicates", r.duplicateClaims());
        out.put("failed", r.tasksFailed());
        out.put("perWorker", r.perWorkerExecuted());
        out.put("inline", r.inlineExecutions());
        MetricsReport report = e.metrics.report();
        out.put("stealAttempts", report.total(WorkerMetrics::stealAttempts));
        out.put("stealSuccesses", report.total(WorkerMetrics::stealSuccesses));
        out.put("progress", progress(e.trace));
        out.put("switches", switches(e.controller));
        out.put("samples", samples(e.controller));
        out.put("loadAverage", ManagementFactory.getOperatingSystemMXBean().getSystemLoadAverage());
        return out;
    }

    private record Execution(RunResult result, MetricsRegistry metrics, TraceRecorder trace,
                             AdaptiveController controller) {
    }

    private static Execution execute(SchedulerConfig config, int batch, Workload workload, boolean traced)
            throws InterruptedException {
        MetricsRegistry metrics = new MetricsRegistry(config.workers());
        TraceRecorder trace = traced ? new TraceRecorder(config.workers(), 1_000_000, TIMEOUT.toNanos()) : null;
        var sink = trace == null ? metrics : MetricsSinks.fanOut(metrics, trace);
        WorkerPool.Builder b = WorkerPool.builder(config).metrics(sink).queueType(QueueType.SPSC).trackTasks(false);
        for (SchedulingStrategy s : Strategies.all(config, batch)) {
            b.strategy(s);
        }
        WorkerPool pool = b.build();
        AdaptiveController controller = config.adaptive()
                ? new AdaptiveController(pool.signalSource(), pool.modeFlag(), config.thresholds(), sink)
                : null;
        if (trace != null) {
            trace.start();
        }
        RunResult result;
        if (controller == null) {
            result = pool.run(workload, TIMEOUT);
        } else {
            try (controller) {
                controller.start();
                result = pool.run(workload, TIMEOUT);
            }
        }
        return new Execution(result, metrics, trace, controller);
    }

    /** Cumulative % of tasks finished over time, as [time_ms, percent] pairs, downsampled. */
    private static List<double[]> progress(TraceRecorder trace) {
        long[] perBucket = trace.finishedPerBucket();
        long total = 0;
        for (long n : perBucket) {
            total += n;
        }
        List<double[]> points = new ArrayList<>();
        points.add(new double[] {0, 0});
        if (total == 0) {
            return points;
        }
        int step = Math.max(1, perBucket.length / MAX_PROGRESS_POINTS);
        long acc = 0;
        for (int b = 0; b < perBucket.length; b++) {
            acc += perBucket[b];
            if (b % step == step - 1 || b == perBucket.length - 1) {
                points.add(new double[] {(b + 1) * trace.bucketMillis(), 100.0 * acc / total});
            }
        }
        return points;
    }

    private static List<Map<String, Object>> switches(AdaptiveController c) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (c == null) {
            return out;
        }
        for (AdaptiveController.Entry e : c.log()) {
            if (e.decision().switched()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("timeMs", e.atNanos() / 1e6);
                m.put("from", e.signals().mode().name());
                m.put("to", e.decision().mode().name());
                m.put("reason", e.decision().reason());
                out.add(m);
            }
        }
        return out;
    }

    private static List<Map<String, Object>> samples(AdaptiveController c) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (c == null) {
            return out;
        }
        for (AdaptiveController.Entry e : c.log()) {
            Signals s = e.signals();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("timeMs", e.atNanos() / 1e6);
            m.put("mode", e.decision().mode().name());
            m.put("utilization", s.utilization());
            m.put("imbalance", s.imbalance());
            out.add(m);
        }
        return out;
    }

    /** Dining philosophers with deadlock detection; returns the detected cycles. */
    static synchronized Map<String, Object> deadlock(int philosophers, int meals, int holdMicros)
            throws InterruptedException {
        if (philosophers < 2 || philosophers > 8 || meals < 1 || meals > 200 || holdMicros < 0 || holdMicros > 5_000) {
            throw new IllegalArgumentException("philosophers 2-8, meals 1-200, holdMicros 0-5000");
        }
        DefaultResourceManager rm = new DefaultResourceManager();
        DeadlockScenario scenario = new DeadlockScenario(philosophers, meals, holdMicros * 1_000L);
        int workers = philosophers + 1;
        SchedulerConfig config = config("static", workers);
        WorkerPool.Builder b = WorkerPool.builder(config).resourceManager(rm).queueType(QueueType.SPSC);
        for (SchedulingStrategy s : Strategies.all(config, 1)) {
            b.strategy(s);
        }
        RunResult r = b.build().run(scenario, TIMEOUT);

        List<Map<String, Object>> cycles = new ArrayList<>();
        for (DeadlockReport d : rm.reports()) {
            if (cycles.size() == 10) {
                break;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            // task ids: 0 is the engine's root task, philosophers are 1..N in seat order
            m.put("philosophers", d.taskCycle().stream().map(id -> id - 1).toList());
            m.put("resources", d.resources());
            m.put("victim", d.victimTaskId() - 1);
            cycles.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("philosophers", philosophers);
        out.put("meals", meals);
        out.put("elapsedMs", r.elapsedMillis());
        out.put("completed", r.completed());
        out.put("deadlocksDetected", rm.deadlocksDetected());
        out.put("mealsEaten", scenario.mealsEaten());
        out.put("mealsExpected", (long) philosophers * meals);
        out.put("cycles", cycles);
        return out;
    }
}

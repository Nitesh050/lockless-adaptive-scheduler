package io.github.nitesh050.sched.cli;

import io.github.nitesh050.sched.adaptive.AdaptiveController;
import io.github.nitesh050.sched.common.api.MetricsSink;
import io.github.nitesh050.sched.common.api.SchedulingStrategy;
import io.github.nitesh050.sched.common.api.Workload;
import io.github.nitesh050.sched.common.config.SchedulerConfig;
import io.github.nitesh050.sched.engine.RunResult;
import io.github.nitesh050.sched.engine.WorkerPool;
import io.github.nitesh050.sched.metrics.CsvExporter;
import io.github.nitesh050.sched.metrics.DeltaCalculator;
import io.github.nitesh050.sched.metrics.MetricsRegistry;
import io.github.nitesh050.sched.metrics.MetricsReport;
import io.github.nitesh050.sched.metrics.MetricsSinks;
import io.github.nitesh050.sched.metrics.TraceRecorder;
import io.github.nitesh050.sched.metrics.WorkerMetrics;
import io.github.nitesh050.sched.queue.QueueType;
import io.github.nitesh050.sched.resources.DefaultResourceManager;
import io.github.nitesh050.sched.workloads.DeadlockScenario;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Entry point: runs one experiment config and writes its results.
 *
 * <pre>
 *   java -jar scheduler.jar --config experiments/uniform-static.json [--out results/uniform-static]
 *                           [--repeats N] [--queue spsc|locking] [--timeout-seconds 300] [--trace]
 * </pre>
 *
 * Output: {@code runs.csv} (one row per repeat) and {@code workers-<repeat>.csv} (one row per
 * worker) in the output directory; with {@code --trace}, also {@code trace-<repeat>.csv}
 * (tasks finished per worker per millisecond) and {@code modes-<repeat>.csv} (mode switches).
 */
public final class Main {

    private static final List<String> RUN_HEADER = List.of(
            "experiment", "repeat", "workload", "mode", "adaptive", "workers", "queue",
            "completed", "tasks_executed", "elapsed_ms", "throughput_per_s",
            "inline_executions", "failed", "duplicate_claims",
            "steal_attempts", "steal_successes", "steal_success_rate", "imbalance_spread", "imbalance_cv",
            "mode_switches");

    private static final long TRACE_BUCKET_NANOS = 1_000_000;

    private Main() {
    }

    public static void main(String[] args) throws Exception {
        Args a;
        try {
            a = Args.parse(args);
        } catch (IllegalArgumentException e) {
            System.err.println("error: " + e.getMessage());
            System.err.println(Args.USAGE);
            System.exit(2);
            return;
        }
        if (a.help) {
            System.out.println(Args.USAGE);
            return;
        }
        System.exit(run(a) ? 0 : 1);
    }

    /** @return true if every repeat finished cleanly */
    static boolean run(Args a) throws Exception {
        ExperimentConfig exp = ExperimentConfig.load(a.config);
        SchedulerConfig config = exp.scheduler().toConfig();
        int repeats = a.repeats != null ? a.repeats : exp.repeats();
        Path out = a.out != null ? a.out : Path.of("results", exp.name());

        int stealBatch = exp.scheduler().stealBatchOrDefault();

        System.out.printf(Locale.ROOT, "%s: %d workers, %s%s%s, %s queues, %d repeat(s) -> %s%n",
                exp.name(), config.workers(), config.adaptive() ? "adaptive from " : "", config.initialMode(),
                stealBatch == 1 ? "" : " (batch " + (stealBatch == 0 ? "half" : stealBatch) + ")",
                a.queue.name().toLowerCase(Locale.ROOT), repeats, out);

        List<List<?>> rows = new ArrayList<>();
        double[] millis = new double[repeats];
        boolean allClean = true;
        for (int r = 1; r <= repeats; r++) {
            Workload workload = Workloads.create(exp.workload());
            MetricsRegistry metrics = new MetricsRegistry(config.workers());
            TraceRecorder trace = a.trace
                    ? new TraceRecorder(config.workers(), TRACE_BUCKET_NANOS, a.timeout.toNanos())
                    : null;
            MetricsSink sink = trace == null ? metrics : MetricsSinks.fanOut(metrics, trace);
            DefaultResourceManager resources = new DefaultResourceManager();
            WorkerPool.Builder builder = WorkerPool.builder(config)
                    .metrics(sink)
                    .resourceManager(resources)
                    .queueType(a.queue)
                    .trackTasks(false);
            for (SchedulingStrategy s : Strategies.all(config, stealBatch)) {
                builder.strategy(s);
            }
            WorkerPool pool = builder.build();

            if (trace != null) {
                trace.start();
            }
            AdaptiveController controller = config.adaptive()
                    ? new AdaptiveController(pool.signalSource(), pool.modeFlag(), config.thresholds(), sink)
                    : null;
            RunResult result;
            if (controller == null) {
                result = pool.run(workload, a.timeout);
            } else {
                try (controller) {
                    controller.start();
                    result = pool.run(workload, a.timeout);
                }
                Reports.writeControllerLog(out.resolve("adaptive-" + r + ".csv"), controller);
            }
            MetricsReport report = metrics.report();

            long expected = workload.expectedTaskCount().orElse(result.tasksExecuted());
            boolean clean = result.isClean() && result.tasksExecuted() == expected;
            allClean &= clean;
            millis[r - 1] = result.elapsedMillis();

            long[] perWorker = result.perWorkerExecuted();
            long stealAttempts = report.total(WorkerMetrics::stealAttempts);
            long stealSuccesses = report.total(WorkerMetrics::stealSuccesses);
            System.out.printf(Locale.ROOT, "  repeat %2d: %9.2f ms  %,12.0f tasks/s  spread %.3f  steals %d/%d%s  per-worker %s%s%n",
                    r, result.elapsedMillis(), result.throughput(),
                    DeltaCalculator.relativeSpread(perWorker), stealSuccesses, stealAttempts,
                    controller == null ? "" : "  switches " + controller.switches(),
                    Arrays.toString(perWorker),
                    clean ? "" : "  <-- NOT CLEAN: " + describeProblem(result, expected));
            result.errors().stream().limit(3).forEach(e -> System.out.println("      " + e));
            if (resources.deadlocksDetected() > 0) {
                System.out.printf(Locale.ROOT, "      deadlocks detected and broken: %d; first: %s%n",
                        resources.deadlocksDetected(), resources.reports().get(0));
            }
            if (workload instanceof DeadlockScenario d) {
                System.out.printf(Locale.ROOT, "      meals eaten: %d%n", d.mealsEaten());
            }

            rows.add(List.of(exp.name(), r, workload.name(), config.initialMode(), config.adaptive(),
                    config.workers(), a.queue.name().toLowerCase(Locale.ROOT), result.completed(),
                    result.tasksExecuted(), String.format(Locale.ROOT, "%.3f", result.elapsedMillis()),
                    String.format(Locale.ROOT, "%.1f", result.throughput()),
                    result.inlineExecutions(), result.tasksFailed(), result.duplicateClaims(),
                    stealAttempts, stealSuccesses,
                    Double.isNaN(report.stealSuccessRate()) ? "" : String.format(Locale.ROOT, "%.4f", report.stealSuccessRate()),
                    String.format(Locale.ROOT, "%.4f", DeltaCalculator.relativeSpread(perWorker)),
                    String.format(Locale.ROOT, "%.4f", DeltaCalculator.coefficientOfVariation(perWorker)),
                    controller == null ? 0 : controller.switches()));
            CsvExporter.writeWorkers(out.resolve("workers-" + r + ".csv"), report);
            if (trace != null) {
                trace.writeCsv(out.resolve("trace-" + r + ".csv"));
                trace.writeModeSwitchesCsv(out.resolve("modes-" + r + ".csv"));
            }
        }
        CsvExporter.write(out.resolve("runs.csv"), RUN_HEADER, rows);

        Arrays.sort(millis);
        System.out.printf(Locale.ROOT, "median %.2f ms (min %.2f, max %.2f)%s%n",
                median(millis), millis[0], millis[millis.length - 1], allClean ? "" : "  -- SOME RUNS NOT CLEAN");
        return allClean;
    }

    private static String describeProblem(RunResult r, long expected) {
        if (!r.completed()) {
            return "did not complete (hang or crash)";
        }
        if (r.duplicateClaims() > 0) {
            return r.duplicateClaims() + " duplicate claims";
        }
        if (r.tasksFailed() > 0) {
            return r.tasksFailed() + " tasks failed";
        }
        return "executed " + r.tasksExecuted() + " of " + expected + " tasks";
    }

    private static double median(double[] sorted) {
        int n = sorted.length;
        return n % 2 == 1 ? sorted[n / 2] : (sorted[n / 2 - 1] + sorted[n / 2]) / 2;
    }

    static final class Args {
        static final String USAGE = """
                usage: java -jar scheduler.jar --config <experiment.json> [options]
                  --out <dir>             output directory (default results/<experiment name>)
                  --repeats <n>           override the config's repeat count
                  --queue <spsc|locking>  queue implementation (default spsc)
                  --timeout-seconds <s>   declare a run hung after this long (default 300)
                  --trace                 also write per-millisecond traces for the over-time chart
                  --help""";

        Path config;
        Path out;
        Integer repeats;
        QueueType queue = QueueType.SPSC;
        Duration timeout = Duration.ofSeconds(300);
        boolean help;
        boolean trace;

        static Args parse(String[] argv) {
            Args a = new Args();
            for (int i = 0; i < argv.length; i++) {
                String flag = argv[i];
                if (flag.equals("--help") || flag.equals("-h")) {
                    a.help = true;
                    return a;
                }
                if (flag.equals("--trace")) {
                    a.trace = true;
                    continue;
                }
                if (i + 1 >= argv.length) {
                    throw new IllegalArgumentException(flag + " needs a value");
                }
                String value = argv[++i];
                switch (flag) {
                    case "--config" -> a.config = Path.of(value);
                    case "--out" -> a.out = Path.of(value);
                    case "--repeats" -> a.repeats = Integer.parseInt(value);
                    case "--queue" -> a.queue = QueueType.valueOf(value.toUpperCase(Locale.ROOT));
                    case "--timeout-seconds" -> a.timeout = Duration.ofSeconds(Long.parseLong(value));
                    default -> throw new IllegalArgumentException("unknown option " + flag);
                }
            }
            if (a.config == null) {
                throw new IllegalArgumentException("--config is required");
            }
            return a;
        }
    }
}

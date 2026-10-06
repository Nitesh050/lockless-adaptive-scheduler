package io.github.nitesh050.sched.cli;

import io.github.nitesh050.sched.common.api.Workload;
import io.github.nitesh050.sched.common.config.Mode;
import io.github.nitesh050.sched.common.config.SchedulerConfig;
import io.github.nitesh050.sched.engine.RunResult;
import io.github.nitesh050.sched.engine.WorkerPool;
import io.github.nitesh050.sched.metrics.CsvExporter;
import io.github.nitesh050.sched.metrics.MetricsRegistry;
import io.github.nitesh050.sched.queue.QueueType;
import io.github.nitesh050.sched.strategies.StaticRoundRobin;
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
 *                           [--repeats N] [--queue spsc|locking] [--timeout-seconds 300]
 * </pre>
 *
 * Output: {@code runs.csv} (one row per repeat) and {@code workers-<repeat>.csv} (one row per
 * worker) in the output directory.
 */
public final class Main {

    private static final List<String> RUN_HEADER = List.of(
            "experiment", "repeat", "workload", "mode", "adaptive", "workers", "queue",
            "completed", "tasks_executed", "elapsed_ms", "throughput_per_s",
            "inline_executions", "failed", "duplicate_claims");

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

        if (config.adaptive()) {
            throw new UnsupportedOperationException("adaptive runs are not implemented yet (Phase 3)");
        }
        if (config.initialMode() != Mode.STATIC_ROUND_ROBIN) {
            throw new UnsupportedOperationException(config.initialMode() + " is not implemented yet (Phase 2)");
        }

        System.out.printf(Locale.ROOT, "%s: %d workers, %s, %s queues, %d repeat(s) -> %s%n",
                exp.name(), config.workers(), config.initialMode(), a.queue.name().toLowerCase(Locale.ROOT),
                repeats, out);

        List<List<?>> rows = new ArrayList<>();
        double[] millis = new double[repeats];
        boolean allClean = true;
        for (int r = 1; r <= repeats; r++) {
            Workload workload = Workloads.create(exp.workload());
            MetricsRegistry metrics = new MetricsRegistry(config.workers());
            WorkerPool pool = WorkerPool.builder(config)
                    .strategy(new StaticRoundRobin(config.workers()))
                    .metrics(metrics)
                    .queueType(a.queue)
                    .trackTasks(false)
                    .build();

            RunResult result = pool.run(workload, a.timeout);

            long expected = workload.expectedTaskCount().orElse(result.tasksExecuted());
            boolean clean = result.isClean() && result.tasksExecuted() == expected;
            allClean &= clean;
            millis[r - 1] = result.elapsedMillis();

            System.out.printf(Locale.ROOT, "  repeat %2d: %9.2f ms  %,12.0f tasks/s  per-worker %s%s%n",
                    r, result.elapsedMillis(), result.throughput(),
                    Arrays.toString(result.perWorkerExecuted()),
                    clean ? "" : "  <-- NOT CLEAN: " + describeProblem(result, expected));
            result.errors().stream().limit(3).forEach(e -> System.out.println("      " + e));

            rows.add(List.of(exp.name(), r, workload.name(), config.initialMode(), config.adaptive(),
                    config.workers(), a.queue.name().toLowerCase(Locale.ROOT), result.completed(),
                    result.tasksExecuted(), String.format(Locale.ROOT, "%.3f", result.elapsedMillis()),
                    String.format(Locale.ROOT, "%.1f", result.throughput()),
                    result.inlineExecutions(), result.tasksFailed(), result.duplicateClaims()));
            CsvExporter.writeWorkers(out.resolve("workers-" + r + ".csv"), metrics.report());
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
                  --help""";

        Path config;
        Path out;
        Integer repeats;
        QueueType queue = QueueType.SPSC;
        Duration timeout = Duration.ofSeconds(300);
        boolean help;

        static Args parse(String[] argv) {
            Args a = new Args();
            for (int i = 0; i < argv.length; i++) {
                String flag = argv[i];
                if (flag.equals("--help") || flag.equals("-h")) {
                    a.help = true;
                    return a;
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

package io.github.nitesh050.sched.cli;

import io.github.nitesh050.sched.adaptive.AdaptiveController;
import io.github.nitesh050.sched.adaptive.Signals;
import io.github.nitesh050.sched.metrics.CsvExporter;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** CSV output that needs more than one module's types. */
final class Reports {

    private static final List<String> CONTROLLER_HEADER = List.of(
            "time_ms", "mode", "imbalance", "queued", "idle_ratio", "utilization",
            "steal_attempts", "steal_successes", "completed", "decided_mode", "switched", "reason");

    private Reports() {
    }

    /** One row per controller sample: the signals it saw and what it decided. */
    static void writeControllerLog(Path file, AdaptiveController controller) throws IOException {
        List<List<?>> rows = new ArrayList<>();
        for (AdaptiveController.Entry e : controller.log()) {
            Signals s = e.signals();
            rows.add(List.of(
                    String.format(Locale.ROOT, "%.3f", e.atNanos() / 1e6),
                    s.mode(),
                    String.format(Locale.ROOT, "%.3f", s.imbalance()),
                    s.queued(),
                    String.format(Locale.ROOT, "%.3f", s.idleRatio()),
                    String.format(Locale.ROOT, "%.3f", s.utilization()),
                    s.stealAttempts(),
                    s.stealSuccesses(),
                    s.completed(),
                    e.decision().mode(),
                    e.decision().switched(),
                    e.decision().reason()));
        }
        CsvExporter.write(file, CONTROLLER_HEADER, rows);
    }
}

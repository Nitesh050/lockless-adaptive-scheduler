package io.github.nitesh050.sched.metrics;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Writes RFC 4180 CSV files for {@code scripts/plot_results.py}. */
public final class CsvExporter {

    public static final List<String> WORKER_HEADER = List.of(
            "worker", "spawned", "started", "finished", "steal_attempts", "steal_successes", "idle_events");

    private CsvExporter() {
    }

    /** Writes {@code header} and {@code rows}, creating parent directories and replacing the file. */
    public static void write(Path file, List<String> header, List<? extends List<?>> rows) throws IOException {
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        try (BufferedWriter out = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            writeRow(out, header);
            for (List<?> row : rows) {
                if (row.size() != header.size()) {
                    throw new IllegalArgumentException(
                            "row has " + row.size() + " columns, header has " + header.size() + ": " + row);
                }
                writeRow(out, row);
            }
        }
    }

    /** One row per worker. */
    public static void writeWorkers(Path file, MetricsReport report) throws IOException {
        List<List<?>> rows = new ArrayList<>();
        for (WorkerMetrics m : report.workers()) {
            rows.add(List.of(m.worker(), m.spawned(), m.started(), m.finished(),
                    m.stealAttempts(), m.stealSuccesses(), m.idleEvents()));
        }
        write(file, WORKER_HEADER, rows);
    }

    private static void writeRow(BufferedWriter out, List<?> cells) throws IOException {
        for (int i = 0; i < cells.size(); i++) {
            if (i > 0) {
                out.write(',');
            }
            out.write(escape(cells.get(i)));
        }
        out.write('\n');
    }

    static String escape(Object value) {
        String s = String.valueOf(value);
        if (s.indexOf(',') < 0 && s.indexOf('"') < 0 && s.indexOf('\n') < 0 && s.indexOf('\r') < 0) {
            return s;
        }
        return '"' + s.replace("\"", "\"\"") + '"';
    }
}

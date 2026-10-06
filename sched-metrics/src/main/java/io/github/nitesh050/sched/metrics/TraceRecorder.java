package io.github.nitesh050.sched.metrics;

import io.github.nitesh050.sched.common.api.MetricsSink;
import io.github.nitesh050.sched.common.config.Mode;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Records what happened over time, for the "performance over time" chart: tasks finished per
 * worker per time bucket, plus every mode switch with its timestamp.
 *
 * <p>Each worker writes only its own bucket array (no sharing, no atomics); read the results
 * after the run, once the worker threads have been joined.
 */
public final class TraceRecorder implements MetricsSink {

    private final long bucketNanos;
    private final int buckets;
    private final long[][] finished;
    private final ConcurrentLinkedQueue<ModeSwitch> switches = new ConcurrentLinkedQueue<>();
    private volatile long startNanos;

    public record ModeSwitch(long atNanos, Mode from, Mode to) {
    }

    /**
     * @param workers     number of workers
     * @param bucketNanos width of one time bucket
     * @param maxNanos    longest run to record; later events land in the last bucket
     */
    public TraceRecorder(int workers, long bucketNanos, long maxNanos) {
        if (bucketNanos <= 0 || maxNanos < bucketNanos) {
            throw new IllegalArgumentException("need 0 < bucketNanos <= maxNanos");
        }
        this.bucketNanos = bucketNanos;
        this.buckets = (int) Math.min(Integer.MAX_VALUE, (maxNanos + bucketNanos - 1) / bucketNanos);
        this.finished = new long[workers][];
        for (int w = 0; w < workers; w++) {
            finished[w] = new long[buckets];
        }
        this.startNanos = System.nanoTime();
    }

    /** Resets time zero. Call just before the run starts. */
    public void start() {
        startNanos = System.nanoTime();
    }

    private int bucketNow() {
        long b = (System.nanoTime() - startNanos) / bucketNanos;
        return (int) Math.max(0, Math.min(b, buckets - 1));
    }

    @Override
    public void taskFinished(int worker, long taskId) {
        finished[worker][bucketNow()]++;
    }

    @Override
    public void modeSwitched(Mode from, Mode to) {
        switches.add(new ModeSwitch(System.nanoTime() - startNanos, from, to));
    }

    public List<ModeSwitch> modeSwitches() {
        return List.copyOf(switches);
    }

    /**
     * Writes {@code time_ms,worker,finished}: one row per bucket per worker, up to the last
     * bucket in which anything finished.
     */
    public void writeCsv(Path file) throws IOException {
        int last = 0;
        for (long[] row : finished) {
            for (int b = buckets - 1; b > last; b--) {
                if (row[b] != 0) {
                    last = b;
                    break;
                }
            }
        }
        double bucketMs = bucketNanos / 1e6;
        List<List<?>> rows = new ArrayList<>();
        for (int b = 0; b <= last; b++) {
            for (int w = 0; w < finished.length; w++) {
                rows.add(List.of(String.format(java.util.Locale.ROOT, "%.3f", b * bucketMs), w, finished[w][b]));
            }
        }
        CsvExporter.write(file, List.of("time_ms", "worker", "finished"), rows);
    }

    /** Writes {@code time_ms,from,to}, one row per mode switch. */
    public void writeModeSwitchesCsv(Path file) throws IOException {
        List<List<?>> rows = new ArrayList<>();
        for (ModeSwitch s : switches) {
            rows.add(List.of(String.format(java.util.Locale.ROOT, "%.3f", s.atNanos() / 1e6), s.from(), s.to()));
        }
        CsvExporter.write(file, List.of("time_ms", "from", "to"), rows);
    }
}

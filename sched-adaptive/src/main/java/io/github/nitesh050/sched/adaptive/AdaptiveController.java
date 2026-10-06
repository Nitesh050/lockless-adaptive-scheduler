package io.github.nitesh050.sched.adaptive;

import io.github.nitesh050.sched.common.api.MetricsSink;
import io.github.nitesh050.sched.common.api.ModeController;
import io.github.nitesh050.sched.common.api.SignalSource;
import io.github.nitesh050.sched.common.config.AdaptiveThresholds;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

/**
 * The monitor thread: every {@code sampleIntervalMillis} it takes a signal snapshot, asks the
 * {@link DecisionRule} what to do, and flips the mode flag when the rule says so. Workers pick
 * the new mode up at their next decision point.
 *
 * <pre>
 *   try (AdaptiveController c = new AdaptiveController(pool.signalSource(), pool.modeFlag(), thresholds, metrics)) {
 *       c.start();
 *       pool.run(workload, timeout);
 *   }
 * </pre>
 *
 * Every sample and decision is kept in {@link #log()} for the over-time chart.
 */
public final class AdaptiveController implements AutoCloseable {

    /** One sample: the signals, what was decided, and why. */
    public record Entry(long atNanos, Signals signals, DecisionRule.Decision decision) {
    }

    private final SignalSource signals;
    private final ModeController modes;
    private final AdaptiveThresholds thresholds;
    private final MetricsSink metrics;
    private final SignalCollector collector = new SignalCollector();
    private final DecisionRule rule;
    private final List<Entry> log = Collections.synchronizedList(new ArrayList<>());

    private volatile boolean running;
    private volatile long startNanos;
    private volatile int switches;
    private Thread thread;

    public AdaptiveController(SignalSource signals, ModeController modes, AdaptiveThresholds thresholds,
                              MetricsSink metrics) {
        this.signals = Objects.requireNonNull(signals, "signals");
        this.modes = Objects.requireNonNull(modes, "modes");
        this.thresholds = Objects.requireNonNull(thresholds, "thresholds");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.rule = new DecisionRule(thresholds);
    }

    public synchronized void start() {
        if (thread != null) {
            throw new IllegalStateException("already started");
        }
        running = true;
        startNanos = System.nanoTime();
        collector.collect(signals.snapshot()); // baseline for the first interval's deltas
        thread = new Thread(this::loop, "adaptive-controller");
        thread.setDaemon(true);
        thread.start();
    }

    private void loop() {
        long interval = TimeUnit.MILLISECONDS.toNanos(thresholds.sampleIntervalMillis());
        long next = System.nanoTime() + interval;
        while (running) {
            long wait = next - System.nanoTime();
            if (wait > 0) {
                LockSupport.parkNanos(wait);
                continue;
            }
            next += interval;
            step();
        }
    }

    /** One sample and decision. Package-private so tests can drive it without the thread. */
    void step() {
        Signals s = collector.collect(signals.snapshot());
        DecisionRule.Decision d = rule.decide(s);
        if (d.switched() && modes.requestMode(d.mode())) {
            switches++;
            metrics.modeSwitched(s.mode(), d.mode());
        }
        log.add(new Entry(s.atNanos() - startNanos, s, d));
    }

    /** Stops the monitor thread and waits for it. */
    @Override
    public synchronized void close() {
        running = false;
        if (thread != null) {
            LockSupport.unpark(thread);
            try {
                thread.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    public int switches() {
        return switches;
    }

    /** Snapshot of every sample so far. */
    public List<Entry> log() {
        synchronized (log) {
            return List.copyOf(log);
        }
    }

    /** {@link #start()} time, for aligning the log with other traces. */
    public long startNanos() {
        return startNanos;
    }
}

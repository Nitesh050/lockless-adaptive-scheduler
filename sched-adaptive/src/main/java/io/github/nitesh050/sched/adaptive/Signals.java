package io.github.nitesh050.sched.adaptive;

import io.github.nitesh050.sched.common.config.Mode;

/**
 * What the controller decides from: one sample, derived from two consecutive
 * {@code SignalSnapshot}s.
 *
 * @param atNanos          when the sample was taken ({@code System.nanoTime()})
 * @param mode             mode the workers were asked to run in
 * @param workers          worker count
 * @param imbalance        max queue length / mean queue length; 1.0 when perfectly even or empty
 * @param queued           tasks waiting in all queues
 * @param idleRatio        fraction of workers that found their queues empty
 * @param stealAttempts    steal requests posted since the previous sample
 * @param stealSuccesses   requests that moved at least one task since the previous sample
 * @param completed        tasks finished since the previous sample
 */
public record Signals(
        long atNanos,
        Mode mode,
        int workers,
        double imbalance,
        int queued,
        double idleRatio,
        long stealAttempts,
        long stealSuccesses,
        long completed) {

    /** Fraction of workers busy: the controller's measure of how well a mode is doing. */
    public double utilization() {
        return 1.0 - idleRatio;
    }

    /** Successes / attempts since the previous sample, or NaN if there were no attempts. */
    public double stealSuccessRate() {
        return stealAttempts == 0 ? Double.NaN : (double) stealSuccesses / stealAttempts;
    }

    /** Work is waiting, yet a large share of workers is idle: the current mode is not spreading it. */
    public boolean starving(double idleRatioHigh) {
        return idleRatio >= idleRatioHigh && queued >= workers;
    }
}

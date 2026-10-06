package io.github.nitesh050.sched.common.config;

/**
 * Tuning knobs for the adaptive controller. Each "enter" threshold has a matching, looser
 * "exit" threshold so the controller does not flip back on small fluctuations (hysteresis).
 *
 * @param sampleIntervalMillis how often the monitor thread takes a signal snapshot
 * @param consecutiveSamples   how many samples in a row must agree before switching
 * @param imbalanceEnter       queue imbalance (max/mean queue length) above which stealing is wanted
 * @param imbalanceExit        imbalance below which the system counts as balanced again
 * @param stealSuccessLow      steal success rate below which stealing counts as wasted effort
 * @param idleRatioHigh        fraction of idle workers above which the system counts as starving
 */
public record AdaptiveThresholds(
        long sampleIntervalMillis,
        int consecutiveSamples,
        double imbalanceEnter,
        double imbalanceExit,
        double stealSuccessLow,
        double idleRatioHigh) {

    /** Tuned in Phase 4 on a separate tuning workload (see docs/results.md). */
    public static AdaptiveThresholds defaults() {
        return new AdaptiveThresholds(5, 3, 2.0, 1.3, 0.2, 0.4);
    }

    public AdaptiveThresholds {
        if (sampleIntervalMillis <= 0) {
            throw new IllegalArgumentException("sampleIntervalMillis must be > 0");
        }
        if (consecutiveSamples < 1) {
            throw new IllegalArgumentException("consecutiveSamples must be >= 1");
        }
        if (imbalanceExit < 1.0 || imbalanceEnter <= imbalanceExit) {
            throw new IllegalArgumentException(
                    "need 1.0 <= imbalanceExit < imbalanceEnter for hysteresis");
        }
        requireFraction("stealSuccessLow", stealSuccessLow);
        requireFraction("idleRatioHigh", idleRatioHigh);
    }

    private static void requireFraction(String name, double value) {
        if (value < 0.0 || value > 1.0) {
            throw new IllegalArgumentException(name + " must be in [0, 1]");
        }
    }
}

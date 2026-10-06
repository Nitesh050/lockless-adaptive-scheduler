package io.github.nitesh050.sched.metrics;

/**
 * How evenly tasks were spread across workers.
 *
 * <p><b>Note for C:</b> the X-OpenMP paper defines its own task-distribution delta; check
 * its exact formula and add it here as {@code paperDelta}. Until then these two standard
 * measures are reported:
 * <ul>
 *   <li>{@link #relativeSpread}: (max - min) / mean. 0 means perfectly even; 1 means the
 *       busiest worker ran one mean's worth more than the idlest.</li>
 *   <li>{@link #coefficientOfVariation}: population standard deviation / mean.</li>
 * </ul>
 */
public final class DeltaCalculator {

    private DeltaCalculator() {
    }

    public static double relativeSpread(long[] perWorker) {
        double mean = mean(perWorker);
        if (mean == 0) {
            return 0;
        }
        long max = Long.MIN_VALUE;
        long min = Long.MAX_VALUE;
        for (long v : perWorker) {
            max = Math.max(max, v);
            min = Math.min(min, v);
        }
        return (max - min) / mean;
    }

    public static double coefficientOfVariation(long[] perWorker) {
        double mean = mean(perWorker);
        if (mean == 0) {
            return 0;
        }
        double sumSq = 0;
        for (long v : perWorker) {
            double d = v - mean;
            sumSq += d * d;
        }
        return Math.sqrt(sumSq / perWorker.length) / mean;
    }

    private static double mean(long[] values) {
        if (values.length == 0) {
            throw new IllegalArgumentException("no workers");
        }
        double sum = 0;
        for (long v : values) {
            sum += v;
        }
        return sum / values.length;
    }
}

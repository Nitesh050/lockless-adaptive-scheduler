package io.github.nitesh050.sched.common.config;

import java.util.Objects;

/**
 * Everything needed to reproduce a run. Experiment configs in {@code experiments/} map
 * one-to-one onto this record.
 *
 * @param workers          number of worker threads
 * @param initialMode      mode every worker starts in (and stays in, unless adaptive)
 * @param adaptive         whether the adaptive controller may switch modes during the run
 * @param queueCapacity    capacity of each SPSC queue; must be a power of two
 * @param stealWaitNanos   how long a wait-based thief waits for an answer before giving up
 * @param seed             seed for every random choice (victim selection, workload shape)
 * @param thresholds       adaptive controller settings; ignored when {@code adaptive} is false
 */
public record SchedulerConfig(
        int workers,
        Mode initialMode,
        boolean adaptive,
        int queueCapacity,
        long stealWaitNanos,
        long seed,
        AdaptiveThresholds thresholds) {

    public SchedulerConfig {
        if (workers < 1) {
            throw new IllegalArgumentException("workers must be >= 1");
        }
        Objects.requireNonNull(initialMode, "initialMode");
        if (queueCapacity < 2 || Integer.bitCount(queueCapacity) != 1) {
            throw new IllegalArgumentException("queueCapacity must be a power of two >= 2");
        }
        if (stealWaitNanos < 0) {
            throw new IllegalArgumentException("stealWaitNanos must be >= 0");
        }
        Objects.requireNonNull(thresholds, "thresholds");
    }

    public static Builder builder() {
        return new Builder();
    }

    public Builder toBuilder() {
        return new Builder()
                .workers(workers)
                .initialMode(initialMode)
                .adaptive(adaptive)
                .queueCapacity(queueCapacity)
                .stealWaitNanos(stealWaitNanos)
                .seed(seed)
                .thresholds(thresholds);
    }

    public static final class Builder {
        private int workers = Runtime.getRuntime().availableProcessors();
        private Mode initialMode = Mode.STATIC_ROUND_ROBIN;
        private boolean adaptive = false;
        private int queueCapacity = 1024;
        private long stealWaitNanos = 10_000;
        private long seed = 42;
        private AdaptiveThresholds thresholds = AdaptiveThresholds.defaults();

        private Builder() {
        }

        public Builder workers(int workers) {
            this.workers = workers;
            return this;
        }

        public Builder initialMode(Mode initialMode) {
            this.initialMode = initialMode;
            return this;
        }

        public Builder adaptive(boolean adaptive) {
            this.adaptive = adaptive;
            return this;
        }

        public Builder queueCapacity(int queueCapacity) {
            this.queueCapacity = queueCapacity;
            return this;
        }

        public Builder stealWaitNanos(long stealWaitNanos) {
            this.stealWaitNanos = stealWaitNanos;
            return this;
        }

        public Builder seed(long seed) {
            this.seed = seed;
            return this;
        }

        public Builder thresholds(AdaptiveThresholds thresholds) {
            this.thresholds = thresholds;
            return this;
        }

        public SchedulerConfig build() {
            return new SchedulerConfig(
                    workers, initialMode, adaptive, queueCapacity, stealWaitNanos, seed, thresholds);
        }
    }
}

package io.github.nitesh050.sched.cli;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.nitesh050.sched.common.config.AdaptiveThresholds;
import io.github.nitesh050.sched.common.config.Mode;
import io.github.nitesh050.sched.common.config.SchedulerConfig;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;

/**
 * One file in {@code experiments/}. {@code scheduler} maps onto {@link SchedulerConfig} (missing
 * fields take the builder's defaults); {@code workload} is interpreted by {@link Workloads}.
 */
record ExperimentConfig(String name, Integer repeats, Integer warmup, SchedulerSection scheduler,
                        Map<String, Object> workload) {

    private static final ObjectMapper JSON = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);

    ExperimentConfig {
        Objects.requireNonNull(name, "experiment needs a \"name\"");
        Objects.requireNonNull(scheduler, "experiment needs a \"scheduler\" section");
        Objects.requireNonNull(workload, "experiment needs a \"workload\" section");
        if (repeats == null) {
            repeats = 1;
        }
        if (repeats < 1) {
            throw new IllegalArgumentException("repeats must be >= 1");
        }
        if (warmup == null) {
            warmup = 0;
        }
        if (warmup < 0) {
            throw new IllegalArgumentException("warmup must be >= 0");
        }
    }

    static ExperimentConfig load(Path file) throws IOException {
        return JSON.readValue(file.toFile(), ExperimentConfig.class);
    }

    record SchedulerSection(
            Integer workers,
            Mode initialMode,
            Boolean adaptive,
            Integer queueCapacity,
            Long stealWaitNanos,
            Long seed,
            AdaptiveThresholds thresholds,
            Object stealBatch) {

        /**
         * Tasks a victim hands over per request: a number (1 = steal-1, the default; 2 =
         * steal-2) or "half" for steal-half (returned as {@code StealingStrategy.STEAL_HALF}).
         */
        int stealBatchOrDefault() {
            if (stealBatch == null) {
                return 1;
            }
            if (stealBatch instanceof Number n && n.intValue() >= 1) {
                return n.intValue();
            }
            if ("half".equals(stealBatch)) {
                return io.github.nitesh050.sched.strategies.StealingStrategy.STEAL_HALF;
            }
            throw new IllegalArgumentException("stealBatch must be a positive number or \"half\", got " + stealBatch);
        }

        SchedulerConfig toConfig() {
            SchedulerConfig.Builder b = SchedulerConfig.builder();
            if (workers != null) {
                b.workers(workers);
            }
            if (initialMode != null) {
                b.initialMode(initialMode);
            }
            if (adaptive != null) {
                b.adaptive(adaptive);
            }
            if (queueCapacity != null) {
                b.queueCapacity(queueCapacity);
            }
            if (stealWaitNanos != null) {
                b.stealWaitNanos(stealWaitNanos);
            }
            if (seed != null) {
                b.seed(seed);
            }
            if (thresholds != null) {
                b.thresholds(thresholds);
            }
            return b.build();
        }
    }
}

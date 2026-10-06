package io.github.nitesh050.sched.metrics;

import io.github.nitesh050.sched.common.api.MetricsSink;
import io.github.nitesh050.sched.common.config.Mode;
import java.util.List;

/** Helpers for combining sinks. */
public final class MetricsSinks {

    private MetricsSinks() {
    }

    /** A sink that forwards every event to each of {@code sinks}, in order. */
    public static MetricsSink fanOut(MetricsSink... sinks) {
        List<MetricsSink> all = List.of(sinks);
        if (all.size() == 1) {
            return all.get(0);
        }
        MetricsSink[] array = all.toArray(MetricsSink[]::new);
        return new MetricsSink() {
            @Override
            public void taskSpawned(int worker, long taskId) {
                for (MetricsSink s : array) {
                    s.taskSpawned(worker, taskId);
                }
            }

            @Override
            public void taskStarted(int worker, long taskId) {
                for (MetricsSink s : array) {
                    s.taskStarted(worker, taskId);
                }
            }

            @Override
            public void taskFinished(int worker, long taskId) {
                for (MetricsSink s : array) {
                    s.taskFinished(worker, taskId);
                }
            }

            @Override
            public void stealAttempted(int thief, int victim) {
                for (MetricsSink s : array) {
                    s.stealAttempted(thief, victim);
                }
            }

            @Override
            public void stealSucceeded(int thief, int victim) {
                for (MetricsSink s : array) {
                    s.stealSucceeded(thief, victim);
                }
            }

            @Override
            public void workerIdle(int worker) {
                for (MetricsSink s : array) {
                    s.workerIdle(worker);
                }
            }

            @Override
            public void modeSwitched(Mode from, Mode to) {
                for (MetricsSink s : array) {
                    s.modeSwitched(from, to);
                }
            }
        };
    }
}

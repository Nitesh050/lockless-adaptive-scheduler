package io.github.nitesh050.sched.cli;

import io.github.nitesh050.sched.common.api.Workload;
import io.github.nitesh050.sched.workloads.FibonacciTree;
import io.github.nitesh050.sched.workloads.UniformWorkload;
import java.util.Map;

/** Builds a {@link Workload} from the {@code workload} section of an experiment config. */
final class Workloads {

    private Workloads() {
    }

    static Workload create(Map<String, Object> spec) {
        String type = String.valueOf(spec.get("type"));
        return switch (type) {
            case "uniform" -> new UniformWorkload(
                    intParam(spec, "tasks"),
                    longParam(spec, "taskMicros") * 1_000);
            case "fibonacci" -> new FibonacciTree(
                    intParam(spec, "n"),
                    longParam(spec, "nodeMicros") * 1_000);
            case "shifting", "deadlock" ->
                    throw new UnsupportedOperationException("workload \"" + type + "\" is not implemented yet");
            default -> throw new IllegalArgumentException("unknown workload type \"" + type + "\"");
        };
    }

    private static int intParam(Map<String, Object> spec, String key) {
        return Math.toIntExact(longParam(spec, key));
    }

    private static long longParam(Map<String, Object> spec, String key) {
        Object v = spec.get(key);
        if (!(v instanceof Number n)) {
            throw new IllegalArgumentException("workload needs a numeric \"" + key + "\", got " + v);
        }
        return n.longValue();
    }
}

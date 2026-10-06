package io.github.nitesh050.sched.workloads;

import io.github.nitesh050.sched.common.api.TaskContext;
import io.github.nitesh050.sched.common.api.Workload;
import io.github.nitesh050.sched.common.model.Task;
import java.util.List;
import java.util.OptionalLong;

/**
 * The call tree of naive recursive Fibonacci as tasks: {@code fib(k)} burns {@code nodeNanos}
 * and spawns {@code fib(k-1)} and {@code fib(k-2)}; {@code fib(0)} and {@code fib(1)} are
 * leaves. Work is created dynamically and the tree is lopsided (one subtree is always ~1.6x
 * the other), which is where stealing should beat a fixed round-robin deal.
 *
 * <p>Tasks never wait for their children, so the run ends when the whole tree has executed.
 */
public final class FibonacciTree implements Workload {

    private final int n;
    private final long nodeNanos;

    public FibonacciTree(int n, long nodeNanos) {
        if (n < 0 || n > 40) {
            throw new IllegalArgumentException("n must be in [0, 40], got " + n);
        }
        if (nodeNanos < 0) {
            throw new IllegalArgumentException("nodeNanos must be >= 0");
        }
        this.n = n;
        this.nodeNanos = nodeNanos;
    }

    @Override
    public String name() {
        return "fibonacci";
    }

    @Override
    public List<Task> initialTasks() {
        return List.of(new Node(n, nodeNanos));
    }

    @Override
    public OptionalLong expectedTaskCount() {
        return OptionalLong.of(nodes(n));
    }

    /** Number of calls naive fib(k) makes: 2 * fib(k + 1) - 1. */
    public static long nodes(int k) {
        long a = 0;
        long b = 1; // fib(0), fib(1)
        for (int i = 0; i < k + 1; i++) {
            long next = a + b;
            a = b;
            b = next;
        }
        return 2 * a - 1;
    }

    private record Node(int k, long nanos) implements Task {
        @Override
        public void run(TaskContext ctx) {
            DelayTask.spin(nanos);
            if (k >= 2) {
                ctx.spawn(new Node(k - 1, nanos));
                ctx.spawn(new Node(k - 2, nanos));
            }
        }
    }
}

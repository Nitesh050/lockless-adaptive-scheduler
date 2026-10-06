package io.github.nitesh050.sched.resources;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * The resource-allocation state, as an OS textbook draws it: which task holds each
 * (single-instance) resource, and which resource each blocked task is waiting for. Following
 * "task waits for resource, resource is held by task" edges gives the wait-for graph that
 * {@link DeadlockDetector} searches for cycles.
 *
 * <p>Not thread-safe: {@link DefaultResourceManager} guards it with its lock.
 */
public final class AllocationGraph {

    private final Map<String, Long> holderOf = new HashMap<>();
    private final Map<Long, Set<String>> heldBy = new HashMap<>();
    private final Map<Long, String> waitingFor = new HashMap<>();

    /** Task holding {@code resource}, or null if it is free. */
    public Long holder(String resource) {
        return holderOf.get(resource);
    }

    public void grant(String resource, long task) {
        Long current = holderOf.putIfAbsent(resource, task);
        if (current != null) {
            throw new IllegalStateException(resource + " is already held by task " + current);
        }
        heldBy.computeIfAbsent(task, t -> new HashSet<>()).add(resource);
    }

    public void release(String resource, long task) {
        Long current = holderOf.get(resource);
        if (current == null || current != task) {
            throw new IllegalStateException("task " + task + " does not hold " + resource);
        }
        holderOf.remove(resource);
        Set<String> held = heldBy.get(task);
        held.remove(resource);
        if (held.isEmpty()) {
            heldBy.remove(task);
        }
    }

    public Set<String> heldBy(long task) {
        return Collections.unmodifiableSet(heldBy.getOrDefault(task, Set.of()));
    }

    public void startWaiting(long task, String resource) {
        String previous = waitingFor.putIfAbsent(task, resource);
        if (previous != null) {
            throw new IllegalStateException("task " + task + " is already waiting for " + previous);
        }
    }

    public void stopWaiting(long task) {
        waitingFor.remove(task);
    }

    /** Resource {@code task} is blocked on, or null. */
    public String waitingFor(long task) {
        return waitingFor.get(task);
    }

    public Set<Long> waitingTasks() {
        return Collections.unmodifiableSet(waitingFor.keySet());
    }
}

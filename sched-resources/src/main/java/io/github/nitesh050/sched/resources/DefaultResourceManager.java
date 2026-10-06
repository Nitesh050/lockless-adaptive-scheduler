package io.github.nitesh050.sched.resources;

import io.github.nitesh050.sched.common.api.DeadlockException;
import io.github.nitesh050.sched.common.api.DeadlockReport;
import io.github.nitesh050.sched.common.api.ResourceManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Single-instance named resources with deadlock detection on every blocking request.
 *
 * <p>When a request would make the requester wait, the manager adds the wait edge and looks for
 * a cycle through the requester. If there is one, the request fails with a
 * {@link DeadlockException} (the requester is the victim: it releases what it holds when it
 * ends, which breaks the cycle) instead of blocking forever. Otherwise the requester blocks
 * until the resource is released.
 *
 * <p>This is the classic, lock-based OS part of the project and deliberately not lock-free: a
 * global allocation state is what deadlock detection needs, and it is off the scheduler's hot
 * path (only tasks that use resources ever call it). A blocked task blocks its worker thread.
 */
public final class DefaultResourceManager implements ResourceManager {

    private static final int MAX_KEPT_REPORTS = 100;

    private final ReentrantLock lock = new ReentrantLock();
    private final Condition released = lock.newCondition();
    private final AllocationGraph graph = new AllocationGraph();
    private final List<DeadlockReport> reports = new ArrayList<>();
    private long deadlocksDetected;

    @Override
    public void acquire(long taskId, String resource) throws DeadlockException, InterruptedException {
        lock.lock();
        try {
            Long holder = graph.holder(resource);
            if (holder == null) {
                graph.grant(resource, taskId);
                return;
            }
            if (holder == taskId) {
                throw new IllegalStateException("task " + taskId + " already holds " + resource);
            }
            graph.startWaiting(taskId, resource);
            Optional<DeadlockReport> cycle = DeadlockDetector.cycleThrough(graph, taskId);
            if (cycle.isPresent()) {
                graph.stopWaiting(taskId);
                record(cycle.get());
                throw new DeadlockException(cycle.get());
            }
            try {
                while (graph.holder(resource) != null) {
                    released.await();
                }
            } finally {
                graph.stopWaiting(taskId);
            }
            graph.grant(resource, taskId);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public boolean tryAcquire(long taskId, String resource) {
        lock.lock();
        try {
            if (graph.holder(resource) != null) {
                return false;
            }
            graph.grant(resource, taskId);
            return true;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void release(long taskId, String resource) {
        lock.lock();
        try {
            graph.release(resource, taskId);
            released.signalAll();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void releaseAll(long taskId) {
        lock.lock();
        try {
            for (String r : List.copyOf(graph.heldBy(taskId))) {
                graph.release(r, taskId);
            }
            graph.stopWaiting(taskId);
            released.signalAll();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public Optional<DeadlockReport> detectDeadlock() {
        lock.lock();
        try {
            return DeadlockDetector.findAny(graph);
        } finally {
            lock.unlock();
        }
    }

    public long deadlocksDetected() {
        lock.lock();
        try {
            return deadlocksDetected;
        } finally {
            lock.unlock();
        }
    }

    /** The first {@value #MAX_KEPT_REPORTS} deadlocks detected. */
    public List<DeadlockReport> reports() {
        lock.lock();
        try {
            return List.copyOf(reports);
        } finally {
            lock.unlock();
        }
    }

    private void record(DeadlockReport report) {
        deadlocksDetected++;
        if (reports.size() < MAX_KEPT_REPORTS) {
            reports.add(report);
        }
    }
}

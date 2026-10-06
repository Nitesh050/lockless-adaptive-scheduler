package io.github.nitesh050.sched.queue;

import io.github.nitesh050.sched.common.api.TaskQueue;

/**
 * The incoming queues of one worker (the consumer): one queue per producer, so every queue
 * stays single-producer single-consumer. Queue {@code owner} is the "master" queue, where the
 * worker pushes its own tasks; the others are auxiliary queues fed by the other workers.
 *
 * <p>{@link #poll} and the scan cursor belong to the owning worker's thread. Producers only
 * touch {@link #from(int)}.
 */
public final class WorkerQueueSet<T> {

    private final int owner;
    private final TaskQueue<T>[] queues;
    /** Consumer-thread only: where the next auxiliary scan starts, so no producer starves. */
    private int cursor;

    @SuppressWarnings("unchecked")
    public WorkerQueueSet(int owner, int workers, int capacity, QueueType type) {
        if (owner < 0 || owner >= workers) {
            throw new IllegalArgumentException("owner " + owner + " out of range for " + workers + " workers");
        }
        this.owner = owner;
        this.queues = (TaskQueue<T>[]) new TaskQueue<?>[workers];
        for (int p = 0; p < workers; p++) {
            queues[p] = type.create(capacity);
        }
        this.cursor = (owner + 1) % workers;
    }

    public int owner() {
        return owner;
    }

    /** The queue that {@code producer} pushes into. Only that producer may call {@code offer} on it. */
    public TaskQueue<T> from(int producer) {
        return queues[producer];
    }

    /** Takes the next element: master queue first, then the auxiliary queues in rotation. */
    public T poll() {
        T item = queues[owner].poll();
        if (item != null) {
            return item;
        }
        int n = queues.length;
        for (int i = 0; i < n; i++) {
            int p = cursor;
            cursor = p + 1 == n ? 0 : p + 1;
            if (p == owner) {
                continue;
            }
            item = queues[p].poll();
            if (item != null) {
                return item;
            }
        }
        return null;
    }

    /** Sum of all queue sizes; approximate when called from a thread other than the owner. */
    public int size() {
        int total = 0;
        for (TaskQueue<T> q : queues) {
            total += q.size();
        }
        return total;
    }
}

package io.github.nitesh050.sched.queue;

import io.github.nitesh050.sched.common.api.TaskQueue;
import java.util.ArrayDeque;
import java.util.Objects;

/**
 * Bounded queue guarded by a single monitor. This is the day-1 stand-in that lets everyone
 * build against {@link TaskQueue} before {@code SpscQueue} exists, and the baseline the queue
 * benchmark compares the lock-free version against.
 *
 * <p>It happens to be safe for any number of producers and consumers. Do not rely on that:
 * code must respect the single-producer, single-consumer contract, and all stress and
 * integration tests must also pass against {@code SpscQueue}.
 */
public final class LockingQueue<T> implements TaskQueue<T> {

    private final ArrayDeque<T> items;
    private final int capacity;

    public LockingQueue(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be >= 1");
        }
        this.capacity = capacity;
        this.items = new ArrayDeque<>(capacity);
    }

    @Override
    public synchronized boolean offer(T element) {
        Objects.requireNonNull(element, "element");
        if (items.size() == capacity) {
            return false;
        }
        items.addLast(element);
        return true;
    }

    @Override
    public synchronized T poll() {
        return items.pollFirst();
    }

    @Override
    public synchronized int size() {
        return items.size();
    }

    @Override
    public int capacity() {
        return capacity;
    }
}

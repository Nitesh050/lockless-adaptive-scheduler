package io.github.nitesh050.sched.common.api;

/**
 * A bounded FIFO queue of tasks.
 *
 * <p><b>Threading contract:</b> exactly one producer thread calls {@link #offer} and exactly one
 * consumer thread calls {@link #poll} for the lifetime of the queue (they may be the same
 * thread). {@link #size} and {@link #isEmpty} may be called from any thread and return an
 * approximate value when called from a third thread. Implementations that happen to tolerate
 * more producers or consumers (such as {@code LockingQueue}) must not be relied on for that,
 * because {@code SpscQueue} will not.
 *
 * @param <T> element type; null elements are not allowed
 */
public interface TaskQueue<T> {

    /**
     * Appends an element. Producer thread only.
     *
     * @return false if the queue is full; the element was not added
     */
    boolean offer(T element);

    /**
     * Removes the oldest element. Consumer thread only.
     *
     * @return the element, or null if the queue is empty
     */
    T poll();

    /** Number of elements; exact on the producer or consumer thread, approximate elsewhere. */
    int size();

    default boolean isEmpty() {
        return size() == 0;
    }

    int capacity();
}

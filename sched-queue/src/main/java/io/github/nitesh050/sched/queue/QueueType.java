package io.github.nitesh050.sched.queue;

import io.github.nitesh050.sched.common.api.TaskQueue;

/** Which {@link TaskQueue} implementation the engine builds its queue sets from. */
public enum QueueType {
    /** Lock-free single-producer single-consumer ring buffer. The default. */
    SPSC {
        @Override
        public <T> TaskQueue<T> create(int capacity) {
            return new SpscQueue<>(capacity);
        }
    },
    /** Synchronized queue: the day-1 stub, kept as the benchmark baseline. */
    LOCKING {
        @Override
        public <T> TaskQueue<T> create(int capacity) {
            return new LockingQueue<>(capacity);
        }
    };

    public abstract <T> TaskQueue<T> create(int capacity);
}

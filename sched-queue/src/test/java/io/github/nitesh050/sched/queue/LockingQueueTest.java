package io.github.nitesh050.sched.queue;

import io.github.nitesh050.sched.common.api.TaskQueue;

class LockingQueueTest extends TaskQueueContractTest {

    @Override
    protected TaskQueue<Integer> newQueue(int capacity) {
        return new LockingQueue<>(capacity);
    }
}

package io.github.nitesh050.sched.queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.nitesh050.sched.common.api.TaskQueue;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Behaviour every {@link TaskQueue} must have. Add a subclass per implementation
 * (see {@link LockingQueueTest}); {@code SpscQueueTest} should extend this too.
 */
abstract class TaskQueueContractTest {

    /** @param capacity always a power of two */
    protected abstract TaskQueue<Integer> newQueue(int capacity);

    @Test
    void newQueueIsEmpty() {
        TaskQueue<Integer> q = newQueue(8);
        assertTrue(q.isEmpty());
        assertEquals(0, q.size());
        assertNull(q.poll());
    }

    @Test
    void preservesFifoOrder() {
        TaskQueue<Integer> q = newQueue(8);
        for (int i = 0; i < 5; i++) {
            assertTrue(q.offer(i));
        }
        assertEquals(5, q.size());
        for (int i = 0; i < 5; i++) {
            assertEquals(i, q.poll());
        }
        assertNull(q.poll());
    }

    @Test
    void rejectsOfferWhenFull() {
        TaskQueue<Integer> q = newQueue(4);
        for (int i = 0; i < q.capacity(); i++) {
            assertTrue(q.offer(i));
        }
        assertFalse(q.offer(99));
        assertEquals(q.capacity(), q.size());
        assertEquals(0, q.poll());
        assertTrue(q.offer(99), "space freed by poll must be reusable");
    }

    @Test
    void wrapsAroundManyTimes() {
        TaskQueue<Integer> q = newQueue(4);
        for (int i = 0; i < 1_000; i++) {
            assertTrue(q.offer(i));
            assertEquals(i, q.poll());
        }
        assertTrue(q.isEmpty());
    }

    @Test
    void rejectsNull() {
        TaskQueue<Integer> q = newQueue(4);
        assertThrows(NullPointerException.class, () -> q.offer(null));
    }

    /** One producer thread, one consumer thread: nothing lost, nothing duplicated, order kept. */
    @Test
    @Timeout(30)
    void singleProducerSingleConsumerTransfersEverythingInOrder() throws InterruptedException {
        final int count = 1_000_000;
        TaskQueue<Integer> q = newQueue(1024);
        AtomicReference<String> failure = new AtomicReference<>();

        Thread producer = new Thread(() -> {
            for (int i = 0; i < count; i++) {
                while (!q.offer(i)) {
                    Thread.onSpinWait();
                }
            }
        }, "producer");
        Thread consumer = new Thread(() -> {
            int expected = 0;
            while (expected < count) {
                Integer v = q.poll();
                if (v == null) {
                    Thread.onSpinWait();
                    continue;
                }
                if (v != expected) {
                    failure.set("expected " + expected + " but got " + v);
                    return;
                }
                expected++;
            }
        }, "consumer");

        producer.start();
        consumer.start();
        producer.join();
        consumer.join();

        assertNull(failure.get(), failure.get());
        assertTrue(q.isEmpty());
    }
}

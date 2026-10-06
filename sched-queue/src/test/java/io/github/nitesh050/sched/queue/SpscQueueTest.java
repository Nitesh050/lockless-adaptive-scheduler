package io.github.nitesh050.sched.queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.nitesh050.sched.common.api.TaskQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class SpscQueueTest extends TaskQueueContractTest {

    @Override
    protected TaskQueue<Integer> newQueue(int capacity) {
        return new SpscQueue<>(capacity);
    }

    @Test
    void capacityMustBeAPowerOfTwo() {
        assertThrows(IllegalArgumentException.class, () -> new SpscQueue<>(0));
        assertThrows(IllegalArgumentException.class, () -> new SpscQueue<>(1));
        assertThrows(IllegalArgumentException.class, () -> new SpscQueue<>(1000));
    }

    /** A third thread reading size() while both sides run must always see a value in range. */
    @Test
    @Timeout(30)
    void sizeFromAThirdThreadStaysInRange() throws InterruptedException {
        SpscQueue<Integer> q = new SpscQueue<>(64);
        AtomicBoolean stop = new AtomicBoolean();
        AtomicBoolean outOfRange = new AtomicBoolean();

        Thread producer = new Thread(() -> {
            while (!stop.get()) {
                q.offer(1);
            }
        });
        Thread consumer = new Thread(() -> {
            while (!stop.get()) {
                q.poll();
            }
        });
        Thread observer = new Thread(() -> {
            while (!stop.get()) {
                int s = q.size();
                if (s < 0 || s > q.capacity()) {
                    outOfRange.set(true);
                }
            }
        });
        producer.start();
        consumer.start();
        observer.start();
        Thread.sleep(500);
        stop.set(true);
        producer.join();
        consumer.join();
        observer.join();

        assertTrue(!outOfRange.get(), "size() left [0, capacity]");
    }

    /** Fill and drain repeatedly so the indices lap the buffer many times at the full boundary. */
    @Test
    void fullAndEmptyBoundariesHoldAcrossManyLaps() {
        SpscQueue<Integer> q = new SpscQueue<>(4);
        int next = 0;
        int expected = 0;
        for (int lap = 0; lap < 10_000; lap++) {
            while (q.offer(next)) {
                next++;
            }
            assertEquals(4, q.size());
            Integer v;
            while ((v = q.poll()) != null) {
                assertEquals(expected++, v);
            }
            assertEquals(0, q.size());
        }
        assertEquals(next, expected);
    }
}

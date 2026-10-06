package io.github.nitesh050.sched.queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class WorkerQueueSetTest {

    @Test
    void masterQueueIsDrainedFirst() {
        WorkerQueueSet<String> set = new WorkerQueueSet<>(1, 3, 8, QueueType.LOCKING);
        set.from(0).offer("from-0");
        set.from(1).offer("own-a");
        set.from(1).offer("own-b");

        assertEquals("own-a", set.poll());
        assertEquals("own-b", set.poll());
        assertEquals("from-0", set.poll());
        assertNull(set.poll());
    }

    @Test
    void auxiliaryQueuesAreServedInRotation() {
        WorkerQueueSet<String> set = new WorkerQueueSet<>(0, 3, 8, QueueType.LOCKING);
        for (int i = 0; i < 3; i++) {
            set.from(1).offer("p1-" + i);
            set.from(2).offer("p2-" + i);
        }
        List<String> order = new ArrayList<>();
        String s;
        while ((s = set.poll()) != null) {
            order.add(s);
        }
        assertEquals(List.of("p1-0", "p2-0", "p1-1", "p2-1", "p1-2", "p2-2"), order,
                "no producer should be starved by another");
    }

    @Test
    void sizeCountsEveryQueue() {
        WorkerQueueSet<String> set = new WorkerQueueSet<>(0, 4, 8, QueueType.LOCKING);
        for (int p = 0; p < 4; p++) {
            set.from(p).offer("x");
        }
        assertEquals(4, set.size());
        set.poll();
        assertEquals(3, set.size());
    }

    @Test
    void rejectsOwnerOutOfRange() {
        assertThrows(IllegalArgumentException.class, () -> new WorkerQueueSet<>(4, 4, 8, QueueType.LOCKING));
    }
}

package io.github.nitesh050.sched.resources;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.nitesh050.sched.common.api.DeadlockException;
import io.github.nitesh050.sched.common.api.DeadlockReport;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(30)
class ResourceManagerTest {

    // ---- graph and detector -----------------------------------------------------------------

    @Test
    void detectsATwoTaskCycleThroughTheRequester() {
        AllocationGraph g = new AllocationGraph();
        g.grant("A", 1);
        g.grant("B", 2);
        g.startWaiting(1, "B");
        assertTrue(DeadlockDetector.cycleThrough(g, 1).isEmpty(), "1 -> 2, but 2 waits for nothing");
        g.startWaiting(2, "A");

        DeadlockReport r = DeadlockDetector.cycleThrough(g, 2).orElseThrow();
        assertEquals(List.of(2L, 1L), r.taskCycle());
        assertEquals(List.of("A", "B"), r.resources());
        assertEquals(2, r.victimTaskId());
    }

    @Test
    void detectsALongerCycle() {
        AllocationGraph g = new AllocationGraph();
        for (int i = 1; i <= 4; i++) {
            g.grant("R" + i, i);
        }
        g.startWaiting(1, "R2");
        g.startWaiting(2, "R3");
        g.startWaiting(3, "R4");
        assertTrue(DeadlockDetector.findAny(g).isEmpty(), "a chain is not a deadlock");
        g.startWaiting(4, "R1");
        assertEquals(4, DeadlockDetector.cycleThrough(g, 4).orElseThrow().taskCycle().size());
    }

    @Test
    void aCycleNotThroughTheStartIsNotReportedForIt() {
        AllocationGraph g = new AllocationGraph();
        g.grant("A", 1);
        g.grant("B", 2);
        g.grant("C", 3);
        g.startWaiting(1, "B");
        g.startWaiting(2, "A"); // 1 <-> 2
        g.startWaiting(3, "A"); // 3 waits on the cycle but is not in it
        assertTrue(DeadlockDetector.cycleThrough(g, 3).isEmpty());
        assertTrue(DeadlockDetector.findAny(g).isPresent());
    }

    @Test
    void graphRejectsDoubleGrantAndForeignRelease() {
        AllocationGraph g = new AllocationGraph();
        g.grant("A", 1);
        assertThrows(IllegalStateException.class, () -> g.grant("A", 2));
        assertThrows(IllegalStateException.class, () -> g.release("A", 2));
    }

    // ---- manager --------------------------------------------------------------------------

    @Test
    void tryAcquireAndRelease() {
        DefaultResourceManager m = new DefaultResourceManager();
        assertTrue(m.tryAcquire(1, "A"));
        assertFalse(m.tryAcquire(2, "A"));
        m.release(1, "A");
        assertTrue(m.tryAcquire(2, "A"));
        assertThrows(IllegalStateException.class, () -> m.release(1, "A"));
    }

    @Test
    void blockedRequestProceedsWhenTheResourceIsReleased() throws Exception {
        DefaultResourceManager m = new DefaultResourceManager();
        m.acquire(1, "A");
        CountDownLatch granted = new CountDownLatch(1);
        Thread waiter = new Thread(() -> {
            try {
                m.acquire(2, "A");
                granted.countDown();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        waiter.start();
        assertFalse(granted.await(100, TimeUnit.MILLISECONDS), "must block while 1 holds A");
        m.release(1, "A");
        assertTrue(granted.await(5, TimeUnit.SECONDS));
        waiter.join();
    }

    /**
     * The textbook deadlock, forced with a latch: each of two threads holds one resource and
     * requests the other. Exactly one must get a DeadlockException; after it releases, the
     * other must get its resource.
     */
    @Test
    void twoTaskDeadlockIsDetectedAndBroken() throws Exception {
        DefaultResourceManager m = new DefaultResourceManager();
        CountDownLatch bothHoldOne = new CountDownLatch(2);
        AtomicInteger victims = new AtomicInteger();
        AtomicInteger finished = new AtomicInteger();
        AtomicReference<Throwable> unexpected = new AtomicReference<>();

        Thread[] threads = new Thread[2];
        for (int i = 0; i < 2; i++) {
            long task = i + 1;
            String mine = i == 0 ? "A" : "B";
            String theirs = i == 0 ? "B" : "A";
            threads[i] = new Thread(() -> {
                try {
                    m.acquire(task, mine);
                    bothHoldOne.countDown();
                    bothHoldOne.await();
                    try {
                        m.acquire(task, theirs);
                        finished.incrementAndGet();
                    } catch (DeadlockException e) {
                        victims.incrementAndGet();
                    }
                    m.releaseAll(task);
                } catch (Throwable t) {
                    unexpected.set(t);
                }
            });
            threads[i].start();
        }
        for (Thread t : threads) {
            t.join();
        }

        assertEquals(null, unexpected.get());
        assertEquals(1, victims.get(), "exactly one victim");
        assertEquals(1, finished.get(), "the other completes");
        assertEquals(1, m.deadlocksDetected());
        assertEquals(2, m.reports().get(0).taskCycle().size());
        assertTrue(m.detectDeadlock().isEmpty(), "no deadlock left");
    }

    @Test
    void releaseAllWakesWaiters() throws Exception {
        DefaultResourceManager m = new DefaultResourceManager();
        m.acquire(1, "A");
        m.acquire(1, "B");
        CountDownLatch done = new CountDownLatch(2);
        for (String r : List.of("A", "B")) {
            long task = r.equals("A") ? 2 : 3;
            new Thread(() -> {
                try {
                    m.acquire(task, r);
                    done.countDown();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }).start();
        }
        Thread.sleep(50);
        m.releaseAll(1);
        assertTrue(done.await(5, TimeUnit.SECONDS));
    }

    @Test
    void reacquiringAHeldResourceIsABug() throws Exception {
        DefaultResourceManager m = new DefaultResourceManager();
        m.acquire(1, "A");
        assertThrows(IllegalStateException.class, () -> m.acquire(1, "A"));
    }
}

package io.github.nitesh050.sched.common.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

class TaskControlBlockTest {

    private static TaskControlBlock newTcb() {
        return new TaskControlBlock(1, TaskControlBlock.NO_PARENT, ctx -> { }, TaskControlBlock.NO_WORKER);
    }

    @Test
    void startsReadyAndUnowned() {
        TaskControlBlock tcb = newTcb();
        assertEquals(TaskState.READY, tcb.state());
        assertEquals(TaskControlBlock.NO_WORKER, tcb.executedBy());
    }

    @Test
    void transitionSucceedsOnlyFromExpectedState() {
        TaskControlBlock tcb = newTcb();
        assertTrue(tcb.transition(TaskState.READY, TaskState.RUNNING));
        assertFalse(tcb.transition(TaskState.READY, TaskState.RUNNING), "second claim must fail");
        assertTrue(tcb.transition(TaskState.RUNNING, TaskState.FINISHED));
        assertTrue(tcb.state().isTerminal());
    }

    /** The exactly-once tests rely on only one of several racing workers winning the claim. */
    @RepeatedTest(50)
    void onlyOneOfManyRacingClaimsWins() throws InterruptedException {
        TaskControlBlock tcb = newTcb();
        int threads = 8;
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger winners = new AtomicInteger();
        Thread[] ts = new Thread[threads];
        for (int i = 0; i < threads; i++) {
            ts[i] = new Thread(() -> {
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                if (tcb.transition(TaskState.READY, TaskState.RUNNING)) {
                    winners.incrementAndGet();
                }
            });
            ts[i].start();
        }
        start.countDown();
        for (Thread t : ts) {
            t.join();
        }
        assertEquals(1, winners.get());
    }
}

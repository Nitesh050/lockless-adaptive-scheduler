package io.github.nitesh050.sched.integration;

import static io.github.nitesh050.sched.integration.Runs.TIMEOUT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.nitesh050.sched.common.api.DeadlockException;
import io.github.nitesh050.sched.common.config.Mode;
import io.github.nitesh050.sched.common.model.Task;
import io.github.nitesh050.sched.engine.RunResult;
import io.github.nitesh050.sched.engine.WorkerPool;
import io.github.nitesh050.sched.queue.QueueType;
import io.github.nitesh050.sched.resources.DefaultResourceManager;
import io.github.nitesh050.sched.strategies.StaticRoundRobin;
import io.github.nitesh050.sched.workloads.DeadlockScenario;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Timeout;

/** Deadlocks between scheduled tasks are detected, reported and broken, and runs complete. */
@Timeout(120)
class DeadlockTest {

    private static WorkerPool pool(int workers, DefaultResourceManager rm) {
        return WorkerPool.builder(Runs.config(workers, Mode.STATIC_ROUND_ROBIN, false))
                .strategy(new StaticRoundRobin(workers))
                .queueType(QueueType.SPSC)
                .resourceManager(rm)
                .build();
    }

    /** Two tasks on two workers, forced into the textbook deadlock with a latch. */
    @RepeatedTest(5)
    void forcedDeadlockBetweenTasksIsBroken() throws Exception {
        DefaultResourceManager rm = new DefaultResourceManager();
        CountDownLatch bothHoldOne = new CountDownLatch(2);
        AtomicInteger victims = new AtomicInteger();
        AtomicInteger completed = new AtomicInteger();

        Task first = ctx -> {
            ctx.acquire("A");
            bothHoldOne.countDown();
            bothHoldOne.await();
            try {
                ctx.acquire("B");
                completed.incrementAndGet();
            } catch (DeadlockException e) {
                victims.incrementAndGet(); // returning releases A, which unblocks the other
            }
        };
        Task second = ctx -> {
            ctx.acquire("B");
            bothHoldOne.countDown();
            bothHoldOne.await();
            try {
                ctx.acquire("A");
                completed.incrementAndGet();
            } catch (DeadlockException e) {
                victims.incrementAndGet();
            }
        };

        RunResult r = pool(4, rm).run(Runs.workload("forced-deadlock", List.of(first, second)), TIMEOUT);

        assertTrue(r.isClean(), r.errors().toString());
        assertEquals(1, victims.get());
        assertEquals(1, completed.get());
        assertEquals(1, rm.deadlocksDetected());
        assertTrue(rm.detectDeadlock().isEmpty());
    }

    /** Dining philosophers: deadlocks form, get detected, and every meal is still eaten. */
    @RepeatedTest(3)
    void diningPhilosophersFinishDespiteDeadlocks() throws Exception {
        DefaultResourceManager rm = new DefaultResourceManager();
        DeadlockScenario philosophers = new DeadlockScenario(5, 20, 200_000);
        RunResult r = pool(8, rm).run(philosophers, TIMEOUT);

        assertTrue(r.isClean(), r.errors().toString());
        assertEquals(5, r.tasksExecuted());
        assertEquals(100, philosophers.mealsEaten());
        assertEquals(rm.deadlocksDetected(), philosophers.deadlocksSeen(),
                "every detected deadlock reached its victim");
        assertTrue(rm.detectDeadlock().isEmpty());
    }
}

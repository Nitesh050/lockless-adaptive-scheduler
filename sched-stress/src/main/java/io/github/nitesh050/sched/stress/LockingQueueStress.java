package io.github.nitesh050.sched.stress;

import static org.openjdk.jcstress.annotations.Expect.ACCEPTABLE;
import static org.openjdk.jcstress.annotations.Expect.FORBIDDEN;

import io.github.nitesh050.sched.queue.LockingQueue;
import org.openjdk.jcstress.annotations.Actor;
import org.openjdk.jcstress.annotations.Arbiter;
import org.openjdk.jcstress.annotations.JCStressTest;
import org.openjdk.jcstress.annotations.Outcome;
import org.openjdk.jcstress.annotations.State;
import org.openjdk.jcstress.infra.results.II_Result;

/**
 * Template for queue stress tests: one producer, one consumer, then an arbiter drains what is
 * left. The item must be seen exactly once. Copy this for {@code SpscQueueStress}.
 *
 * <p>r1 = what the consumer polled, r2 = what the arbiter polled afterwards (0 means nothing).
 */
@JCStressTest
@Outcome(id = "1, 0", expect = ACCEPTABLE, desc = "consumer saw the item")
@Outcome(id = "0, 1", expect = ACCEPTABLE, desc = "consumer polled before the offer; item still queued")
@Outcome(id = "1, 1", expect = FORBIDDEN, desc = "item duplicated")
@Outcome(id = "0, 0", expect = FORBIDDEN, desc = "item lost")
@State
public class LockingQueueStress {

    private final LockingQueue<Integer> queue = new LockingQueue<>(4);

    @Actor
    public void producer() {
        queue.offer(1);
    }

    @Actor
    public void consumer(II_Result r) {
        Integer v = queue.poll();
        r.r1 = v == null ? 0 : v;
    }

    @Arbiter
    public void drain(II_Result r) {
        Integer v = queue.poll();
        r.r2 = v == null ? 0 : v;
    }
}

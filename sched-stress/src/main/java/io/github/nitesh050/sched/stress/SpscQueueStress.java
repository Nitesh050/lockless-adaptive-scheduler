package io.github.nitesh050.sched.stress;

import static org.openjdk.jcstress.annotations.Expect.ACCEPTABLE;
import static org.openjdk.jcstress.annotations.Expect.FORBIDDEN;

import io.github.nitesh050.sched.queue.SpscQueue;
import org.openjdk.jcstress.annotations.Actor;
import org.openjdk.jcstress.annotations.Arbiter;
import org.openjdk.jcstress.annotations.JCStressTest;
import org.openjdk.jcstress.annotations.Outcome;
import org.openjdk.jcstress.annotations.State;
import org.openjdk.jcstress.infra.results.III_Result;
import org.openjdk.jcstress.infra.results.II_Result;
import org.openjdk.jcstress.infra.results.I_Result;

/**
 * Concurrency tests for {@link SpscQueue}: one producer actor, one consumer actor, raced
 * millions of times. Any outcome not listed as ACCEPTABLE fails the test.
 *
 * <p>Run: {@code java -jar sched-stress/target/jcstress.jar -t SpscQueueStress}
 */
public final class SpscQueueStress {

    private SpscQueueStress() {
    }

    /** One item crosses the queue: it must be seen exactly once (r1 = consumer, r2 = drained after). */
    @JCStressTest
    @Outcome(id = "1, 0", expect = ACCEPTABLE, desc = "consumer got the item")
    @Outcome(id = "0, 1", expect = ACCEPTABLE, desc = "consumer polled first; item still queued")
    @Outcome(id = "1, 1", expect = FORBIDDEN, desc = "item duplicated")
    @Outcome(id = "0, 0", expect = FORBIDDEN, desc = "item lost")
    @State
    public static class NoLossNoDuplication {
        private final SpscQueue<Integer> q = new SpscQueue<>(4);

        @Actor
        public void producer() {
            q.offer(1);
        }

        @Actor
        public void consumer(II_Result r) {
            Integer v = q.poll();
            r.r1 = v == null ? 0 : v;
        }

        @Arbiter
        public void drain(II_Result r) {
            Integer v = q.poll();
            r.r2 = v == null ? 0 : v;
        }
    }

    /** Producer offers 1 then 2; two polls must see a prefix of that order, never a gap or a swap. */
    @JCStressTest
    @Outcome(id = "0, 0", expect = ACCEPTABLE, desc = "consumer ran first")
    @Outcome(id = "0, 1", expect = ACCEPTABLE, desc = "1 arrived between the polls")
    @Outcome(id = "1, 0", expect = ACCEPTABLE, desc = "only 1 arrived in time")
    @Outcome(id = "1, 2", expect = ACCEPTABLE, desc = "both, in order")
    @Outcome(id = "2, .*", expect = FORBIDDEN, desc = "2 overtook 1")
    @Outcome(id = "0, 2", expect = FORBIDDEN, desc = "1 skipped")
    @Outcome(id = "1, 1", expect = FORBIDDEN, desc = "1 duplicated")
    @State
    public static class FifoOrder {
        private final SpscQueue<Integer> q = new SpscQueue<>(4);

        @Actor
        public void producer() {
            q.offer(1);
            q.offer(2);
        }

        @Actor
        public void consumer(II_Result r) {
            Integer a = q.poll();
            Integer b = q.poll();
            r.r1 = a == null ? 0 : a;
            r.r2 = b == null ? 0 : b;
        }
    }

    /**
     * Safe publication: the producer writes a plain field and then offers the object. A consumer
     * that receives the object must see the field's value. This is the property x86 gives for
     * free and the release/acquire pair in SpscQueue has to provide on ARM.
     */
    @JCStressTest
    @Outcome(id = "-1", expect = ACCEPTABLE, desc = "nothing received yet")
    @Outcome(id = "42", expect = ACCEPTABLE, desc = "received with its contents")
    @Outcome(id = "0", expect = FORBIDDEN, desc = "received the object but not its contents")
    @State
    public static class SafePublication {
        static final class Box {
            int value; // deliberately not final: final fields would hide a missing fence
        }

        private final SpscQueue<Box> q = new SpscQueue<>(4);

        @Actor
        public void producer() {
            Box b = new Box();
            b.value = 42;
            q.offer(b);
        }

        @Actor
        public void consumer(I_Result r) {
            Box b = q.poll();
            r.r1 = b == null ? -1 : b.value;
        }
    }

    /**
     * Full-queue boundary: capacity 2, already holding [1, 2]. The producer's offer of 3 may only
     * succeed if the consumer's poll freed a slot, and must never overwrite an unread item.
     * r1 = offer succeeded, r2 = polled value, r3 = what is left, as digits in order.
     */
    @JCStressTest
    @Outcome(id = "0, 1, 2", expect = ACCEPTABLE, desc = "offer saw the queue full; [2] left")
    @Outcome(id = "1, 1, 23", expect = ACCEPTABLE, desc = "poll freed a slot first; [2, 3] left")
    @Outcome(id = "1, .*, 3", expect = FORBIDDEN, desc = "3 overwrote an unread item")
    @Outcome(id = "1, 3, .*", expect = FORBIDDEN, desc = "3 overwrote an unread item")
    @State
    public static class FullBoundary {
        private final SpscQueue<Integer> q = new SpscQueue<>(2);

        public FullBoundary() {
            q.offer(1);
            q.offer(2);
        }

        @Actor
        public void producer(III_Result r) {
            r.r1 = q.offer(3) ? 1 : 0;
        }

        @Actor
        public void consumer(III_Result r) {
            Integer v = q.poll();
            r.r2 = v == null ? 0 : v;
        }

        @Arbiter
        public void drain(III_Result r) {
            int left = 0;
            Integer v;
            while ((v = q.poll()) != null) {
                left = left * 10 + v;
            }
            r.r3 = left;
        }
    }
}

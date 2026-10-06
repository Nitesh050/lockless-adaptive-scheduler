package io.github.nitesh050.sched.stress;

import static org.openjdk.jcstress.annotations.Expect.ACCEPTABLE;
import static org.openjdk.jcstress.annotations.Expect.FORBIDDEN;

import io.github.nitesh050.sched.common.api.MetricsSink;
import io.github.nitesh050.sched.common.api.WorkerView;
import io.github.nitesh050.sched.common.model.TaskControlBlock;
import io.github.nitesh050.sched.queue.SpscQueue;
import io.github.nitesh050.sched.strategies.NoWaitSteal;
import io.github.nitesh050.sched.strategies.StealRequestCell;
import java.util.SplittableRandom;
import java.util.random.RandomGenerator;
import org.openjdk.jcstress.annotations.Actor;
import org.openjdk.jcstress.annotations.Arbiter;
import org.openjdk.jcstress.annotations.JCStressTest;
import org.openjdk.jcstress.annotations.Outcome;
import org.openjdk.jcstress.annotations.State;
import org.openjdk.jcstress.infra.results.IIII_Result;
import org.openjdk.jcstress.infra.results.II_Result;
import org.openjdk.jcstress.infra.results.I_Result;

/**
 * Concurrency tests for the steal handshake ({@link StealRequestCell} plus the victim's
 * {@code serve}), on real SPSC queues. Worker 0 is the victim, worker 1 the thief.
 *
 * <p>Run: {@code java -jar sched-stress/target/jcstress.jar -t StealHandshakeStress}
 */
public final class StealHandshakeStress {

    private StealHandshakeStress() {
    }

    private static final int VICTIM = 0;
    private static final int THIEF = 1;

    private static TaskControlBlock task(long id) {
        return new TaskControlBlock(id, TaskControlBlock.NO_PARENT, ctx -> { }, TaskControlBlock.NO_WORKER);
    }

    /**
     * The victim's side of two workers: its own queue, and the queue it produces into for the
     * thief. Each queue keeps exactly one producer and one consumer, as in the engine.
     */
    static final class VictimView implements WorkerView {
        final SpscQueue<TaskControlBlock> own = new SpscQueue<>(4);       // victim produces + consumes
        final SpscQueue<TaskControlBlock> toThief = new SpscQueue<>(4);   // victim produces, thief consumes

        @Override
        public int id() {
            return VICTIM;
        }

        @Override
        public int workerCount() {
            return 2;
        }

        @Override
        public boolean pushTo(int target, TaskControlBlock tcb) {
            return target == THIEF ? toThief.offer(tcb) : own.offer(tcb);
        }

        @Override
        public void pushLocal(TaskControlBlock tcb) {
            if (!own.offer(tcb)) {
                throw new IllegalStateException("test queue overflow");
            }
        }

        @Override
        public TaskControlBlock pollLocal() {
            return own.poll();
        }

        @Override
        public int localSize() {
            return own.size();
        }

        @Override
        public int approximateSize(int worker) {
            return worker == VICTIM ? own.size() : toThief.size();
        }

        @Override
        public RandomGenerator random() {
            return new SplittableRandom(1);
        }

        @Override
        public MetricsSink metrics() {
            return MetricsSink.NOOP;
        }
    }

    /**
     * The thief withdraws its request while the victim is serving it. Whatever the interleaving,
     * the one task must end up in exactly one queue and the cell must end up free.
     *
     * <p>r1 = withdraw succeeded, r2 = tasks in the thief's queue, r3 = tasks left at the
     * victim, r4 = request still pending.
     */
    @JCStressTest
    @Outcome(id = "1, 0, 1, 0", expect = ACCEPTABLE, desc = "withdrawn before the victim looked")
    @Outcome(id = "0, 1, 0, 0", expect = ACCEPTABLE, desc = "served before the withdraw")
    @Outcome(id = "1, 1, 0, 0", expect = ACCEPTABLE,
            desc = "withdrawn mid-serve: task already in the thief's queue, which it drains anyway")
    @Outcome(id = ".*, 1, 1, .*", expect = FORBIDDEN, desc = "task duplicated")
    @Outcome(id = ".*, 0, 0, .*", expect = FORBIDDEN, desc = "task lost")
    @Outcome(id = ".*, .*, .*, 1", expect = FORBIDDEN, desc = "request left pending forever")
    @State
    public static class ServeVersusWithdraw {
        private final StealRequestCell cells = new StealRequestCell(2);
        private final NoWaitSteal strategy = new NoWaitSteal(cells, 0, 1);
        private final VictimView victim = new VictimView();
        private final long request;

        public ServeVersusWithdraw() {
            victim.own.offer(task(1));
            request = cells.post(VICTIM, THIEF);
        }

        @Actor
        public void victim() {
            strategy.serve(victim);
        }

        @Actor
        public void thief(IIII_Result r) {
            r.r1 = cells.withdraw(VICTIM, request) ? 1 : 0;
        }

        @Arbiter
        public void check(IIII_Result r) {
            r.r2 = victim.toThief.size();
            r.r3 = victim.own.size();
            r.r4 = cells.pending(VICTIM) != 0 ? 1 : 0;
        }
    }

    /** Two thieves race to post to the same victim: exactly one request may win the cell. */
    @JCStressTest
    @Outcome(id = "1, 0", expect = ACCEPTABLE, desc = "thief A won")
    @Outcome(id = "0, 1", expect = ACCEPTABLE, desc = "thief B won")
    @Outcome(id = "1, 1", expect = FORBIDDEN, desc = "both think they own the cell")
    @Outcome(id = "0, 0", expect = FORBIDDEN, desc = "neither got in although the cell was free")
    @State
    public static class TwoThievesOneCell {
        private final StealRequestCell cells = new StealRequestCell(3);

        @Actor
        public void thiefA(II_Result r) {
            r.r1 = cells.post(0, 1) != 0 ? 1 : 0;
        }

        @Actor
        public void thiefB(II_Result r) {
            r.r2 = cells.post(0, 2) != 0 ? 1 : 0;
        }
    }

    /**
     * A thief that sees its request answered must then find the stolen task in its queue: the
     * victim pushes before answering, and the answer's release/acquire must carry the push.
     *
     * <p>r1: -1 = not answered yet, 1 = answered and the task was there, 0 = answered but the
     * task was missing.
     */
    @JCStressTest
    @Outcome(id = "-1", expect = ACCEPTABLE, desc = "thief looked before the answer")
    @Outcome(id = "1", expect = ACCEPTABLE, desc = "answer seen, task found")
    @Outcome(id = "0", expect = FORBIDDEN, desc = "answer seen but the stolen task was not visible")
    @State
    public static class AnswerPublishesTheTask {
        private final StealRequestCell cells = new StealRequestCell(2);
        private final NoWaitSteal strategy = new NoWaitSteal(cells, 0, 1);
        private final VictimView victim = new VictimView();
        private final long request;

        public AnswerPublishesTheTask() {
            victim.own.offer(task(1));
            request = cells.post(VICTIM, THIEF);
        }

        @Actor
        public void victim() {
            strategy.serve(victim);
        }

        @Actor
        public void thief(I_Result r) {
            if (!cells.isAnswered(VICTIM, request)) {
                r.r1 = -1;
                return;
            }
            r.r1 = victim.toThief.poll() != null ? 1 : 0; // the thief is this queue's consumer
        }
    }
}

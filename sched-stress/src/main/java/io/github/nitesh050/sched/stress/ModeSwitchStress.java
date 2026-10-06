package io.github.nitesh050.sched.stress;

import static org.openjdk.jcstress.annotations.Expect.ACCEPTABLE;
import static org.openjdk.jcstress.annotations.Expect.FORBIDDEN;

import io.github.nitesh050.sched.common.api.MetricsSink;
import io.github.nitesh050.sched.common.api.WorkerView;
import io.github.nitesh050.sched.common.model.TaskControlBlock;
import io.github.nitesh050.sched.strategies.NoWaitSteal;
import io.github.nitesh050.sched.strategies.StealRequestCell;
import io.github.nitesh050.sched.stress.StealHandshakeStress.VictimView;
import java.util.SplittableRandom;
import java.util.random.RandomGenerator;
import org.openjdk.jcstress.annotations.Actor;
import org.openjdk.jcstress.annotations.Arbiter;
import org.openjdk.jcstress.annotations.JCStressTest;
import org.openjdk.jcstress.annotations.Outcome;
import org.openjdk.jcstress.annotations.State;
import org.openjdk.jcstress.infra.results.III_Result;

/**
 * Mode switches in the middle of a steal, driven through the strategy hooks the engine calls
 * ({@code onIdle}, {@code onDequeue}, {@code onExit}). Whatever the interleaving, the one task
 * must end up in exactly one queue and no request may be left pending.
 *
 * <p>r1 = tasks in the thief's queue, r2 = tasks left at the victim, r3 = request still pending.
 *
 * <p>Run: {@code java -jar sched-stress/target/jcstress.jar -t ModeSwitchStress}
 */
public final class ModeSwitchStress {

    private ModeSwitchStress() {
    }

    private static final int VICTIM = 0;
    private static final int THIEF = 1;

    /** The thief's side: it only needs to see the victim's queue size to pick it. */
    static final class ThiefView implements WorkerView {
        private final VictimView victim;

        ThiefView(VictimView victim) {
            this.victim = victim;
        }

        @Override
        public int id() {
            return THIEF;
        }

        @Override
        public int workerCount() {
            return 2;
        }

        @Override
        public boolean pushTo(int target, TaskControlBlock tcb) {
            throw new UnsupportedOperationException("the thief never pushes in these tests");
        }

        @Override
        public void pushLocal(TaskControlBlock tcb) {
            throw new UnsupportedOperationException();
        }

        @Override
        public TaskControlBlock pollLocal() {
            throw new UnsupportedOperationException("polling happens in the arbiter");
        }

        @Override
        public int localSize() {
            return victim.toThief.size();
        }

        @Override
        public int approximateSize(int worker) {
            return worker == VICTIM ? victim.own.size() : victim.toThief.size();
        }

        @Override
        public RandomGenerator random() {
            return new SplittableRandom(7);
        }

        @Override
        public MetricsSink metrics() {
            return MetricsSink.NOOP;
        }
    }

    /** Shared setup: the victim has one task, and the thief has posted a no-wait request. */
    abstract static class Base {
        final StealRequestCell cells = new StealRequestCell(2);
        final NoWaitSteal strategy = new NoWaitSteal(cells, Long.MAX_VALUE, 1);
        final VictimView victim = new VictimView();
        final ThiefView thief = new ThiefView(victim);

        Base() {
            victim.own.offer(new TaskControlBlock(1, TaskControlBlock.NO_PARENT, ctx -> { }, TaskControlBlock.NO_WORKER));
            strategy.onIdle(thief); // posts the request to worker 0
            if (cells.pending(VICTIM) == 0) {
                throw new IllegalStateException("setup failed: no request posted");
            }
        }

        void check(III_Result r) {
            r.r1 = victim.toThief.size();
            r.r2 = victim.own.size();
            r.r3 = cells.pending(VICTIM) != 0 ? 1 : 0;
        }
    }

    /** The thief's worker switches away from stealing (onExit) while the victim serves (onDequeue). */
    @JCStressTest
    @Outcome(id = "1, 0, 0", expect = ACCEPTABLE, desc = "served (before or during the thief's exit); task in thief's queue")
    @Outcome(id = "0, 1, 0", expect = ACCEPTABLE, desc = "thief withdrew first; victim kept its task")
    @Outcome(id = "1, 1, .*", expect = FORBIDDEN, desc = "task duplicated")
    @Outcome(id = "0, 0, .*", expect = FORBIDDEN, desc = "task lost")
    @Outcome(id = ".*, .*, 1", expect = FORBIDDEN, desc = "request left pending")
    @State
    public static class ThiefLeavesWhileVictimServes extends Base {
        @Actor
        public void victimDequeues() {
            strategy.onDequeue(victim);
        }

        @Actor
        public void thiefSwitchesMode() {
            strategy.onExit(thief);
        }

        @Arbiter
        public void arbiter(III_Result r) {
            check(r);
        }
    }

    /** Both workers leave stealing mode at once: nothing moves, and the cell must end up free. */
    @JCStressTest
    @Outcome(id = "0, 1, 0", expect = ACCEPTABLE, desc = "task stays with the victim, cell free")
    @Outcome(id = "1, 0, 0", expect = FORBIDDEN, desc = "a declining victim must not hand over work")
    @Outcome(id = ".*, .*, 1", expect = FORBIDDEN, desc = "request left pending")
    @State
    public static class BothLeaveAtOnce extends Base {
        @Actor
        public void victimSwitchesMode() {
            strategy.onExit(victim);
        }

        @Actor
        public void thiefSwitchesMode() {
            strategy.onExit(thief);
        }

        @Arbiter
        public void arbiter(III_Result r) {
            check(r);
        }
    }
}

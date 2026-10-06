package io.github.nitesh050.sched.strategies;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import io.github.nitesh050.sched.common.api.MetricsSink;
import io.github.nitesh050.sched.common.api.WorkerView;
import io.github.nitesh050.sched.common.config.Mode;
import io.github.nitesh050.sched.common.model.TaskControlBlock;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import java.util.random.RandomGenerator;
import org.junit.jupiter.api.Test;

class StaticRoundRobinTest {

    /** Just enough of a worker for strategies that only look at their id. */
    private record FakeWorker(int id, int workerCount) implements WorkerView {
        @Override
        public boolean pushTo(int target, TaskControlBlock tcb) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void pushLocal(TaskControlBlock tcb) {
            throw new UnsupportedOperationException();
        }

        @Override
        public TaskControlBlock pollLocal() {
            return null;
        }

        @Override
        public int localSize() {
            return 0;
        }

        @Override
        public int approximateSize(int worker) {
            return 0;
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

    private static final TaskControlBlock CHILD = new TaskControlBlock(1, 0, ctx -> { }, 0);

    private static List<Integer> targets(StaticRoundRobin s, WorkerView w, int count) {
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            out.add(s.onSpawn(w, CHILD));
        }
        return out;
    }

    @Test
    void dealsInRotationStartingWithItself() {
        StaticRoundRobin s = new StaticRoundRobin(4);
        assertEquals(List.of(2, 3, 0, 1, 2, 3), targets(s, new FakeWorker(2, 4), 6));
    }

    @Test
    void eachWorkerHasItsOwnCursor() {
        StaticRoundRobin s = new StaticRoundRobin(3);
        FakeWorker w0 = new FakeWorker(0, 3);
        FakeWorker w1 = new FakeWorker(1, 3);
        assertEquals(List.of(0, 1), targets(s, w0, 2));
        assertEquals(List.of(1, 2), targets(s, w1, 2));
        assertEquals(List.of(2, 0), targets(s, w0, 2));
    }

    @Test
    void neverSteals() {
        StaticRoundRobin s = new StaticRoundRobin(2);
        assertEquals(Mode.STATIC_ROUND_ROBIN, s.mode());
        assertFalse(s.onIdle(new FakeWorker(0, 2)));
    }
}

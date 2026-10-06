package io.github.nitesh050.sched.engine;

import io.github.nitesh050.sched.common.api.SchedulingStrategy;
import io.github.nitesh050.sched.common.api.WorkerView;
import io.github.nitesh050.sched.common.config.Mode;
import io.github.nitesh050.sched.common.model.TaskControlBlock;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Minimal strategies for engine tests, so sched-engine does not depend on sched-strategies.
 * They also count onEnter/onExit calls to check the engine pairs them.
 */
final class TestStrategies {

    private TestStrategies() {
    }

    static class Counting implements SchedulingStrategy {
        private final Mode mode;
        final AtomicInteger enters = new AtomicInteger();
        final AtomicInteger exits = new AtomicInteger();

        Counting(Mode mode) {
            this.mode = mode;
        }

        @Override
        public Mode mode() {
            return mode;
        }

        @Override
        public void onEnter(WorkerView w) {
            enters.incrementAndGet();
        }

        @Override
        public void onExit(WorkerView w) {
            exits.incrementAndGet();
        }

        @Override
        public int onSpawn(WorkerView w, TaskControlBlock child) {
            return w.id();
        }

        @Override
        public void onDequeue(WorkerView w) {
        }

        @Override
        public boolean onIdle(WorkerView w) {
            return false;
        }
    }

    /** Sends each child to the next worker in rotation. */
    static final class RoundRobin extends Counting {
        private final int[] next;

        RoundRobin(Mode mode, int workers) {
            super(mode);
            next = new int[workers * 32];
        }

        @Override
        public int onSpawn(WorkerView w, TaskControlBlock child) {
            int slot = w.id() * 32;
            int target = next[slot];
            next[slot] = (target + 1) % w.workerCount();
            return target;
        }
    }

    /** Keeps every child on the worker that spawned it. */
    static Counting local(Mode mode) {
        return new Counting(mode);
    }
}

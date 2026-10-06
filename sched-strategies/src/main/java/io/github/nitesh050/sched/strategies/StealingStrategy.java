package io.github.nitesh050.sched.strategies;

import io.github.nitesh050.sched.common.api.SchedulingStrategy;
import io.github.nitesh050.sched.common.api.WorkerView;
import io.github.nitesh050.sched.common.model.TaskControlBlock;

/**
 * What both stealing strategies share: the victim side of the handshake, victim selection,
 * and per-worker thief bookkeeping. Subclasses only decide how a thief waits.
 *
 * <p>Victim side, following X-OpenMP: a worker checks its request cell whenever it passes
 * through its own enqueue or dequeue path ({@link #onSpawn}, {@link #onDequeue}). If a thief
 * is waiting, it moves up to {@code batch} of its queued tasks into the thief's queue, then
 * answers. An idle worker declines requests at once, so no thief waits on an empty victim.
 *
 * <p>Spawned tasks stay on the spawning worker; stealing is what spreads them.
 */
public abstract class StealingStrategy implements SchedulingStrategy {

    /**
     * Batch value meaning "steal half": the victim hands over half of its queued tasks (at
     * least one). The transfer then scales with how much work the victim has piled up.
     */
    public static final int STEAL_HALF = 0;

    /** Random victims to sample before deciding nobody has work worth stealing. */
    private static final int VICTIM_SAMPLES = 4;

    /** Longs per worker in {@link #thiefState}, so each worker's slots fill a cache line. */
    private static final int STRIDE = 16;
    private static final int REQUEST = 0;
    private static final int VICTIM = 1;
    private static final int POSTED_AT = 2;

    protected final StealRequestCell cells;
    protected final long waitNanos;
    private final int batch;

    /**
     * Thief bookkeeping: [REQUEST] = outstanding request word (0 if none), [VICTIM], and
     * [POSTED_AT] in nanos. Each worker reads and writes only its own slots.
     */
    private final long[] thiefState;

    protected StealingStrategy(StealRequestCell cells, long waitNanos, int batch) {
        if (waitNanos < 0) {
            throw new IllegalArgumentException("waitNanos must be >= 0");
        }
        if (batch < 1 && batch != STEAL_HALF) {
            throw new IllegalArgumentException("batch must be >= 1, or STEAL_HALF");
        }
        this.cells = cells;
        this.waitNanos = waitNanos;
        this.batch = batch;
        this.thiefState = new long[(cells.workers() + 1) * STRIDE];
    }

    /** Most tasks a victim hands over per request, or {@link #STEAL_HALF}. */
    public int batch() {
        return batch;
    }

    @Override
    public int onSpawn(WorkerView w, TaskControlBlock child) {
        serve(w);
        return w.id();
    }

    @Override
    public void onDequeue(WorkerView w) {
        serve(w);
    }

    /** Leaves no other worker waiting on this one, and withdraws this worker's own request. */
    @Override
    public void onExit(WorkerView w) {
        declineIncoming(w);
        long request = outstandingRequest(w);
        if (request != 0) {
            cells.withdraw(outstandingVictim(w), request);
            clearOutstanding(w);
        }
    }

    // ---- victim side ---------------------------------------------------------------------

    /**
     * Answers a pending request, handing over up to {@code batch} queued tasks. Public so the
     * stress tests can drive the handshake directly.
     *
     * @return true if at least one task was handed over
     */
    public final boolean serve(WorkerView w) {
        long request = cells.pending(w.id());
        if (request == 0) {
            return false;
        }
        int thief = StealRequestCell.thiefOf(request);
        int limit = batch == STEAL_HALF ? Math.max(1, w.localSize() / 2) : batch;
        int moved = 0;
        while (moved < limit) {
            TaskControlBlock tcb = w.pollLocal();
            if (tcb == null) {
                break;
            }
            if (!w.pushTo(thief, tcb)) {
                w.pushLocal(tcb); // thief's queue from us is full: keep it
                break;
            }
            moved++;
        }
        cells.answer(w.id(), request);
        if (moved > 0) {
            w.metrics().stealSucceeded(thief, w.id());
        }
        return moved > 0;
    }

    /** Declines a pending request without handing anything over. */
    protected final void declineIncoming(WorkerView w) {
        long request = cells.pending(w.id());
        if (request != 0) {
            cells.answer(w.id(), request);
        }
    }

    // ---- thief side ----------------------------------------------------------------------

    /**
     * Picks a victim that looks like it has queued work.
     *
     * @return the victim, or -1 if every sampled worker looked empty
     */
    protected final int pickVictim(WorkerView w) {
        int n = w.workerCount();
        if (n < 2) {
            return -1;
        }
        for (int i = 0; i < VICTIM_SAMPLES; i++) {
            int v = w.random().nextInt(n - 1);
            if (v >= w.id()) {
                v++;
            }
            if (w.approximateSize(v) > 0) {
                return v;
            }
        }
        return -1;
    }

    /**
     * Picks a victim and posts a request to it.
     *
     * @return the request word, or 0 if no request was posted
     */
    protected final long postRequest(WorkerView w) {
        int victim = pickVictim(w);
        if (victim < 0) {
            return 0;
        }
        long request = cells.post(victim, w.id());
        if (request == 0) {
            return 0;
        }
        int slot = slot(w);
        thiefState[slot + REQUEST] = request;
        thiefState[slot + VICTIM] = victim;
        thiefState[slot + POSTED_AT] = System.nanoTime();
        w.metrics().stealAttempted(w.id(), victim);
        return request;
    }

    protected final long outstandingRequest(WorkerView w) {
        return thiefState[slot(w) + REQUEST];
    }

    protected final int outstandingVictim(WorkerView w) {
        return (int) thiefState[slot(w) + VICTIM];
    }

    protected final long outstandingSince(WorkerView w) {
        return thiefState[slot(w) + POSTED_AT];
    }

    protected final void clearOutstanding(WorkerView w) {
        thiefState[slot(w) + REQUEST] = 0;
    }

    private static int slot(WorkerView w) {
        return (w.id() + 1) * STRIDE;
    }
}

package io.github.nitesh050.sched.strategies;

import io.github.nitesh050.sched.common.api.WorkerView;
import io.github.nitesh050.sched.common.config.Mode;

/**
 * No-wait stealing: an idle worker posts a request and goes straight back to checking its own
 * queues. The victim hands tasks over when it next passes through its enqueue/dequeue path.
 * A request still unanswered after {@code waitNanos} is withdrawn and a new victim is tried.
 *
 * <p>The thief never blocks, so it suits victims that are busy in long tasks.
 *
 * <p>Variants: {@code batch = 1} ("steal-1") moves one task per request, {@code batch = 2}
 * ("steal-2") up to two, and {@link StealingStrategy#STEAL_HALF} half the victim's queue.
 * Check the steal-1/steal-2 reading against the X-OpenMP paper; see docs/paper-notes.md.
 */
public final class NoWaitSteal extends StealingStrategy {

    public NoWaitSteal(StealRequestCell cells, long waitNanos, int batch) {
        super(cells, waitNanos, batch);
    }

    @Override
    public Mode mode() {
        return Mode.NO_WAIT_STEAL;
    }

    @Override
    public boolean onIdle(WorkerView w) {
        declineIncoming(w);
        long request = outstandingRequest(w);
        if (request == 0) {
            postRequest(w);
            return false;
        }
        int victim = outstandingVictim(w);
        if (cells.isAnswered(victim, request)) {
            clearOutstanding(w);
            return w.localSize() > 0;
        }
        if (System.nanoTime() - outstandingSince(w) >= waitNanos) {
            // Withdraw and retarget. If the withdraw loses to an answer, the tasks are already
            // in our queue either way.
            cells.withdraw(victim, request);
            clearOutstanding(w);
            return w.localSize() > 0;
        }
        return false;
    }
}

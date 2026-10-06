package io.github.nitesh050.sched.strategies;

import io.github.nitesh050.sched.common.api.WorkerView;
import io.github.nitesh050.sched.common.config.Mode;

/**
 * Wait-based stealing: an idle worker posts a request to a victim and spins until the victim
 * answers or {@code waitNanos} passes, then withdraws. While it waits it keeps declining
 * requests aimed at itself, so two idle workers can never wait on each other.
 *
 * <p>Good when victims answer quickly (they pass through enqueue/dequeue often, i.e. tasks are
 * short); wasteful when they are busy in long tasks, because the thief burns its wait.
 */
public final class WaitBasedSteal extends StealingStrategy {

    /** Steal-1, as in the paper. */
    public WaitBasedSteal(StealRequestCell cells, long waitNanos) {
        this(cells, waitNanos, 1);
    }

    /** @param batch tasks per request, or {@link StealingStrategy#STEAL_HALF} */
    public WaitBasedSteal(StealRequestCell cells, long waitNanos, int batch) {
        super(cells, waitNanos, batch);
    }

    @Override
    public Mode mode() {
        return Mode.WAIT_BASED_STEAL;
    }

    @Override
    public boolean onIdle(WorkerView w) {
        declineIncoming(w);
        long request = postRequest(w);
        if (request == 0) {
            return false;
        }
        int victim = outstandingVictim(w);
        long deadline = System.nanoTime() + waitNanos;
        try {
            while (!cells.isAnswered(victim, request)) {
                declineIncoming(w);
                if (System.nanoTime() - deadline >= 0) {
                    if (cells.withdraw(victim, request)) {
                        return false; // timed out; nothing will arrive
                    }
                    break; // answered just as we gave up
                }
                Thread.onSpinWait();
            }
            return w.localSize() > 0;
        } finally {
            clearOutstanding(w);
        }
    }
}

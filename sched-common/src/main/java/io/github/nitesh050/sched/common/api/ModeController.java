package io.github.nitesh050.sched.common.api;

import io.github.nitesh050.sched.common.config.Mode;

/** Write side of the shared mode flag, used by the adaptive controller. */
public interface ModeController {

    /**
     * Asks every worker to move to {@code next}. Returns immediately; workers pick the change
     * up at their next decision point.
     *
     * @return true if the mode changed, false if {@code next} was already active
     */
    boolean requestMode(Mode next);
}

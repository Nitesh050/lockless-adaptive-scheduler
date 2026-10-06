package io.github.nitesh050.sched.common.api;

import io.github.nitesh050.sched.common.config.Mode;

/**
 * Read side of the shared mode flag. Workers read it only between tasks, so different
 * workers may adopt a new mode a moment apart; the {@link SchedulingStrategy} invariant
 * makes that safe.
 */
public interface ModeSource {

    /** Cheap enough to call on every loop iteration (a single volatile read). */
    Mode currentMode();
}

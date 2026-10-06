package io.github.nitesh050.sched.engine;

import io.github.nitesh050.sched.common.api.ModeController;
import io.github.nitesh050.sched.common.api.ModeSource;
import io.github.nitesh050.sched.common.config.Mode;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The shared "active strategy" value. One writer in practice (the adaptive controller), read
 * by every worker once per loop iteration. A volatile read is enough: workers only need to
 * see the new mode eventually, and they act on it between tasks.
 */
public final class ModeFlag implements ModeSource, ModeController {

    private final AtomicReference<Mode> mode;

    public ModeFlag(Mode initial) {
        this.mode = new AtomicReference<>(Objects.requireNonNull(initial, "initial"));
    }

    @Override
    public Mode currentMode() {
        return mode.get();
    }

    @Override
    public boolean requestMode(Mode next) {
        Objects.requireNonNull(next, "next");
        return mode.getAndSet(next) != next;
    }
}

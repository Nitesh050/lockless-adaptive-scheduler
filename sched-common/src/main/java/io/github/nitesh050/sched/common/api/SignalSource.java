package io.github.nitesh050.sched.common.api;

/** Implemented by the engine; read by the adaptive controller's monitor thread. */
public interface SignalSource {

    /** Safe to call from any thread; must not block or slow down the workers. */
    SignalSnapshot snapshot();
}

package io.github.nitesh050.sched.common.config;

/**
 * The scheduling strategies a worker can be running. Adaptive is not a mode: it is a
 * controller that moves the system between these modes (see {@link SchedulerConfig#adaptive()}).
 */
public enum Mode {
    /** Spawned tasks are dealt out to workers in rotation; no stealing. */
    STATIC_ROUND_ROBIN,
    /** An idle worker posts a steal request and waits a bounded time for the victim to answer. */
    WAIT_BASED_STEAL,
    /** An idle worker posts a steal request and goes back to its own queues immediately. */
    NO_WAIT_STEAL
}

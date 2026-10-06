/**
 * Adaptive layer: the project's contribution.
 *
 * <p>{@link io.github.nitesh050.sched.adaptive.AdaptiveController} (the monitor thread),
 * {@link io.github.nitesh050.sched.adaptive.SignalCollector} and
 * {@link io.github.nitesh050.sched.adaptive.Signals} (what it sees), and
 * {@link io.github.nitesh050.sched.adaptive.DecisionRule} (signal-triggered trials with a
 * utilization check, hysteresis and exponential backoff). See docs/architecture.md.
 */
package io.github.nitesh050.sched.adaptive;

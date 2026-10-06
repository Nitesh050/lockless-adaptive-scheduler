/**
 * Scheduling strategies (owner: B).
 *
 * <p>Done: {@link io.github.nitesh050.sched.strategies.StaticRoundRobin},
 * {@link io.github.nitesh050.sched.strategies.WaitBasedSteal},
 * {@link io.github.nitesh050.sched.strategies.NoWaitSteal} (steal-1 and steal-2 via batch size),
 * their shared base {@link io.github.nitesh050.sched.strategies.StealingStrategy}, and the
 * handshake word {@link io.github.nitesh050.sched.strategies.StealRequestCell}.
 */
package io.github.nitesh050.sched.strategies;

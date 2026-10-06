/**
 * Resource management and deadlock detection: the classic OS part.
 *
 * <p>{@link io.github.nitesh050.sched.resources.DefaultResourceManager},
 * {@link io.github.nitesh050.sched.resources.AllocationGraph},
 * {@link io.github.nitesh050.sched.resources.DeadlockDetector} (wait-for cycle detection on every
 * blocking request; the requester is aborted). The Banker's algorithm is not implemented.
 */
package io.github.nitesh050.sched.resources;

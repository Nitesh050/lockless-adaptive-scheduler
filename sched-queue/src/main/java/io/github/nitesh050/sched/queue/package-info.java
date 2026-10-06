/**
 * Queues (owner: A).
 *
 * <p>Done: {@link io.github.nitesh050.sched.queue.SpscQueue} (lock-free ring buffer, the
 * default), {@link io.github.nitesh050.sched.queue.LockingQueue} (day-1 stub and benchmark
 * baseline), {@link io.github.nitesh050.sched.queue.WorkerQueueSet},
 * {@link io.github.nitesh050.sched.queue.QueueType}.
 * <br>Planned: {@code QueueHint} ("last queue pushed to" optimisation).
 */
package io.github.nitesh050.sched.queue;

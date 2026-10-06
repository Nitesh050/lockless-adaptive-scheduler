/**
 * Queues (owner: A).
 *
 * <p>Planned: {@code SpscQueue} (lock-free single-producer single-consumer ring buffer),
 * {@code WorkerQueueSet} (one queue per producer for each worker), {@code QueueHint}
 * ("last queue pushed to" optimisation). {@link io.github.nitesh050.sched.queue.LockingQueue}
 * is the day-1 stub.
 */
package io.github.nitesh050.sched.queue;

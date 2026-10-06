/**
 * Task model shared by every module.
 *
 * <p>Workers are identified by a plain {@code int} index in {@code [0, workerCount)} rather
 * than a wrapper type, because worker ids appear on every hot-path call (enqueue, dequeue,
 * steal, metrics) and must not allocate. See docs/contracts-changelog.md.
 */
package io.github.nitesh050.sched.common.model;

/**
 * Scheduler runtime: {@link io.github.nitesh050.sched.engine.WorkerPool} (entry point),
 * {@code Worker}, {@link io.github.nitesh050.sched.engine.TcbTable},
 * {@link io.github.nitesh050.sched.engine.ModeFlag}, {@code TaskContextImpl},
 * {@code EngineSignalSource}, {@link io.github.nitesh050.sched.engine.TerminationDetector}.
 * <br>Not implemented: {@code SnapshotDumper} (the TCB table's {@code countByState} covers the
 * auditing it was for).
 */
package io.github.nitesh050.sched.engine;

# Contracts changelog

Every change to an interface or model class in `sched-common` gets an entry here, newest
first. A change needs agreement from the owners of every module that implements or calls it.

Format: `## vX — YYYY-MM-DD — summary`, then what changed, why, and who agreed.

---

## v0.2 — 2026-10-06 — TaskControlBlock made cheaper to create

- **Removed `TaskControlBlock.createdNanos()`.** Nothing read it, and calling
  `System.nanoTime()` for every task was measurable on the spawn path. `TraceRecorder` should
  timestamp events itself.
- **`state` and `executedBy` are no longer `volatile` fields.** They are still only accessed
  through VarHandles with explicit modes (CAS, acquire, release), so behaviour is unchanged.
  Initialising a volatile field adds a full fence on ARM. That fence is unnecessary, because a
  TCB only reaches another thread through a queue, and the queue's release/acquire publishes
  it.

Why: found while profiling the Phase 1 engine (see architecture.md, "Initial tasks").

## v0.1 — 2026-10-06 — onSpawn semantics clarified (docs only, no signature change)

- **`onSpawn` may answer steal requests.** X-OpenMP victims check on enqueue as well as
  dequeue. Without this, a worker that spawns many tasks in a row never answers anyone.
- **Spawn fallback is defined:** target queue, then own queue, then run the child
  immediately (undeferred).
- **Initial tasks go through `onSpawn`**, from a root task on worker 0, so strategies decide
  the initial distribution too.

Why: found while building the Phase 1 engine; B needs this for the stealing strategies.

## v0 — 2026-10-06 — initial contracts (Phase 0)

Initial version of every contract. These are frozen except for `SchedulingStrategy` and
`WorkerView`. Those two are v0 and get one planned revision at the end of week 1, once A
and B have designed the steal handshake.

Decisions and deviations from the original plan:

- **`WorkerId` dropped; workers are plain `int` indices.** Worker ids appear on every
  hot-path call (push, poll, steal, metrics), and a wrapper object adds allocation or
  indirection there.
- **`WorkerView` added.** This is what a strategy may do on behalf of a worker. Strategies
  never see queues directly, which keeps the SPSC rule enforceable.
- **`SchedulingStrategy` gained `onEnter` / `onExit`.** They make mode switches safe: `onExit`
  withdraws and declines pending steal requests.
- **`WorkerView.pushLocal` never fails.** It spills to a worker-private overflow, so a victim
  whose push to a thief fails always has somewhere to put the task. This is what makes the
  rule "a strategy never holds a task" possible.
- **`acquire` blocks and throws `DeadlockException`; `tryAcquire` does not block.** A blocked
  task blocks its worker; this is a known limitation, documented in the report.
- **Termination rule fixed:** an outstanding-task counter, incremented when a TCB is created
  and decremented when it finishes (see architecture.md).
- **Steal counters reach `SignalSource` through the engine's counting `MetricsSink`**
  wrapper, so `sched-adaptive` does not depend on `sched-metrics`.

Agreed by: _A, B, C to sign off here_

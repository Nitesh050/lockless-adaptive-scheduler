# Architecture

## Modules and dependencies

```
sched-common  ◄── sched-queue ◄── sched-engine
     ▲                 ▲
     ├── sched-strategies (common + queue)
     ├── sched-adaptive   (common only)
     ├── sched-resources  (common only)
     ├── sched-metrics    (common only)
     └── sched-workloads  (common only)

sched-cli, sched-integration, sched-bench, sched-stress ──► may depend on everything
```

Modules talk only through the interfaces in `sched-common/.../api`. `sched-cli` builds the
real objects and passes them in. For example, the engine receives a `ResourceManager` and
never sees `sched-resources`.

## Queue layout

This follows X-OpenMP's XQueue. With N workers, each worker owns N queues, one per
producer:

```
             consumer = worker j
             ┌───────────────────────────────┐
producer 0 ─►│ q[j][0]                       │
producer 1 ─►│ q[j][1]                       │
   ...       │  ...                          │
producer j ─►│ q[j][j]   "master" (own pushes)│
   ...       │  ...                          │
             └───────────────────────────────┘
```

Every queue has exactly one producer and one consumer, so no queue needs a lock or a CAS.
Strategies never touch queues directly. They go through `WorkerView`, which only allows
pushes from the calling worker, so the SPSC rule cannot be broken by accident.

`WorkerView.pushLocal` never fails. If the master queue is full, the task spills into an
overflow list that only the owning worker touches. This means no strategy ever has to keep
a task in its hands.

## Worker loop

```
start:  strategy(mode).onEnter(w)
loop:
    m = modeSource.currentMode()                   // one volatile read
    if m != current:  old.onExit(w); new.onEnter(w) // only between tasks
    tcb = w.pollLocal()
    if tcb != null:
        strategy.onDequeue(w)                       // victim side: answer steal requests
        claim tcb (READY → RUNNING CAS), run it, mark FINISHED/FAILED
    else:
        if termination detected: exit
        if !strategy.onIdle(w): back off            // thief side
```

A task that calls `ctx.spawn(child)` gets a new TCB. The strategy's `onSpawn` picks the
target worker, and the engine pushes there. If that queue is full, it tries this worker's own
master queue. If that is full too, the child runs immediately on the spawning worker
("undeferred"), as OpenMP runtimes do when their task queues fill up. This bounds memory, and
`RunResult.inlineExecutions` counts how often it happened.

**Initial tasks.** The engine wraps the workload's initial tasks in a root task on worker 0,
which spawns them through `onSpawn` like any other child. So the active strategy also decides
how the initial work is spread. This mirrors an OpenMP program creating its tasks from one
thread, and it is what makes round-robin and stealing distribute the same workload
differently. The root task is excluded from all task counts.

Consequence: worker 0 is on the critical path, because it spawns everything and also runs
its own share. Measured in week 1 on an Apple Silicon Mac (100k tasks of 10 µs, 8 workers):
the ideal is 125 ms, and the measured medians were 163 ms with `LockingQueue` and 143.5 ms
with `SpscQueue`. Earlier measurements of about 200 ms were taken while jcstress and profiler
runs were still loading the machine (load average about 5). Final experiments must run on an
idle machine, and should record the load average.

The profile (JFR, 1M empty tasks) shows the time is **not** in the queue, which takes under
1% of worker 0's samples. It is in creating each task's TCB: about 0.4 µs per spawn with 8
workers, against about 0.05 µs with 1 worker, for the same code. Two cheap explanations were
tested and ruled out, or found to be minor:
- false sharing on the id counter: padding it changed little
- volatile-field fences: no measurable change, though removing them is still correct

`System.nanoTime()` per task cost a few percent and was removed. The remaining hypothesis is
cache-coherence traffic. Worker 0 writes each TCB into fresh memory, other cores then claim it
with a CAS and take ownership of the cache line, and worker 0 keeps paying misses as it
allocates. If Phase 4's overhead benchmark confirms this matters, the fix is per-worker TCB
reuse (free lists) instead of allocating every task.

## Steal handshake (to be finalised by A + B in week 1)

Each worker has a `StealRequestCell`: one word that packs a round number and the thief's id.

1. The thief CASes its id and the current round into the victim's cell.
2. The victim checks its cell in `onDequeue`. If there is a request, it does
   `pollLocal()` → `pushTo(thief)` (or `pushLocal` if that push fails), then clears the cell.
3. Wait-based: the thief spins with backoff for up to `stealWaitNanos`, watching its own
   queue from that victim. No-wait: the thief returns at once.

Steal-success counts are recorded by the victim (`MetricsSink.stealSucceeded`).

## Mode switching

`ModeFlag` holds one volatile `Mode`. The adaptive controller writes it, and workers read it
only between tasks, so workers may adopt a new mode a moment apart.

This is safe because of one invariant: **a strategy never holds a task across hook calls.**
Tasks only ever move straight from one queue into another, and every mode drains all of a
worker's queues. So a task is always in exactly one queue or owned by exactly one worker,
and a switch cannot strand it. A steal answer that arrives after the thief gave up just sits
in the thief's queue until the thief picks it up. On `onExit`, a worker withdraws its own
pending request and declines any request waiting for it, so nobody waits on a worker that
stopped listening.

## Termination

The engine keeps one counter of outstanding tasks:

- it is **incremented when a TCB is created**, before the task is enqueued. That covers both
  initial tasks and spawned children.
- it is **decremented when a TCB reaches FINISHED or FAILED**.

A child is counted before its parent finishes, so the counter cannot reach 0 while work
still exists. The run is over when the counter is 0 after all initial tasks were submitted.
Workers read the counter only when they are idle, but every spawn and every completion still
updates it, so it is a shared cache line that all workers write. Profiling in week 1 did not
show it as the main cost (see "Initial tasks" above). If it ever becomes one, replace it with
per-worker created/completed counters and a double-scan termination check (Mattern's
four-counter method). Do **not** just swap in a `LongAdder`: its sum is not atomic and can
read zero falsely.

## Adaptive controller signals

The engine provides `SignalSource`. Its snapshot contains:

- queue lengths per worker, read approximately without stopping the workers
- the number of idle workers
- cumulative steal attempts and successes, taken from the counting `MetricsSink` wrapper the
  engine hands to strategies
- completed tasks

The controller computes rates from the difference between two snapshots. No steals happen
in static round-robin, so the decision to leave it must come from imbalance and idleness
alone.

## Resources

Tasks call `ctx.acquire(name)`, which blocks the worker while the task waits; that is a
documented limitation. Before blocking, the resource manager checks the wait-for graph. If
the request would close a cycle, the chosen victim gets a `DeadlockException`. When a task
ends, the engine releases everything it still holds. `tryAcquire` is the non-blocking
alternative.

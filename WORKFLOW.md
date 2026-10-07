# How the scheduler works: workflow diagrams

This file shows, step by step, what happens when the scheduler runs. Each diagram covers one
part of the system, from the whole run down to a single steal. For the design reasoning
behind each part, see [docs/architecture.md](docs/architecture.md); for measured results, see
[README.md](README.md#results).

1. [The big picture](#1-the-big-picture)
2. [One run, start to finish](#2-one-run-start-to-finish)
3. [How tasks travel: the queue layout](#3-how-tasks-travel-the-queue-layout)
4. [The worker loop](#4-the-worker-loop)
5. [Spawning a task](#5-spawning-a-task)
6. [A task's life (task control block)](#6-a-tasks-life-task-control-block)
7. [Work stealing: the handshake](#7-work-stealing-the-handshake)
8. [The adaptive controller](#8-the-adaptive-controller)
9. [Why switching mid-run is safe](#9-why-switching-mid-run-is-safe)
10. [Deadlock detection](#10-deadlock-detection)
11. [Knowing when everything is done](#11-knowing-when-everything-is-done)
12. [The experiment pipeline](#12-the-experiment-pipeline)

---

## 1. The big picture

The modules and how they depend on each other. Everything talks through the interfaces
(contracts) in `sched-common`; `sched-cli` plugs the real implementations together.

```mermaid
flowchart TB
    subgraph top["Top layer: may use everything, nothing depends on it"]
        CLI["sched-cli<br/>runs experiments"]
        INT["sched-integration<br/>end-to-end tests"]
        BENCH["sched-bench<br/>JMH benchmarks"]
        STRESS["sched-stress<br/>jcstress tests"]
    end

    subgraph core["Runtime"]
        ENGINE["sched-engine<br/>workers, TCB table, mode flag,<br/>termination, signals"]
        QUEUE["sched-queue<br/>SpscQueue (lock-free)<br/>WorkerQueueSet"]
    end

    subgraph plug["Pluggable parts: each depends only on sched-common"]
        STRAT["sched-strategies<br/>round-robin, wait-steal,<br/>no-wait-steal"]
        ADAPT["sched-adaptive<br/>controller (our contribution)"]
        RES["sched-resources<br/>deadlock detection"]
        MET["sched-metrics<br/>counters, traces, CSV"]
        WORK["sched-workloads<br/>uniform, Fibonacci,<br/>shifting, philosophers"]
    end

    COMMON[("sched-common<br/>task model + all interfaces")]

    CLI --> ENGINE & STRAT & ADAPT & RES & MET & WORK
    ENGINE --> QUEUE --> COMMON
    ENGINE --> COMMON
    STRAT --> QUEUE
    STRAT & ADAPT & RES & MET & WORK --> COMMON
```

---

## 2. One run, start to finish

What happens between typing the command and getting the CSV files.

```mermaid
flowchart TD
    A(["java -jar scheduler.jar --config shifting-adaptive.json"]) --> B["Read the JSON config:<br/>workers, mode, adaptive?, workload"]
    B --> C["Build the WorkerPool:<br/>N workers, N x N SPSC queues,<br/>all three strategies registered"]
    C --> D{"adaptive: true?"}
    D -- yes --> E["Start the AdaptiveController<br/>monitor thread (samples every 5 ms)"]
    D -- no --> F
    E --> F["Put the root task on worker 0"]
    F --> G["Start N worker threads"]
    G --> H["Root task spawns every initial task<br/>through the active strategy"]
    H --> I["Workers run tasks, tasks spawn children,<br/>strategies balance the load,<br/>controller may switch modes"]
    I --> J{"Outstanding tasks == 0?"}
    J -- no --> I
    J -- yes --> K["Stop the timer, join the workers,<br/>stop the controller"]
    K --> L["Audit the run: completed? any task<br/>run twice? any task failed?"]
    L --> M[("Write runs.csv, workers-N.csv,<br/>trace / adaptive logs")]
    M --> N{"More repeats?"}
    N -- yes --> C
    N -- no --> O(["Print the median, min and max"])
```

Warm-up runs follow the same path but are not recorded.

---

## 3. How tasks travel: the queue layout

Each worker owns one incoming queue **per producer**, so every queue has exactly one writer
and one reader, and no queue ever needs a lock. Shown for 3 workers:

```mermaid
flowchart LR
    W0(("Worker 0"))
    W1(("Worker 1"))
    W2(("Worker 2"))

    subgraph IN1["Worker 1's incoming queues"]
        Q10["from W0"]
        Q11["from W1<br/>(master)"]
        Q12["from W2"]
    end

    W0 -- "only W0 writes here" --> Q10
    W1 -- "own spawns" --> Q11
    W2 -- "only W2 writes here" --> Q12
    Q10 & Q11 & Q12 -- "only W1 reads" --> W1
```

Every worker has the same set. Worker *i* sending a task to worker *j* writes into "*j*'s
queue from *i*". Inside `SpscQueue`, a **release** store when publishing and an **acquire**
load when reading guarantee the reader sees the whole task (see
[docs/memory-model.md](docs/memory-model.md)).

---

## 4. The worker loop

What every worker thread does, over and over, until the run is finished.

```mermaid
flowchart TD
    S(["Worker starts:<br/>strategy.onEnter()"]) --> M{"Did the mode flag change?"}
    M -- yes --> SW["old strategy.onExit()<br/>new strategy.onEnter()"]
    SW --> P
    M -- no --> P["Poll own queues:<br/>master first, then the others in rotation"]
    P --> T{"Got a task?"}
    T -- yes --> V["strategy.onDequeue()<br/>(victim side: answer steal requests)"]
    V --> X["Claim the task (READY to RUNNING, by CAS)<br/>run it, mark FINISHED or FAILED"]
    X --> M
    T -- no --> D{"All work done?"}
    D -- yes --> E(["strategy.onExit()<br/>worker exits"])
    D -- no --> I["strategy.onIdle()<br/>(thief side: try to get work)"]
    I --> G{"Work may have arrived?"}
    G -- yes --> M
    G -- no --> B["Back off:<br/>spin, then yield, then park 20 us"]
    B --> M
```

The mode flag is checked **only between tasks**, so a strategy is never switched while it is
in the middle of one of its own steps.

---

## 5. Spawning a task

What happens when a running task calls `ctx.spawn(child)`.

```mermaid
flowchart TD
    A(["Task calls ctx.spawn(child)"]) --> B["Create a task control block<br/>(state READY)"]
    B --> C["Termination counter + 1<br/>(before the child becomes visible)"]
    C --> D["strategy.onSpawn() picks a target worker"]
    D --> R{"Which strategy?"}
    R -- "round-robin" --> RR["next worker in rotation"]
    R -- "stealing" --> ST["answer any pending steal request,<br/>then keep the child on this worker"]
    RR --> P
    ST --> P
    P{"Push into the target's queue: space?"}
    P -- yes --> Z(["Done: the child waits in that queue"])
    P -- no --> O{"Push into this worker's own queue: space?"}
    O -- yes --> Z
    O -- no --> IN(["Run the child right here, immediately<br/>(as OpenMP runtimes do when queues are full)"])
```

---

## 6. A task's life (task control block)

Every task has a task control block (TCB), like an OS process control block. Every state
change is a compare-and-set. If two workers ever tried to start the same task, the second CAS
would fail and be counted as a bug. Across every test and experiment run, that count is 0.

```mermaid
stateDiagram-v2
    [*] --> READY: created by spawn<br/>(sits in exactly one queue)
    READY --> RUNNING: a worker claims it (CAS)
    RUNNING --> BLOCKED: acquire() must wait for a resource
    BLOCKED --> RUNNING: resource granted
    RUNNING --> FINISHED: run() returns
    RUNNING --> FAILED: run() throws
    BLOCKED --> FAILED: chosen as deadlock victim
    FINISHED --> [*]
    FAILED --> [*]
```

---

## 7. Work stealing: the handshake

An idle worker (the **thief**) never touches a busy worker's (the **victim**'s) queues.
It only *asks*, through one 64-bit word per victim: the steal-request cell, holding a round
number and the thief's id. The victim moves the task itself.

### The normal case

```mermaid
sequenceDiagram
    autonumber
    participant T as Thief (idle worker)
    participant C as Victim's request cell
    participant V as Victim (busy worker)
    participant Q as Queue from victim to thief

    T->>T: pick a victim that looks busy
    T->>C: CAS (round r, empty) to (round r, thief)
    Note over T: wait-based: spin until answered or timeout<br/>no-wait: go back to own queues at once
    V->>C: at its next spawn or dequeue: request pending?
    V->>V: take a task from its own queue
    V->>Q: push the task (release)
    V->>C: CAS (r, thief) to (r+1, empty): answered
    T->>C: sees round r+1 (acquire)
    T->>Q: poll: the stolen task is guaranteed visible
```

### When the thief gives up at the same moment the victim answers

```mermaid
sequenceDiagram
    participant T as Thief
    participant C as Request cell
    participant V as Victim

    T->>C: post request (r, thief)
    V->>V: takes a task, pushes it to the thief's queue
    T->>C: timeout: withdraw, CAS (r, thief) to (r, empty)
    Note over T,C: withdraw succeeds
    V->>C: answer, CAS (r, thief) to (r+1, empty)
    Note over V,C: answer fails, the cell is already empty
    Note over T,V: No harm done: the task is already in the thief's queue,<br/>and every mode drains all of a worker's queues.<br/>A lost race only wastes an attempt. Tasks never pass through the cell.
```

This exact race is tested with jcstress: it happened 5,000–32,000 times per run, and a task was
never lost or duplicated.

---

## 8. The adaptive controller

A separate monitor thread. Every 5 ms it samples the scheduler and decides whether to switch
strategy.

### The sampling loop

```mermaid
flowchart LR
    A(["Every 5 ms"]) --> B["Snapshot: queue lengths, idle workers,<br/>steal attempts and successes, tasks completed"]
    B --> C["Compute signals: imbalance (max / mean queue),<br/>idle ratio, utilization, steal success rate"]
    C --> D["DecisionRule.decide()"]
    D --> E{"Switch?"}
    E -- yes --> F["Flip the mode flag<br/>(workers adopt it between tasks)"]
    E -- no --> G["Log the sample"]
    F --> G
    G --> A
```

### How the decision rule thinks

Signals alone can mislead. In the shifting workload, round-robin looks bad after the shift,
but the paper's steal-1 is even worse there. So every switch is a **trial** that must prove
itself:

```mermaid
stateDiagram-v2
    [*] --> Stable
    Stable --> Pending: signals suggest the other strategy<br/>(workers idle while work is queued,<br/>and the work is concentrated)
    Pending --> Stable: signal disappears<br/>(a short spike: ignored)
    Pending --> Trial: signal held for 3 samples x penalty<br/>(switch the mode)
    Trial --> Kept: utilization over 6 samples<br/>did not drop
    Trial --> Reverted: utilization dropped more than 5%
    Kept --> Stable: penalty reset to 1
    Reverted --> Stable: switch back,<br/>penalty doubles (max 32)
```

- **Hysteresis:** a condition must hold several samples in a row, and there's a minimum time
  between switches, so brief spikes never cause flapping.
- **Exponential backoff:** a strategy that keeps losing is tried less and less often.
- **Utilization** (the fraction of workers busy) is the judge, because it measures useful work
  regardless of task size.

### What it actually did on the shifting workload (8 workers, median run)

```mermaid
flowchart LR
    P1["0-90 ms<br/>balanced phase:<br/>round-robin"] --> S1["111 ms<br/>workload has shifted:<br/>trial no-wait stealing"]
    S1 --> S2["200 ms<br/>probe round-robin"]
    S2 --> S3["231 ms<br/>worse: back to<br/>no-wait stealing"]
    S3 --> S4["311 ms<br/>probe round-robin"]
    S4 --> S5["341 ms<br/>worse: back to<br/>no-wait stealing"]
    S5 --> FIN(["Finished at 379 ms<br/>round-robin alone: 478 ms"])
```

---

## 9. Why switching mid-run is safe

Workers notice a mode change at slightly different moments, so for a short while different
workers run different strategies. The diagram shows why no task can be lost:

```mermaid
flowchart TD
    A["Rule: a strategy never holds a task<br/>across its hook calls"] --> B["A task only ever moves<br/>straight from one queue into another"]
    B --> C["So at every moment, each task is in exactly<br/>one queue, or owned by exactly one worker"]
    D["Rule: every mode drains<br/>all of a worker's queues"] --> E["A task in any queue is always<br/>picked up eventually, whatever the mode"]
    F["Rule: onExit() withdraws this worker's request<br/>and declines requests aimed at it"] --> G["No worker is left waiting<br/>on one that stopped listening"]
    C --> H(["No task is lost or duplicated by a switch"])
    E --> H
    G --> H
```

This is checked by the jcstress `ModeSwitchStress` tests (about 960 million samples, 0
forbidden outcomes) and by integration tests that flip the mode nonstop during runs.

---

## 10. Deadlock detection

Tasks can lock named resources (for example `fork-0`). The resource manager keeps a wait-for
graph and checks it on **every** request that would block.

```mermaid
flowchart TD
    A(["Task T calls acquire(R)"]) --> B{"Is R free?"}
    B -- yes --> C(["Grant R to T"])
    B -- no --> D["Add the edge: T waits for R,<br/>and R is held by task H"]
    D --> E["Follow the chain:<br/>T waits for H, H waits for ...?"]
    E --> F{"Does the chain<br/>lead back to T?"}
    F -- "no: just a waiting chain" --> G["Block until R is released"]
    G --> B
    F -- "yes: a cycle, deadlock" --> H["Remove T's wait edge,<br/>record a DeadlockReport (the cycle)"]
    H --> I(["T gets DeadlockException.<br/>When T ends, its resources are released<br/>and the other tasks proceed"])
```

Why only the requester needs checking: a new cycle must include the new edge, so it always
passes through the task that just asked. In the dining-philosophers demo, 7–11 five-way
deadlocks per run were found this way and broken, and every meal was still eaten.

```mermaid
flowchart LR
    P1(("Philosopher 1")) -- "holds" --> F1["fork-1"]
    P1 -. "waits for" .-> F2["fork-2"]
    P2(("Philosopher 2")) -- "holds" --> F2
    P2 -. "waits for" .-> F3["fork-3"]
    P3(("Philosopher 3")) -- "holds" --> F3
    P3 -. "waits for" .-> F1
```

Above: a three-way cycle. Each philosopher holds one fork and waits for the next one, so
nobody can proceed. The last request that closes the loop is the one the detector rejects.

---

## 11. Knowing when everything is done

Tasks keep creating new tasks, so "all queues are empty" does not mean "finished": a running
task may be about to spawn more. The engine uses one counter instead:

```mermaid
flowchart LR
    A["Task spawned"] -- "+1 before it is enqueued" --> CNT[("Outstanding<br/>task counter")]
    B["Task finished or failed"] -- "-1" --> CNT
    CNT --> Z{"Counter == 0?"}
    Z -- yes --> DONE(["Run complete:<br/>workers exit"])
    Z -- no --> RUN["Keep running"]
```

A child is always counted **before** its parent finishes, so the counter cannot reach zero
while any work exists. The integration tests check exactly this: a 300-step chain where only
one task exists at any moment, and a burst of work that arrives after 50 ms of everyone being
idle.

---

## 12. The experiment pipeline

How the numbers in the README are produced, so anyone can reproduce them.

```mermaid
flowchart TD
    A["scripts/make_sweep.py"] --> B["experiments/tuning/<br/>a different shifting workload,<br/>6 threshold settings"]
    A --> C["experiments/final/<br/>71 configs: 4 strategies x 3 workloads<br/>x 2, 4, 8 workers, + 9 variants, + steal-half"]
    B --> D["run_experiments.sh experiments/tuning"]
    D --> E{"Pick thresholds<br/>and freeze them"}
    E --> C
    C --> F["run_experiments.sh experiments/final<br/>2 warm-up + 10 recorded runs per config"]
    F --> G[("results/final/*/runs.csv<br/>710 runs, each audited for<br/>lost, duplicated or failed tasks")]
    C --> H["run_traces.sh<br/>per-millisecond traces, 8 workers"]
    H --> I[("results/traces/")]
    G --> J["plot_results.py"]
    I --> J
    J --> K[("docs/report/figures/<br/>4 charts + summary.csv")]
    J --> L["Markdown tables<br/>for docs/results.md and README"]
```

Tuning is kept separate from the final runs, so the reported numbers never come from the runs
the thresholds were tuned on.

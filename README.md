# Lockless Adaptive Scheduler

A lock-free task scheduler in Java, built the way an operating system would build one, that
**switches its own scheduling strategy at runtime** depending on how balanced the work is.

It reimplements the three strategies from the X-OpenMP paper on per-worker
single-producer/single-consumer queues:

- static round-robin
- wait-based work stealing
- no-wait work stealing

On top of these, an adaptive controller watches queue imbalance, steal success rate and idle
workers, and moves the whole system between the strategies while it runs.

> **Status:** Phase 0 (scaffold). The contracts in `sched-common` and the `LockingQueue` stub
> exist; everything else is planned. See [docs/architecture.md](docs/architecture.md).

## Build

You need JDK 21+. The Maven wrapper downloads Maven for you.

```sh
./mvnw verify            # compile everything, run all unit + integration tests
./mvnw -pl sched-queue -am test   # just one module (and what it depends on)
```

## Run

```sh
# scheduler (once wired up in Phase 1)
java -jar sched-cli/target/scheduler.jar --config experiments/uniform-static.json

# concurrency stress tests (jcstress). Long-running; not part of `verify`.
java -jar sched-stress/target/jcstress.jar                # everything
java -jar sched-stress/target/jcstress.jar -t LockingQueue # one test

# benchmarks (JMH)
java -jar sched-bench/target/benchmarks.jar QueueBenchmark

# all experiment configs, then charts
scripts/run_experiments.sh
python3 scripts/plot_results.py results/
```

## Modules

| Module | What it is | Owner |
|---|---|---|
| `sched-common` | task model, contracts (interfaces), config | A |
| `sched-queue` | `LockingQueue` stub, `SpscQueue`, per-worker queue sets | A |
| `sched-engine` | workers, TCB table, mode flag, termination, snapshots | A |
| `sched-strategies` | round-robin, wait-based steal, no-wait steal | B |
| `sched-adaptive` | signal collection, decision rule, controller | B |
| `sched-resources` | resource manager, allocation graph, deadlock detection | C |
| `sched-metrics` | counters, traces, distribution delta, CSV | C |
| `sched-workloads` | uniform, Fibonacci, shifting, deadlock scenario | C |
| `sched-integration` | end-to-end correctness tests | C |
| `sched-stress` | jcstress tests | A (queues), B (steal, mode switch) |
| `sched-bench` | JMH benchmarks, ForkJoinPool baseline | C |
| `sched-cli` | entry point | C |

Dependency rules: strategies, adaptive, resources, metrics and workloads depend only on
`sched-common` (strategies also on `sched-queue`). None of them depends on the engine or on
each other. Nothing depends on cli, integration, bench or stress.

## Working agreements

- Branch per change (`feature/spsc-queue`), merge by PR, the module owner reviews.
- Don't edit someone else's module directly; open an issue or a PR for them.
- Any change to a `sched-common` interface needs a team conversation and an entry in
  [docs/contracts-changelog.md](docs/contracts-changelog.md).
- Friday: merge everything and run the full build.

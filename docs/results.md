# Results

Phase 4 final experiments, run on 2026-10-06. All numbers are reproducible with:

```sh
python3 scripts/make_sweep.py                       # writes experiments/tuning and experiments/final
scripts/run_experiments.sh experiments/tuning       # threshold tuning (not reported below)
scripts/run_experiments.sh experiments/final        # the reported matrix
scripts/run_traces.sh                               # traces for the over-time chart
python3 scripts/plot_results.py                     # charts + tables in docs/report/figures
```

## Headline

On a workload whose shape changes mid-run, the adaptive scheduler is **19% faster than the
best fixed strategy at 8 workers** (380 ms against round-robin's 469 ms) and 18% faster at 4
workers. Across all 9 variants of that workload it is 8–22% faster. On workloads that don't
change shape, it matches the best fixed strategy to within 1%. Monitoring cost is not
measurable.

![Run time by strategy, 8 workers](report/figures/main-8-workers.png)

## Method

- **Machine:** one Apple Silicon Mac, 12 cores (8 performance, 4 efficiency), JDK 21
  (Homebrew build), lock-free `SpscQueue`, queue capacity 1024, steal wait 10 µs, seed 42.
- **Runs:** every configuration ran **2 warm-up runs (discarded, to remove JIT warm-up), then
  10 recorded runs**. Reported values are the **median**, with the **interquartile range** in
  brackets.
- **Correctness of every run was checked:** 710 of 710 recorded runs completed with no lost,
  duplicated or failed task.
- **Workload sizes scale with workers:** uniform and shifting have 12,500 tasks per worker.
  Fibonacci uses n = 22, 24 and 25 for 2, 4 and 8 workers. "Ideal" is total work ÷ workers,
  a bound no scheduler can beat.
- **Strategies are the paper's:** round-robin, wait-based steal-1 and no-wait steal-1.
  Steal-half is reported separately below.
- **Tuning was kept separate from the reported runs.** The adaptive thresholds were chosen on
  a *different* shifting workload: 80k tasks, shift at 40%, heavy tasks 8× (`experiments/tuning`).
  They were then frozen before the final runs.

  | Setting (streak, idle ratio) | 2, 0.25 | 2, 0.4 | 3, 0.25 | **3, 0.4** | 5, 0.25 | 5, 0.4 | Round-robin | No-wait |
  |---|---|---|---|---|---|---|---|---|
  | Tuning workload, median ms | 282 | 284 | 299 | **284** | 298 | 309 | 358 | 663 |

  Every setting beat the best fixed strategy, so the result does not hinge on tuning. We
  chose (3, 0.4): it is within 1% of the fastest setting, and the longer streak makes it less
  likely to flip back and forth.
- **Caveat: the machine was not idle.** The median load average during the runs was **5.7**
  (range 5.0–6.7). That includes the experiments' own threads, plus VS Code and Word. Every
  run records its load average in `runs.csv`. Relative comparisons were run interleaved under
  the same conditions; absolute times would be somewhat lower on an idle machine.

## Main matrix

Run time, median ms (IQR); lower is better. **Bold** marks the best fixed strategy. The last
column is adaptive's gain over that best fixed strategy (+ means adaptive is faster).

| Workload | Workers | Ideal | Round-robin | Wait steal-1 | No-wait steal-1 | Adaptive | Adaptive vs best fixed |
|---|---|---|---|---|---|---|---|
| Uniform | 2 | 125 | **129 (129–130)** | 165 (162–172) | 159 (152–165) | 129 (129–130) | +0.0% |
| Uniform | 4 | 125 | **133 (132–134)** | 252 (250–257) | 266 (265–269) | 133 (132–134) | +0.6% |
| Uniform | 8 | 125 | **144 (143–146)** | 484 (475–494) | 499 (482–504) | 144 (143–145) | −0.2% |
| Fibonacci tree | 2 | 143 | **154 (154–154)** | 157 (156–158) | 156 (156–158) | 154 (153–155) | −0.3% |
| Fibonacci tree | 4 | 188 | **214 (213–216)** | 218 (216–219) | 218 (214–219) | 215 (214–215) | −0.5% |
| Fibonacci tree | 8 | 152 | **195 (194–203)** | 196 (195–197) | 198 (196–199) | 196 (195–198) | −0.6% |
| Shifting | 2 | 406 | **456 (456–458)** | 711 (710–714) | 673 (653–682) | **432 (430–440)** | **+5.4%** |
| Shifting | 4 | 266 | **461 (460–461)** | 571 (569–572) | 670 (668–674) | **376 (372–386)** | **+18.3%** |
| Shifting | 8 | 195 | **469 (467–470)** | 771 (760–779) | 842 (834–854) | **380 (372–385)** | **+18.9%** |

![Scaling](report/figures/scaling.png)

## What happens over time

![Progress over time on the shifting workload](report/figures/over-time-shifting.png)

This is the median run of each strategy at 8 workers.
- **Steal-1 is slow from the start.** All tasks are spawned on one worker, and each handshake
  moves a single task.
- **Round-robin is fast in the balanced first half,** then slows once every heavy task lands
  on the same worker.
- **Adaptive follows round-robin through the first half.** After the shift it moves into
  no-wait stealing. The dots mark each switch; it probes back to round-robin twice, finds
  it worse, and returns. It finishes at 379 ms against round-robin's 478 ms.

**Why adaptive beats both.** By the time the controller switches, round-robin has already
spread most tasks across all eight workers' queues, so thieves have many victims rather than
one. Stealing then drains the overloaded worker. Neither fixed strategy does both. This is
the case adaptive switching exists for.

## Sensitivity: how much the result depends on the workload's shape

![Sensitivity](report/figures/sensitivity.png)

Adaptive against the best fixed strategy, 8 workers (+ means adaptive is faster):

| Heavy multiplier | Shift at 30% | Shift at 50% | Shift at 70% |
|---|---|---|---|
| 5× | +12.7% | +7.7% | +7.9% |
| 10× | +21.9% | +18.9% | +16.2% |
| 20× | +22.0% | +18.4% | +17.2% |

The gain is positive in all 9 cells. It grows with imbalance (heavier tasks) and with how
much of the run happens after the shift (earlier shift points).

## Steal granularity (separate finding)

The paper's steal-1 moves one task per handshake. Moving half the victim's queue (steal-half)
changes the picture, at 8 workers:

| Workload | No-wait steal-half | Best paper strategy | Adaptive |
|---|---|---|---|
| Uniform | **137** | 144 | 144 |
| Fibonacci tree | **189** | 195 | 196 |
| Shifting | **210** | 469 | 380 |

Steal-half beats everything, including adaptive, on all three workloads. In X-OpenMP's
design the victim hands over work, and only at its own scheduling points. With steal-1, a
victim can shed at most one task per task it runs, so it can never drain a backlog quickly.
That single limitation is what makes the paper's stealing modes lose here. We kept steal-1
to stay faithful to the paper; see the decision in architecture.md.

## Other measurements (Phase 3, JMH)

- **Monitor overhead** (`OverheadBenchmark`, 8 workers, 50k empty tasks): 512.5 ns per task
  without the controller and 513.8 ns with it. The difference is within the error bars.
- **Java's `ForkJoinPool`** (`ForkJoinBaseline`, 8 threads): uniform 143 ms, Fibonacci
  180 ms, shifting 216 ms. It is faster than all our paper-faithful strategies on shifting.
  Its thieves take tasks directly from a victim's deque with a CAS, which the SPSC design
  rules out.
- **Queues** (`QueueBenchmark`): `SpscQueue` about 389 ops/µs against `LockingQueue` about
  19 ops/µs.

## Honest limits

- **One machine, not idle** (load average about 5.7). Absolute numbers will move on other
  hardware. The relative ordering was stable across every repeat set we ran.
- **The headline gain depends on the shift being one that round-robin handles badly.** On
  workloads that don't change shape, adaptive gives nothing; it just costs nothing either.
- **At 2 workers the gain is small** (+5%). With only 2 workers, an overloaded worker can
  only shed load to one other worker, so there is less to win.
- **Adaptive loses 0.3–0.6% on Fibonacci.** That is within or near the noise, but it is
  consistently on the negative side. It's the price of occasional probes.
- **Adaptive does not reach `ForkJoinPool` or steal-half on shifting.** The controller can
  only choose among the strategies it is given, and the paper's steal-1 is the bottleneck.

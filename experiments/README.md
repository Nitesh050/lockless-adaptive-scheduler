# Experiment configs

One JSON file per run configuration. `scheduler` maps one-to-one onto `SchedulerConfig`, and
`workload` is read by `sched-cli` (its `type` picks the workload class). Fixed seeds are
required so runs are reproducible.

Keep **tuning** configs (used to pick thresholds) in `experiments/tuning/`, separate from
the final configs here. Never report numbers from tuning runs.

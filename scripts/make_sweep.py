#!/usr/bin/env python3
"""Generates the Phase 4 experiment configs.

    scripts/make_sweep.py            # writes experiments/tuning/ and experiments/final/

tuning/  -- a *different* shifting workload (other size, shift point and heavy pattern) used
            only to pick the adaptive thresholds. Never report these numbers.
final/   -- the reported matrix: every mode x worker count x workload, plus shifting-workload
            variations and a queue comparison. Thresholds come from THRESHOLDS below, which are
            frozen after tuning.

Every config gets warm-up runs (discarded) and a fixed seed.
"""
import itertools
import json
import pathlib
import shutil

ROOT = pathlib.Path(__file__).resolve().parent.parent
EXP = ROOT / "experiments"

REPEATS = 10
WARMUP = 2
WORKERS = [2, 4, 8]

# Frozen after tuning on 2026-10-06 (see docs/results.md): consecutiveSamples 3, idleRatioHigh 0.4.
# Do not edit after the final runs start.
THRESHOLDS = {
    "sampleIntervalMillis": 5,
    "consecutiveSamples": 3,
    "imbalanceEnter": 2.0,
    "imbalanceExit": 1.3,
    "stealSuccessLow": 0.2,
    "idleRatioHigh": 0.4,
}

MODES = {
    "static": ("STATIC_ROUND_ROBIN", False),
    "wait": ("WAIT_BASED_STEAL", False),
    "nowait": ("NO_WAIT_STEAL", False),
    "adaptive": ("STATIC_ROUND_ROBIN", True),
}


def uniform(workers):
    # same total work per worker at every worker count: 12.5k x 10 us each
    return {"type": "uniform", "tasks": 12_500 * workers, "taskMicros": 10}


def fibonacci(workers):
    return {"type": "fibonacci", "n": {2: 22, 4: 24, 8: 25}[workers], "nodeMicros": 5}


def shifting(workers, multiplier=10, shift=0.5, tasks_per_worker=12_500):
    # heavyEveryNth = workers, so round-robin sends every heavy task to the same worker
    return {"type": "shifting", "tasks": tasks_per_worker * workers, "taskMicros": 10,
            "shiftAtFraction": shift, "heavyEveryNth": workers, "heavyOffset": 1,
            "heavyMultiplier": multiplier}


def config(name, mode, workers, workload, thresholds=THRESHOLDS, extra=None):
    initial, adaptive = MODES[mode]
    sched = {"workers": workers, "initialMode": initial, "adaptive": adaptive,
             "queueCapacity": 1024, "stealWaitNanos": 10_000, "seed": 42}
    if adaptive:
        sched["thresholds"] = thresholds
    if extra:
        sched.update(extra)
    return {"name": name, "repeats": REPEATS, "warmup": WARMUP, "scheduler": sched, "workload": workload}


def write(directory, cfg):
    directory.mkdir(parents=True, exist_ok=True)
    (directory / f"{cfg['name']}.json").write_text(json.dumps(cfg, indent=2) + "\n")


def main():
    for d in ("tuning", "final"):
        shutil.rmtree(EXP / d, ignore_errors=True)

    # --- tuning: shifting variant not used in the final matrix (more tasks, earlier shift,
    #     different heavy multiplier), 8 workers, a small grid of thresholds
    tune_workload = shifting(8, multiplier=8, shift=0.4, tasks_per_worker=10_000)
    for mode in ("static", "nowait"):
        write(EXP / "tuning", config(f"tune-{mode}", mode, 8, tune_workload))
    for k, idle in itertools.product([2, 3, 5], [0.25, 0.4]):
        t = dict(THRESHOLDS, consecutiveSamples=k, idleRatioHigh=idle)
        write(EXP / "tuning", config(f"tune-adaptive-k{k}-idle{idle}", "adaptive", 8, tune_workload, thresholds=t))

    # --- final: main matrix
    final = EXP / "final"
    for (wname, wfun), workers, mode in itertools.product(
            [("uniform", uniform), ("fibonacci", fibonacci), ("shifting", shifting)], WORKERS, MODES):
        write(final, config(f"{wname}-w{workers}-{mode}", mode, workers, wfun(workers)))

    # --- final: shifting-workload sensitivity at 8 workers
    for mult, shift in itertools.product([5, 10, 20], [0.3, 0.5, 0.7]):
        if (mult, shift) == (10, 0.5):
            continue  # already in the main matrix
        for mode in MODES:
            write(final, config(f"shiftvar-m{mult}-s{int(shift * 100)}-{mode}", mode, 8,
                                shifting(8, multiplier=mult, shift=shift)))

    # --- final: steal-half as a separate finding (not one of the paper's modes)
    for wname, wfun in [("uniform", uniform), ("fibonacci", fibonacci), ("shifting", shifting)]:
        write(final, config(f"{wname}-w8-nowait-half", "nowait", 8, wfun(8), extra={"stealBatch": "half"}))

    n_tune = len(list((EXP / "tuning").glob("*.json")))
    n_final = len(list(final.glob("*.json")))
    print(f"wrote {n_tune} tuning configs and {n_final} final configs")


if __name__ == "__main__":
    main()

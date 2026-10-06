#!/usr/bin/env python3
"""Turns experiment results into the report's charts and summary tables.

    scripts/plot_results.py                       # results/final + results/traces
    scripts/plot_results.py results/final --traces results/traces --out docs/report/figures

Reads   results/final/<config>/runs.csv           (from scripts/run_experiments.sh experiments/final)
        results/traces/<config>/trace-*.csv       (from scripts/run_traces.sh, optional)
        results/traces/<config>/adaptive-*.csv
Writes  <out>/main-8-workers.png, scaling.png, over-time-shifting.png, sensitivity.png
        <out>/summary.csv, and prints Markdown tables for docs/results.md.

Times are medians over the recorded repeats (warm-up runs are excluded by the CLI); error bars
are the interquartile range. Lower is better everywhere.
"""
import argparse
import csv
import math
import pathlib
import re
import statistics
import sys
from collections import defaultdict

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt  # noqa: E402
from matplotlib.colors import LinearSegmentedColormap  # noqa: E402

ROOT = pathlib.Path(__file__).resolve().parent.parent

# ---- palette: validated categorical order (dataviz reference palette, light mode) -------
SURFACE = "#fcfcfb"
INK = "#0b0b0b"
INK_2 = "#52514e"
MUTED = "#898781"
GRID = "#e1e0d9"
AXIS = "#c3c2b7"
# Fixed entity -> colour mapping; never re-assigned by rank or filter.
MODE_COLOR = {
    "adaptive": "#2a78d6",  # slot 1, blue: the contribution
    "static": "#eb6834",    # slot 2, orange
    "wait": "#1baf7a",      # slot 3, aqua
    "nowait": "#eda100",    # slot 4, yellow
}
MODE_LABEL = {
    "static": "Round-robin",
    "wait": "Wait-based steal-1",
    "nowait": "No-wait steal-1",
    "adaptive": "Adaptive",
}
MODES = ["static", "wait", "nowait", "adaptive"]
WORKLOADS = ["uniform", "fibonacci", "shifting"]
WORKLOAD_LABEL = {"uniform": "Uniform", "fibonacci": "Fibonacci tree", "shifting": "Shifting"}

plt.rcParams.update({
    "font.family": ["Helvetica Neue", "Helvetica", "Arial", "DejaVu Sans"],
    "font.size": 10,
    "figure.facecolor": SURFACE,
    "axes.facecolor": SURFACE,
    "savefig.facecolor": SURFACE,
    "axes.edgecolor": AXIS,
    "axes.labelcolor": INK_2,
    "axes.titlecolor": INK,
    "axes.titlesize": 11,
    "axes.titleweight": "bold",
    "axes.spines.top": False,
    "axes.spines.right": False,
    "axes.grid": True,
    "axes.grid.axis": "y",
    "grid.color": GRID,
    "grid.linewidth": 0.6,
    "xtick.color": MUTED,
    "ytick.color": MUTED,
    "xtick.labelcolor": INK_2,
    "ytick.labelcolor": INK_2,
    "legend.frameon": False,
    "legend.labelcolor": INK_2,
    "lines.linewidth": 2,
})


# ---- ideal run times (total work / workers), from the workload definitions ------------
def fib_nodes(k):
    a, b = 0, 1
    for _ in range(k + 1):
        a, b = b, a + b
    return 2 * a - 1


def ideal_ms(workload, workers, mult=10, shift=0.5):
    if workload == "uniform":
        return 12_500 * workers * 10 / workers / 1000
    if workload == "fibonacci":
        n = {2: 22, 4: 24, 8: 25}[workers]
        return fib_nodes(n) * 5 / workers / 1000
    tasks = 12_500 * workers
    start = round(tasks * shift)
    heavy = sum(1 for i in range(start, tasks) if i % workers == 1)
    return ((tasks - heavy) * 10 + heavy * 10 * mult) / workers / 1000


# ---- loading ----------------------------------------------------------------------------
NAME = re.compile(r"^(?P<w>uniform|fibonacci|shifting)-w(?P<n>\d+)-(?P<m>static|wait|nowait|adaptive)(?P<half>-half)?$")
VAR = re.compile(r"^shiftvar-m(?P<mult>\d+)-s(?P<shift>\d+)-(?P<m>static|wait|nowait|adaptive)$")


def quartiles(xs):
    xs = sorted(xs)
    if len(xs) < 4:
        return xs[0], xs[-1]
    q = statistics.quantiles(xs, n=4)
    return q[0], q[2]


def load(results):
    rows = []
    for runs in sorted(pathlib.Path(results).glob("*/runs.csv")):
        name = runs.parent.name
        data = list(csv.DictReader(runs.open()))
        if not data:
            continue
        times = [float(r["elapsed_ms"]) for r in data]
        q1, q3 = quartiles(times)
        rec = {
            "config": name,
            "median_ms": statistics.median(times),
            "q1_ms": q1,
            "q3_ms": q3,
            "min_ms": min(times),
            "max_ms": max(times),
            "repeats": len(times),
            "clean": sum(r["completed"] == "true" and r["failed"] == "0" and r["duplicate_claims"] == "0" for r in data),
            "switches_median": statistics.median(float(r.get("mode_switches") or 0) for r in data),
            "load_median": statistics.median(float(r.get("load_avg") or "nan") for r in data),
        }
        if m := NAME.match(name):
            rec.update(kind="main", workload=m["w"], workers=int(m["n"]), mode=m["m"] + ("-half" if m["half"] else ""))
            rec["ideal_ms"] = ideal_ms(m["w"], int(m["n"]))
        elif m := VAR.match(name):
            mult, shift = int(m["mult"]), int(m["shift"]) / 100
            rec.update(kind="var", workload="shifting", workers=8, mode=m["m"], mult=mult, shift=shift)
            rec["ideal_ms"] = ideal_ms("shifting", 8, mult, shift)
        else:
            continue
        rows.append(rec)
    return rows


def index(rows, kind):
    return {(r["workload"], r["workers"], r["mode"]): r for r in rows if r["kind"] == kind}


# ---- charts -----------------------------------------------------------------------------
def style_axes(ax):
    ax.set_axisbelow(True)
    ax.tick_params(length=0)
    ax.spines["left"].set_visible(False)


def chart_main(main, out):
    fig, axes = plt.subplots(1, 3, figsize=(11, 3.8))
    for ax, wl in zip(axes, WORKLOADS):
        recs = [main.get((wl, 8, m)) for m in MODES]
        if not all(recs):
            ax.set_visible(False)
            continue
        xs = range(len(MODES))
        med = [r["median_ms"] for r in recs]
        err = [[r["median_ms"] - r["q1_ms"] for r in recs], [r["q3_ms"] - r["median_ms"] for r in recs]]
        ax.bar(xs, med, width=0.62, color=[MODE_COLOR[m] for m in MODES], edgecolor=SURFACE, linewidth=2,
               yerr=err, error_kw={"ecolor": INK_2, "elinewidth": 1, "capsize": 3})
        ideal = recs[0]["ideal_ms"]
        ax.axhline(ideal, color=MUTED, linestyle=(0, (4, 3)), linewidth=1)
        top = max(r["q3_ms"] for r in recs)
        for x, r in zip(xs, recs):
            ax.text(x, r["q3_ms"] + top * 0.015, f"{r['median_ms']:.0f}", ha="center", va="bottom", color=INK, fontsize=9)
        ax.set_xticks(list(xs), [MODE_LABEL[m].replace(" steal-1", "\nsteal-1") for m in MODES], fontsize=8)
        ax.set_title(WORKLOAD_LABEL[wl], loc="left", pad=20)
        ax.text(0, 1.02, f"dashed line = ideal ({ideal:.0f} ms)", transform=ax.transAxes, color=MUTED,
                fontsize=8, va="bottom")
        ax.set_ylim(0, max(max(r["q3_ms"] for r in recs), ideal) * 1.15)
        style_axes(ax)
    axes[0].set_ylabel("Run time, ms (median, IQR)")
    fig.suptitle("8 workers: run time by strategy (lower is better)", x=0.01, ha="left", color=INK, fontweight="bold")
    fig.tight_layout()
    fig.savefig(out / "main-8-workers.png", dpi=200)
    plt.close(fig)


def place_end_labels(ax, ends, top):
    """Direct labels at the right end of each line, pushed apart so none overlap."""
    lo, hi = 0.9, top * 1.08
    gap = (hi - lo) * 0.07
    ends = sorted(ends)
    ys = [y for y, _, _ in ends]
    for i in range(1, len(ys)):
        ys[i] = max(ys[i], ys[i - 1] + gap)
    for (y, m, x), ly in zip(ends, ys):
        ax.annotate(MODE_LABEL[m].replace(" steal-1", ""), (x, y), xytext=(x + 0.35, ly), textcoords="data",
                    va="center", fontsize=8, color=INK_2,
                    arrowprops={"arrowstyle": "-", "color": GRID, "linewidth": 0.8} if abs(ly - y) > gap * 0.5 else None)


def chart_scaling(main, out):
    fig, axes = plt.subplots(1, 3, figsize=(11, 3.8), sharey=False)
    workers = [2, 4, 8]
    for ax, wl in zip(axes, WORKLOADS):
        top = 1.0
        ends = []
        for m in MODES:
            pts = [(w, main[(wl, w, m)]["median_ms"] / main[(wl, w, m)]["ideal_ms"])
                   for w in workers if (wl, w, m) in main]
            if not pts:
                continue
            xs, ys = zip(*pts)
            top = max(top, max(ys))
            ax.plot(xs, ys, color=MODE_COLOR[m], marker="o", markersize=7,
                    markeredgecolor=SURFACE, markeredgewidth=1.5, label=MODE_LABEL[m])
            ends.append((ys[-1], m, xs[-1]))
        place_end_labels(ax, ends, top)
        ax.axhline(1.0, color=MUTED, linestyle=(0, (4, 3)), linewidth=1)
        ax.set_xticks(workers)
        ax.set_xlim(1.6, 10.2)
        ax.set_ylim(0.9, top * 1.08)
        ax.set_xlabel("Workers")
        ax.set_title(WORKLOAD_LABEL[wl], loc="left")
        style_axes(ax)
    axes[0].set_ylabel("Run time ÷ ideal (1.0 = perfect)")
    handles, labels = axes[2].get_legend_handles_labels()
    fig.legend(handles, labels, loc="upper right", ncol=4, fontsize=8)
    fig.suptitle("Scaling: how far each strategy is from ideal", x=0.01, ha="left", color=INK, fontweight="bold")
    fig.tight_layout(rect=(0, 0, 1, 0.93))
    fig.savefig(out / "scaling.png", dpi=200)
    plt.close(fig)


def median_trace(traces, cfg):
    """The trace of the repeat whose run time is the median one."""
    d = pathlib.Path(traces) / cfg
    runs = list(csv.DictReader((d / "runs.csv").open()))
    runs.sort(key=lambda r: float(r["elapsed_ms"]))
    rep = runs[len(runs) // 2]["repeat"]
    per = defaultdict(int)
    for r in csv.DictReader((d / f"trace-{rep}.csv").open()):
        per[float(r["time_ms"])] += int(r["finished"])
    total = sum(per.values())
    ts, acc, xs, ys = sorted(per), 0, [0.0], [0.0]
    for t in ts:
        acc += per[t]
        xs.append(t + 1)  # bucket end
        ys.append(100 * acc / total)
    while len(ys) > 2 and ys[-1] == ys[-2]:
        xs.pop()
        ys.pop()
    switches = []
    log = d / f"adaptive-{rep}.csv"
    if log.exists():
        for r in csv.DictReader(log.open()):
            if r["switched"] == "true":
                switches.append((float(r["time_ms"]), r["decided_mode"]))
    return xs, ys, switches


def chart_over_time(traces, out):
    if not (pathlib.Path(traces) / "shifting-w8-adaptive" / "runs.csv").exists():
        print("no traces found; skipping over-time chart (run scripts/run_traces.sh)", file=sys.stderr)
        return
    fig, ax = plt.subplots(figsize=(9, 4.4))
    finish = {}
    for m in MODES:
        xs, ys, switches = median_trace(traces, f"shifting-w8-{m}")
        finish[m] = xs[-1]
        ax.plot(xs, ys, color=MODE_COLOR[m], label=f"{MODE_LABEL[m]}  ({xs[-1]:.0f} ms)",
                zorder=3 if m == "adaptive" else 2)
        if m == "adaptive":
            short = {"STATIC_ROUND_ROBIN": "→ RR", "WAIT_BASED_STEAL": "→ wait", "NO_WAIT_STEAL": "→ no-wait"}
            for k, (t, to) in enumerate(switches):
                y = ys[min(range(len(xs)), key=lambda i: abs(xs[i] - t))]
                ax.plot([t], [y], marker="o", markersize=7, color=MODE_COLOR[m],
                        markeredgecolor=SURFACE, markeredgewidth=1.2, zorder=4)
                # the line rises to the right, so above-left and below-right of a point are both
                # clear; alternate them so labels of switches close in time don't collide
                above = k % 2 == 0
                ax.annotate(short[to], (t, y), xytext=(-7, 5) if above else (7, -6), textcoords="offset points",
                            ha="right" if above else "left", va="bottom" if above else "top",
                            fontsize=7.5, color=INK_2)
            if switches:
                ax.plot([], [], marker="o", markersize=7, color=MODE_COLOR["adaptive"], linestyle="none",
                        markeredgecolor=SURFACE, label="Adaptive switches mode (→ new mode)")
    ax.axhline(50, color=MUTED, linestyle=(0, (4, 3)), linewidth=1)
    ax.text(max(finish.values()) * 1.02, 51, "workload shifts here (50% of tasks)", color=MUTED, fontsize=8,
            va="bottom", ha="right")
    ax.set_xlim(0, max(finish.values()) * 1.04)
    ax.set_ylim(0, 103)
    ax.set_xlabel("Time since start, ms")
    ax.set_ylabel("Tasks completed, %")
    ax.set_title("Shifting workload, 8 workers: progress over time (median run; finish time in legend)", loc="left")
    ax.legend(loc="lower right", fontsize=8)
    style_axes(ax)
    fig.tight_layout()
    fig.savefig(out / "over-time-shifting.png", dpi=200)
    plt.close(fig)


def chart_sensitivity(rows, out):
    var = defaultdict(dict)
    for r in rows:
        if r["kind"] == "var":
            var[(r["mult"], r["shift"])][r["mode"]] = r["median_ms"]
        elif r["kind"] == "main" and r["workload"] == "shifting" and r["workers"] == 8 and r["mode"] in MODES:
            var[(10, 0.5)][r["mode"]] = r["median_ms"]
    mults = sorted({k[0] for k in var})
    shifts = sorted({k[1] for k in var})
    if len(mults) < 2 or len(shifts) < 2:
        return None
    grid = [[math.nan] * len(shifts) for _ in mults]
    for i, mu in enumerate(mults):
        for j, sh in enumerate(shifts):
            cell = var.get((mu, sh), {})
            if all(m in cell for m in MODES):
                best_fixed = min(cell[m] for m in ("static", "wait", "nowait"))
                grid[i][j] = 100 * (best_fixed - cell["adaptive"]) / best_fixed  # + = adaptive faster
    lim = max(5.0, max(abs(v) for row in grid for v in row if not math.isnan(v)))
    cmap = LinearSegmentedColormap.from_list("div", ["#e34948", "#f0efec", "#2a78d6"])
    fig, ax = plt.subplots(figsize=(5.6, 4.0))
    im = ax.imshow(grid, cmap=cmap, vmin=-lim, vmax=lim, origin="lower", aspect="auto")
    for i in range(len(mults)):
        for j in range(len(shifts)):
            v = grid[i][j]
            if not math.isnan(v):
                ax.text(j, i, f"{v:+.0f}%", ha="center", va="center", color=INK, fontsize=10, fontweight="bold")
    ax.set_xticks(range(len(shifts)), [f"{int(s * 100)}%" for s in shifts])
    ax.set_yticks(range(len(mults)), [f"{m}×" for m in mults])
    ax.set_xlabel("Shift point (share of tasks before the change)")
    ax.set_ylabel("Heavy-task multiplier")
    ax.grid(False)
    for s in ax.spines.values():
        s.set_visible(False)
    ax.tick_params(length=0)
    cb = fig.colorbar(im, ax=ax, shrink=0.85)
    cb.set_label("Adaptive vs best fixed strategy\n(+ = adaptive faster)", color=INK_2, fontsize=8)
    cb.outline.set_visible(False)
    cb.ax.tick_params(length=0, labelsize=8, labelcolor=INK_2)
    ax.set_title("Shifting variants, 8 workers", loc="left")
    fig.tight_layout()
    fig.savefig(out / "sensitivity.png", dpi=200)
    plt.close(fig)
    return mults, shifts, grid


# ---- tables -----------------------------------------------------------------------------
def write_summary(rows, out):
    keys = ["config", "kind", "workload", "workers", "mode", "mult", "shift", "median_ms", "q1_ms", "q3_ms",
            "min_ms", "max_ms", "ideal_ms", "repeats", "clean", "switches_median", "load_median"]
    with (out / "summary.csv").open("w", newline="") as f:
        w = csv.DictWriter(f, fieldnames=keys, extrasaction="ignore")
        w.writeheader()
        for r in sorted(rows, key=lambda r: r["config"]):
            w.writerow({k: (f"{v:.3f}" if isinstance(v, float) else v) for k, v in r.items()})


def print_tables(rows, main, sens):
    print("\n### Run time, median ms (IQR) — lower is better; best fixed strategy in bold\n")
    print("| Workload | Workers | Ideal | Round-robin | Wait steal-1 | No-wait steal-1 | Adaptive | Adaptive vs best fixed |")
    print("|---|---|---|---|---|---|---|---|")
    for wl in WORKLOADS:
        for n in (2, 4, 8):
            recs = {m: main.get((wl, n, m)) for m in MODES}
            if not all(recs.values()):
                continue
            best = min(("static", "wait", "nowait"), key=lambda m: recs[m]["median_ms"])
            cells = []
            for m in MODES:
                r = recs[m]
                txt = f"{r['median_ms']:.0f} ({r['q1_ms']:.0f}–{r['q3_ms']:.0f})"
                cells.append(f"**{txt}**" if m == best else txt)
            gain = 100 * (recs[best]["median_ms"] - recs["adaptive"]["median_ms"]) / recs[best]["median_ms"]
            print(f"| {WORKLOAD_LABEL[wl]} | {n} | {recs['static']['ideal_ms']:.0f} | " + " | ".join(cells)
                  + f" | {gain:+.1f}% |")
    half = [r for r in rows if r["kind"] == "main" and r["mode"] == "nowait-half"]
    if half:
        print("\n### Steal-half (separate finding), 8 workers\n")
        print("| Workload | No-wait steal-half | Best fixed (paper modes) | Adaptive |")
        print("|---|---|---|---|")
        for r in sorted(half, key=lambda r: WORKLOADS.index(r["workload"])):
            recs = {m: main.get((r["workload"], 8, m)) for m in MODES}
            if all(recs.values()):
                best = min(recs[m]["median_ms"] for m in ("static", "wait", "nowait"))
                print(f"| {WORKLOAD_LABEL[r['workload']]} | {r['median_ms']:.0f} | {best:.0f} | {recs['adaptive']['median_ms']:.0f} |")
    if sens:
        mults, shifts, grid = sens
        print("\n### Shifting variants (8 workers): adaptive vs best fixed strategy, + = adaptive faster\n")
        print("| Heavy multiplier | " + " | ".join(f"shift at {int(s * 100)}%" for s in shifts) + " |")
        print("|---|" + "---|" * len(shifts))
        for i, mu in enumerate(mults):
            print(f"| {mu}× | " + " | ".join(f"{v:+.1f}%" for v in grid[i]) + " |")
    total = sum(r["repeats"] for r in rows)
    clean = sum(r["clean"] for r in rows)
    loads = [r["load_median"] for r in rows if not math.isnan(r["load_median"])]
    print(f"\n{len(rows)} configurations, {total} recorded runs, {clean} clean "
          f"(no lost, duplicated or failed task); median load average during runs "
          f"{statistics.median(loads):.1f} (range {min(loads):.1f}–{max(loads):.1f}).")


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("results", nargs="?", default=str(ROOT / "results" / "final"))
    p.add_argument("--traces", default=str(ROOT / "results" / "traces"))
    p.add_argument("--out", default=str(ROOT / "docs" / "report" / "figures"))
    a = p.parse_args()
    out = pathlib.Path(a.out)
    out.mkdir(parents=True, exist_ok=True)

    rows = load(a.results)
    if not rows:
        sys.exit(f"no runs.csv files under {a.results}")
    main_idx = index(rows, "main")
    chart_main(main_idx, out)
    chart_scaling(main_idx, out)
    chart_over_time(a.traces, out)
    sens = chart_sensitivity(rows, out)
    write_summary(rows, out)
    print_tables(rows, main_idx, sens)
    print(f"\ncharts and summary.csv written to {out}", file=sys.stderr)


if __name__ == "__main__":
    main()

#!/usr/bin/env bash
# Re-runs the 8-worker shifting configs with --trace (per-millisecond completions and the
# adaptive controller's log) for the over-time chart. Traces add a little overhead, so the
# timing numbers in the report come from results/final, not from these runs.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
JAR="$ROOT/sched-cli/target/scheduler.jar"
OUT="$ROOT/results/traces"
mkdir -p "$OUT"
for mode in static wait nowait adaptive; do
  name="shifting-w8-$mode"
  printf '%-24s ' "$name"
  java -jar "$JAR" --config "$ROOT/experiments/final/$name.json" --repeats 5 --trace --out "$OUT/$name" | tail -1
done

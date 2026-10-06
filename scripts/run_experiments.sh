#!/usr/bin/env bash
# Runs every config in a directory and writes results under results/<dir name>/<config name>/.
#
#   scripts/run_experiments.sh                     # experiments/*.json
#   scripts/run_experiments.sh experiments/final   # the Phase 4 matrix
#   scripts/run_experiments.sh experiments/final --queue locking   # extra CLI options pass through
#
# Run on an idle machine: each run records the load average in runs.csv.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
JAR="$ROOT/sched-cli/target/scheduler.jar"
DIR="${1:-$ROOT/experiments}"
shift || true
OUT="$ROOT/results/$(basename "$DIR")"

if [[ ! -f "$JAR" ]]; then
  echo "building $JAR"
  (cd "$ROOT" && ./mvnw -q -B -DskipTests -pl sched-cli -am package)
fi

echo "load before: $(uptime | sed 's/.*load/load/')"
mkdir -p "$OUT"
failed=0
for config in "$DIR"/*.json; do
  name="$(basename "$config" .json)"
  printf '%-40s ' "$name"
  if java -jar "$JAR" --config "$config" --out "$OUT/$name" "$@" > "$OUT/$name.log" 2>&1; then
    tail -1 "$OUT/$name.log"
  else
    echo "FAILED (see $OUT/$name.log)"
    failed=$((failed + 1))
  fi
done
echo "load after: $(uptime | sed 's/.*load/load/')"
echo "$failed config(s) failed"
exit $(( failed > 0 ))

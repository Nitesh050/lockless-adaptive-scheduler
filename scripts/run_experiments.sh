#!/usr/bin/env bash
# Runs every config in experiments/ (not experiments/tuning/) and writes CSVs and traces to results/.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
JAR="$ROOT/sched-cli/target/scheduler.jar"
OUT="${1:-$ROOT/results}"

if [[ ! -f "$JAR" ]]; then
  echo "building $JAR"
  (cd "$ROOT" && ./mvnw -q -B -DskipTests -pl sched-cli -am package)
fi

mkdir -p "$OUT"
for config in "$ROOT"/experiments/*.json; do
  name="$(basename "$config" .json)"
  echo "== $name"
  java -jar "$JAR" --config "$config" --out "$OUT/$name"
done

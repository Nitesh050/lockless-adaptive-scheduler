#!/usr/bin/env bash
# Starts the web dashboard and opens it in the browser.
#
#   scripts/ui.sh            # http://localhost:8080
#   scripts/ui.sh 9000       # another port
#
# The web app (ui/, Vite + React) is already compiled into the scheduler jar. After changing
# anything in ui/, rebuild it with:  (cd ui && npm install && npm run build)  and then this script.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
PORT="${1:-8080}"

# Java 21 even if it isn't on the PATH (Homebrew installs openjdk@21 keg-only)
for home in "${JAVA_HOME:-}" /opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
            /usr/local/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home; do
  if [[ -n "$home" && -x "$home/bin/java" ]] && "$home/bin/java" -version 2>&1 | grep -q '"21'; then
    export JAVA_HOME="$home"; export PATH="$JAVA_HOME/bin:$PATH"; break
  fi
done
java -version >/dev/null 2>&1 || { echo "Java 21 not found. Install it with: brew install openjdk@21"; exit 1; }

JAR=sched-cli/target/scheduler.jar
if [[ ! -f "$JAR" ]]; then
  echo "Building the scheduler (first run only)..."
  ./mvnw -q -B -ntp install -DskipTests 2>&1 | grep -v -E '^WARNING|^$' || true
fi

# open the browser once the server answers (skipped when NO_OPEN is set, e.g. by `npm start`)
if [[ -z "${NO_OPEN:-}" ]]; then
  ( for _ in $(seq 1 50); do
      if curl -s -o /dev/null "http://localhost:$PORT/"; then open "http://localhost:$PORT/" 2>/dev/null || true; break; fi
      sleep 0.2
    done ) &
fi
exec java -jar "$JAR" --ui --port "$PORT"

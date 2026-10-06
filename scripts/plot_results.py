#!/usr/bin/env python3
"""Turns results/ (CSVs + traces from sched-cli) into the adaptive-vs-fixed charts.

Owner: C. Planned charts:
  1. throughput over time on the shifting workload: static vs steal vs adaptive, with mode
     switches marked (the key result)
  2. total run time per workload and mode: medians with spread over repeats
  3. tasking overhead per task length
  4. task-distribution delta per mode
"""
import sys


def main() -> int:
    results_dir = sys.argv[1] if len(sys.argv) > 1 else "results"
    print(f"not implemented yet; would read {results_dir}/")
    return 0


if __name__ == "__main__":
    sys.exit(main())

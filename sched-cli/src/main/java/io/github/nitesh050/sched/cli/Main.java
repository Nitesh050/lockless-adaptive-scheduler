package io.github.nitesh050.sched.cli;

/**
 * Entry point (owner: C). Will choose workload, strategy, worker count and config file, wire
 * the real implementations together, run, and write CSV/trace output.
 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) {
        System.out.println("""
                usage: java -jar scheduler.jar --config experiments/<name>.json [--out results/]

                Not wired up yet: the engine and strategies land in Phase 1.""");
    }
}

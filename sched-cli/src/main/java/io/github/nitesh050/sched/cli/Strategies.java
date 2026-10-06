package io.github.nitesh050.sched.cli;

import io.github.nitesh050.sched.common.api.SchedulingStrategy;
import io.github.nitesh050.sched.common.config.SchedulerConfig;
import io.github.nitesh050.sched.strategies.NoWaitSteal;
import io.github.nitesh050.sched.strategies.StaticRoundRobin;
import io.github.nitesh050.sched.strategies.StealRequestCell;
import io.github.nitesh050.sched.strategies.WaitBasedSteal;
import java.util.List;

/**
 * Builds one instance of every strategy for a run. The stealing strategies share their request
 * cells and use the same batch policy.
 */
final class Strategies {

    private Strategies() {
    }

    static List<SchedulingStrategy> all(SchedulerConfig config, int stealBatch) {
        StealRequestCell cells = new StealRequestCell(config.workers());
        return List.of(
                new StaticRoundRobin(config.workers()),
                new WaitBasedSteal(cells, config.stealWaitNanos(), stealBatch),
                new NoWaitSteal(cells, config.stealWaitNanos(), stealBatch));
    }
}

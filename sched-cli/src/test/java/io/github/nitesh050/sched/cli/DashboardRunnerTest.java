package io.github.nitesh050.sched.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The dashboard's API payloads: what the web UI relies on. */
class DashboardRunnerTest {

    @Test
    void runReturnsEverythingThePageDraws() throws Exception {
        Map<String, Object> r = DashboardRunner.run("uniform", 2, "adaptive", 0);
        assertEquals(true, r.get("clean"));
        assertEquals(25_000L, r.get("tasks"));
        assertEquals(125.0, (Double) r.get("idealMs"), 1e-9);
        assertEquals(2, ((long[]) r.get("perWorker")).length);
        List<?> progress = (List<?>) r.get("progress");
        assertTrue(progress.size() > 2);
        double[] last = (double[]) progress.get(progress.size() - 1);
        assertEquals(100.0, last[1], 1e-9, "progress must end at 100%");
        assertFalse(((List<?>) r.get("samples")).isEmpty(), "adaptive runs carry controller samples");
    }

    @Test
    void everyStrategyAndWorkloadRuns() throws Exception {
        for (String s : DashboardRunner.STRATEGIES) {
            assertEquals(true, DashboardRunner.run("fibonacci", 2, s, 0).get("clean"), s);
        }
        assertEquals(true, DashboardRunner.run("shifting", 2, "static", 0).get("clean"));
    }

    @Test
    void deadlockDemoReportsCyclesWithPhilosopherSeats() throws Exception {
        Map<String, Object> r = DashboardRunner.deadlock(4, 10, 300);
        assertEquals(40L, r.get("mealsEaten"));
        for (Object o : (List<?>) r.get("cycles")) {
            for (Object seat : (List<?>) ((Map<?, ?>) o).get("philosophers")) {
                int p = ((Number) seat).intValue();
                assertTrue(p >= 0 && p < 4, "seats are 0-based philosopher numbers, got " + p);
            }
        }
    }

    @Test
    void rejectsBadInput() {
        assertThrows(IllegalArgumentException.class, () -> DashboardRunner.run("bogus", 2, "static", 0));
        assertThrows(IllegalArgumentException.class, () -> DashboardRunner.run("uniform", 99, "static", 0));
        assertThrows(IllegalArgumentException.class, () -> DashboardRunner.deadlock(50, 1, 1));
    }
}

package io.github.nitesh050.sched.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.nitesh050.sched.common.config.Mode;
import io.github.nitesh050.sched.common.config.SchedulerConfig;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MainTest {

    /** The configs committed in experiments/ must always parse. */
    @Test
    void committedExperimentConfigsParse() throws Exception {
        Path experiments = Path.of("..", "experiments");
        try (var files = Files.list(experiments)) {
            List<Path> configs = files.filter(p -> p.toString().endsWith(".json")).toList();
            assertTrue(configs.size() >= 2);
            for (Path p : configs) {
                ExperimentConfig exp = ExperimentConfig.load(p);
                SchedulerConfig c = exp.scheduler().toConfig();
                assertTrue(c.workers() > 0, p.toString());
            }
        }
        ExperimentConfig shifting = ExperimentConfig.load(experiments.resolve("shifting-adaptive.json"));
        assertTrue(shifting.scheduler().toConfig().adaptive());
        assertEquals(1.3, shifting.scheduler().toConfig().thresholds().imbalanceExit());
    }

    @Test
    void missingSchedulerFieldsTakeDefaults(@TempDir Path dir) throws Exception {
        Path cfg = dir.resolve("e.json");
        Files.writeString(cfg, """
                {"name": "e", "scheduler": {"workers": 2}, "workload": {"type": "uniform", "tasks": 1, "taskMicros": 0}}
                """);
        ExperimentConfig exp = ExperimentConfig.load(cfg);
        assertEquals(1, exp.repeats());
        assertEquals(Mode.STATIC_ROUND_ROBIN, exp.scheduler().toConfig().initialMode());
    }

    @Test
    void runsAnExperimentEndToEndAndWritesCsv(@TempDir Path dir) throws Exception {
        Path cfg = dir.resolve("tiny.json");
        Files.writeString(cfg, """
                {
                  "name": "tiny",
                  "repeats": 2,
                  "scheduler": {"workers": 3, "initialMode": "STATIC_ROUND_ROBIN", "queueCapacity": 256, "seed": 1},
                  "workload": {"type": "uniform", "tasks": 3000, "taskMicros": 1}
                }
                """);
        Path out = dir.resolve("out");

        assertTrue(Main.run(Main.Args.parse(new String[] {"--config", cfg.toString(), "--out", out.toString()})));

        List<String> runs = Files.readAllLines(out.resolve("runs.csv"));
        assertEquals(3, runs.size(), "header + one row per repeat");
        assertTrue(runs.get(1).startsWith("tiny,1,uniform,STATIC_ROUND_ROBIN,false,3,spsc,true,3000,"), runs.get(1));
        assertEquals(4, Files.readAllLines(out.resolve("workers-1.csv")).size(), "header + 3 workers");
        assertTrue(Files.exists(out.resolve("workers-2.csv")));
    }

    @Test
    void adaptiveRunWritesTheControllerLog(@TempDir Path dir) throws Exception {
        Path cfg = dir.resolve("adaptive.json");
        Files.writeString(cfg, """
                {"name": "a", "repeats": 1,
                 "scheduler": {"workers": 4, "adaptive": true, "queueCapacity": 256,
                               "thresholds": {"sampleIntervalMillis": 1, "consecutiveSamples": 2, "imbalanceEnter": 2.0,
                                              "imbalanceExit": 1.3, "stealSuccessLow": 0.2, "idleRatioHigh": 0.25}},
                 "workload": {"type": "shifting", "tasks": 20000, "taskMicros": 5, "shiftAtFraction": 0.5,
                              "heavyEveryNth": 4, "heavyOffset": 1, "heavyMultiplier": 10}}
                """);
        Path out = dir.resolve("out");
        assertTrue(Main.run(Main.Args.parse(new String[] {"--config", cfg.toString(), "--out", out.toString(), "--trace"})));

        List<String> log = Files.readAllLines(out.resolve("adaptive-1.csv"));
        assertTrue(log.size() > 5, "controller should have sampled several times, got " + log.size());
        assertTrue(log.get(0).startsWith("time_ms,mode,imbalance"));
        assertTrue(Files.exists(out.resolve("trace-1.csv")));
        assertTrue(Files.exists(out.resolve("modes-1.csv")));
    }

    @Test
    void warmUpRunsAreExecutedButNotRecorded(@TempDir Path dir) throws Exception {
        Path cfg = dir.resolve("w.json");
        Files.writeString(cfg, """
                {"name": "w", "repeats": 2, "warmup": 3, "scheduler": {"workers": 2},
                 "workload": {"type": "uniform", "tasks": 200, "taskMicros": 0}}
                """);
        Path out = dir.resolve("out");
        assertTrue(Main.run(Main.Args.parse(new String[] {"--config", cfg.toString(), "--out", out.toString()})));

        List<String> runs = Files.readAllLines(out.resolve("runs.csv"));
        assertEquals(3, runs.size(), "header + the 2 recorded repeats only");
        assertTrue(runs.get(1).startsWith("w,1,"));
        assertTrue(runs.get(0).endsWith(",mode_switches,load_avg"));
        assertTrue(Files.notExists(out.resolve("workers-0.csv")), "warm-ups write no files");
    }

    @Test
    void stealBatchAcceptsHalf(@TempDir Path dir) throws Exception {
        Path cfg = dir.resolve("half.json");
        Files.writeString(cfg, """
                {"name": "h", "scheduler": {"workers": 2, "initialMode": "NO_WAIT_STEAL", "stealBatch": "half"},
                 "workload": {"type": "uniform", "tasks": 500, "taskMicros": 0}}
                """);
        assertTrue(Main.run(Main.Args.parse(new String[] {"--config", cfg.toString(), "--out", dir.resolve("o").toString()})));
    }

    @Test
    void argsRequireConfig() {
        assertThrows(IllegalArgumentException.class, () -> Main.Args.parse(new String[] {}));
        assertThrows(IllegalArgumentException.class, () -> Main.Args.parse(new String[] {"--bogus", "x"}));
    }
}

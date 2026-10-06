package io.github.nitesh050.sched.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.nitesh050.sched.common.config.Mode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MetricsTest {

    @Test
    void countsEventsPerWorker() {
        MetricsRegistry m = new MetricsRegistry(2);
        m.taskStarted(0, 1);
        m.taskFinished(0, 1);
        m.taskFinished(1, 2);
        m.stealAttempted(1, 0);
        m.stealAttempted(1, 0);
        m.stealSucceeded(1, 0);
        m.modeSwitched(Mode.STATIC_ROUND_ROBIN, Mode.NO_WAIT_STEAL);

        MetricsReport r = m.report();
        assertEquals(1, r.workers().get(0).finished());
        assertEquals(1, r.workers().get(1).finished());
        assertEquals(2, r.workers().get(1).stealAttempts(), "attempts count at the thief");
        assertEquals(1, r.workers().get(0).stealSuccesses(), "successes count at the victim");
        assertEquals(0.5, r.stealSuccessRate());
        assertEquals(1, r.modeSwitches());
    }

    @Test
    void stealSuccessRateIsNaNWithoutAttempts() {
        assertTrue(Double.isNaN(new MetricsRegistry(1).report().stealSuccessRate()));
    }

    @Test
    void writesCsvWithEscaping(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("nested/out.csv");
        CsvExporter.write(file, List.of("a", "b"), List.of(List.of(1, "x,y"), List.of("say \"hi\"", 2.5)));
        assertEquals(List.of("a,b", "1,\"x,y\"", "\"say \"\"hi\"\"\",2.5"), Files.readAllLines(file));
    }

    @Test
    void rejectsRowsThatDoNotMatchTheHeader(@TempDir Path dir) {
        assertThrows(IllegalArgumentException.class,
                () -> CsvExporter.write(dir.resolve("x.csv"), List.of("a", "b"), List.of(List.of(1))));
    }

    @Test
    void writesOneRowPerWorker(@TempDir Path dir) throws Exception {
        MetricsRegistry m = new MetricsRegistry(3);
        m.taskFinished(2, 7);
        Path file = dir.resolve("workers.csv");
        CsvExporter.writeWorkers(file, m.report());

        List<String> lines = Files.readAllLines(file);
        assertEquals(4, lines.size());
        assertEquals(String.join(",", CsvExporter.WORKER_HEADER), lines.get(0));
        assertEquals("2,0,0,1,0,0,0", lines.get(3));
    }
}

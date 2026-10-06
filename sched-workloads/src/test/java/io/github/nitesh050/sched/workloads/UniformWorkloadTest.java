package io.github.nitesh050.sched.workloads;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.nitesh050.sched.common.model.Task;
import java.util.List;
import org.junit.jupiter.api.Test;

class UniformWorkloadTest {

    @Test
    void producesTheRequestedNumberOfEqualTasks() {
        UniformWorkload w = new UniformWorkload(1_000, 5_000);
        List<Task> tasks = w.initialTasks();
        assertEquals(1_000, tasks.size());
        assertEquals(1_000, w.expectedTaskCount().orElseThrow());
        for (Task t : tasks) {
            assertEquals(5_000, ((DelayTask) t).nanos());
        }
    }

    @Test
    void delayTaskBusyWaitsAtLeastItsDuration() throws Exception {
        long nanos = 2_000_000;
        long start = System.nanoTime();
        new DelayTask(nanos).run(null);
        assertTrue(System.nanoTime() - start >= nanos);
    }

    @Test
    void rejectsNegativeParameters() {
        assertThrows(IllegalArgumentException.class, () -> new UniformWorkload(-1, 10));
        assertThrows(IllegalArgumentException.class, () -> new DelayTask(-1));
    }
}

package io.github.nitesh050.sched.adaptive;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.nitesh050.sched.common.api.MetricsSink;
import io.github.nitesh050.sched.common.api.ModeController;
import io.github.nitesh050.sched.common.api.SignalSnapshot;
import io.github.nitesh050.sched.common.api.SignalSource;
import io.github.nitesh050.sched.common.config.AdaptiveThresholds;
import io.github.nitesh050.sched.common.config.Mode;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class AdaptiveControllerTest {

    /** A scripted scheduler: the test sets the queue lengths and idle count; the mode is real. */
    private static final class FakeScheduler implements SignalSource, ModeController {
        final AtomicReference<Mode> mode = new AtomicReference<>(Mode.STATIC_ROUND_ROBIN);
        volatile int[] queues = new int[] {100, 100, 100, 100};
        volatile int idle;
        long completed;

        @Override
        public SignalSnapshot snapshot() {
            completed += 10;
            return new SignalSnapshot(System.nanoTime(), mode.get(), queues, idle, 0, 0, completed);
        }

        @Override
        public boolean requestMode(Mode next) {
            return mode.getAndSet(next) != next;
        }
    }

    @Test
    void collectorComputesImbalanceAndDeltas() {
        SignalCollector c = new SignalCollector();
        c.collect(new SignalSnapshot(0, Mode.NO_WAIT_STEAL, new int[] {0, 0, 0, 0}, 0, 10, 5, 100));
        Signals s = c.collect(new SignalSnapshot(1, Mode.NO_WAIT_STEAL, new int[] {40, 0, 0, 0}, 3, 30, 10, 160));
        assertEquals(4.0, s.imbalance(), 1e-9, "max 40 / mean 10");
        assertEquals(40, s.queued());
        assertEquals(0.75, s.idleRatio(), 1e-9);
        assertEquals(20, s.stealAttempts());
        assertEquals(5, s.stealSuccesses());
        assertEquals(0.25, s.stealSuccessRate(), 1e-9);
        assertEquals(60, s.completed());
    }

    @Test
    void emptyQueuesCountAsBalanced() {
        Signals s = new SignalCollector().collect(
                new SignalSnapshot(0, Mode.STATIC_ROUND_ROBIN, new int[] {0, 0}, 2, 0, 0, 0));
        assertEquals(1.0, s.imbalance());
    }

    @Test
    void switchesTheFlagAndReportsItWhenTheRuleSaysSo() throws Exception {
        FakeScheduler sched = new FakeScheduler();
        List<String> reported = new ArrayList<>();
        MetricsSink sink = new MetricsSink() {
            @Override
            public void modeSwitched(Mode from, Mode to) {
                reported.add(from + "->" + to);
            }
        };
        AdaptiveThresholds t = AdaptiveThresholds.defaults();
        try (AdaptiveController c = new AdaptiveController(sched, sched, t, sink)) {
            // drive it by hand: balanced first
            for (int i = 0; i < 10; i++) {
                c.step();
            }
            assertEquals(Mode.STATIC_ROUND_ROBIN, sched.mode.get());

            // then one worker holds everything and the rest idle
            sched.queues = new int[] {400, 0, 0, 0};
            sched.idle = 3;
            for (int i = 0; i < t.consecutiveSamples(); i++) {
                c.step();
            }
            assertEquals(Mode.NO_WAIT_STEAL, sched.mode.get(), "imbalance 4.0 >= 2 x 2.0 -> no-wait");
            assertEquals(1, c.switches());
            assertEquals(List.of("STATIC_ROUND_ROBIN->NO_WAIT_STEAL"), reported);
            assertTrue(c.log().size() >= 10 + t.consecutiveSamples());
        }
    }

    @Test
    void threadRunsAndStopsCleanly() throws Exception {
        FakeScheduler sched = new FakeScheduler();
        AdaptiveThresholds t = new AdaptiveThresholds(1, 3, 2.0, 1.3, 0.2, 0.25);
        AdaptiveController c = new AdaptiveController(sched, sched, t, MetricsSink.NOOP);
        c.start();
        Thread.sleep(50);
        c.close();
        int samples = c.log().size();
        assertTrue(samples >= 10, "expected ~50 samples at 1 ms, got " + samples);
        Thread.sleep(20);
        assertEquals(samples, c.log().size(), "no samples after close()");
    }
}

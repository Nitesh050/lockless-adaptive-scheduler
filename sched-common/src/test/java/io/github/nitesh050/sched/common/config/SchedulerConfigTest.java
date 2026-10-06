package io.github.nitesh050.sched.common.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class SchedulerConfigTest {

    @Test
    void builderRoundTrips() {
        SchedulerConfig c = SchedulerConfig.builder()
                .workers(4)
                .initialMode(Mode.NO_WAIT_STEAL)
                .adaptive(true)
                .seed(7)
                .build();
        assertEquals(c, c.toBuilder().build());
        assertEquals(4, c.workers());
        assertEquals(Mode.NO_WAIT_STEAL, c.initialMode());
    }

    @Test
    void rejectsNonPowerOfTwoCapacity() {
        assertThrows(IllegalArgumentException.class,
                () -> SchedulerConfig.builder().queueCapacity(1000).build());
    }

    @Test
    void rejectsZeroWorkers() {
        assertThrows(IllegalArgumentException.class,
                () -> SchedulerConfig.builder().workers(0).build());
    }

    @Test
    void thresholdsRequireHysteresisGap() {
        assertThrows(IllegalArgumentException.class,
                () -> new AdaptiveThresholds(5, 3, 1.5, 1.5, 0.2, 0.25));
    }
}

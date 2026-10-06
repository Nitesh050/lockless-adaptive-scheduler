package io.github.nitesh050.sched.adaptive;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.nitesh050.sched.common.config.AdaptiveThresholds;
import io.github.nitesh050.sched.common.config.Mode;
import org.junit.jupiter.api.Test;

class DecisionRuleTest {

    /** 5 ms samples, 3 in a row to trigger, imbalance 2.0 / 1.3, steal success 0.2, idle 0.25. */
    private static final AdaptiveThresholds T = AdaptiveThresholds.defaults();
    private static final int K = T.consecutiveSamples();
    private static final int TRIAL = K * DecisionRule.TRIAL_FACTOR;

    private static Signals balanced(Mode mode) {
        return new Signals(0, mode, 8, 1.1, 800, 0.0, 0, 0, 1000);
    }

    /** Workers idle while queued work sits on a few of them. */
    private static Signals starvingImbalanced(Mode mode, double imbalance) {
        return new Signals(0, mode, 8, imbalance, 800, 0.75, 0, 0, 200);
    }

    private static Signals withUtilization(Mode mode, double utilization) {
        return new Signals(0, mode, 8, 1.1, 800, 1.0 - utilization, 0, 0, 500);
    }

    /** Feeds {@code s} n times; returns the last decision. */
    private static DecisionRule.Decision feed(DecisionRule r, Signals s, int n) {
        DecisionRule.Decision d = null;
        for (int i = 0; i < n; i++) {
            d = r.decide(s);
        }
        return d;
    }

    @Test
    void balancedRoundRobinStays() {
        DecisionRule r = new DecisionRule(T);
        DecisionRule.Decision d = feed(r, balanced(Mode.STATIC_ROUND_ROBIN), 50);
        assertFalse(d.switched());
        assertEquals(Mode.STATIC_ROUND_ROBIN, d.mode());
    }

    @Test
    void aSpikeShorterThanTheStreakDoesNotSwitch() {
        DecisionRule r = new DecisionRule(T);
        feed(r, balanced(Mode.STATIC_ROUND_ROBIN), 10);
        DecisionRule.Decision d = feed(r, starvingImbalanced(Mode.STATIC_ROUND_ROBIN, 3.0), K - 1);
        assertFalse(d.switched());
        d = r.decide(balanced(Mode.STATIC_ROUND_ROBIN));
        assertFalse(d.switched(), "streak broken by a normal sample");
        d = feed(r, starvingImbalanced(Mode.STATIC_ROUND_ROBIN, 3.0), K - 1);
        assertFalse(d.switched(), "streak must restart from zero");
    }

    @Test
    void sustainedStarvationTriesStealing() {
        DecisionRule r = new DecisionRule(T);
        DecisionRule.Decision d = feed(r, starvingImbalanced(Mode.STATIC_ROUND_ROBIN, 3.0), K);
        assertTrue(d.switched());
        assertEquals(Mode.WAIT_BASED_STEAL, d.mode(), "moderate imbalance -> wait-based");
        assertTrue(r.inTrial());
    }

    @Test
    void extremeImbalanceTriesNoWaitStealing() {
        DecisionRule r = new DecisionRule(T);
        DecisionRule.Decision d = feed(r, starvingImbalanced(Mode.STATIC_ROUND_ROBIN, 7.0), K);
        assertEquals(Mode.NO_WAIT_STEAL, d.mode());
    }

    @Test
    void stealingThatStarvesWorkersTriesRoundRobin() {
        DecisionRule r = new DecisionRule(T);
        DecisionRule.Decision d = feed(r, starvingImbalanced(Mode.NO_WAIT_STEAL, 8.0), K);
        assertTrue(d.switched());
        assertEquals(Mode.STATIC_ROUND_ROBIN, d.mode());
    }

    @Test
    void balancedLoadWithFailingStealsTriesRoundRobin() {
        DecisionRule r = new DecisionRule(T);
        Signals wasted = new Signals(0, Mode.WAIT_BASED_STEAL, 8, 1.1, 800, 0.0, 100, 5, 1000);
        DecisionRule.Decision d = feed(r, wasted, K);
        assertTrue(d.switched());
        assertEquals(Mode.STATIC_ROUND_ROBIN, d.mode());
    }

    @Test
    void aTrialThatLowersUtilizationIsRevertedAndBacksOff() {
        DecisionRule r = new DecisionRule(T);
        // baseline: round-robin at 40% utilization, starving and imbalanced
        Signals rr = new Signals(0, Mode.STATIC_ROUND_ROBIN, 8, 3.0, 800, 0.6, 0, 0, 100);
        DecisionRule.Decision d = feed(r, rr, K);
        assertTrue(d.switched());
        Mode trial = d.mode();

        // the trial mode only reaches 20%
        d = feed(r, withUtilization(trial, 0.2), TRIAL);
        assertTrue(d.switched());
        assertEquals(Mode.STATIC_ROUND_ROBIN, d.mode(), "reverted");
        assertEquals(2, r.penalty());
        assertFalse(r.inTrial());

        // the same trigger now needs twice as many samples
        d = feed(r, rr, 2 * K - 1);
        assertFalse(d.switched());
        d = r.decide(rr);
        assertTrue(d.switched(), "probes again after 2K samples");
    }

    @Test
    void aTrialThatHoldsUtilizationIsKept() {
        DecisionRule r = new DecisionRule(T);
        Signals rr = new Signals(0, Mode.STATIC_ROUND_ROBIN, 8, 3.0, 800, 0.6, 0, 0, 100);
        Mode trial = feed(r, rr, K).mode();

        DecisionRule.Decision d = feed(r, withUtilization(trial, 0.9), TRIAL);
        assertFalse(d.switched());
        assertEquals(trial, d.mode());
        assertEquals(1, r.penalty());
        assertFalse(r.inTrial());
    }

    @Test
    void penaltyIsCapped() {
        DecisionRule r = new DecisionRule(T);
        Signals rr = new Signals(0, Mode.STATIC_ROUND_ROBIN, 8, 3.0, 800, 0.6, 0, 0, 100);
        for (int round = 0; round < 10; round++) {
            DecisionRule.Decision d;
            do {
                d = r.decide(rr);
            } while (!d.switched());
            feed(r, withUtilization(d.mode(), 0.0), TRIAL);
        }
        assertEquals(DecisionRule.MAX_PENALTY, r.penalty());
    }

    @Test
    void noSwitchRightAfterASwitch() {
        DecisionRule r = new DecisionRule(T);
        Mode trial = feed(r, starvingImbalanced(Mode.STATIC_ROUND_ROBIN, 3.0), K).mode();
        feed(r, withUtilization(trial, 0.9), TRIAL); // kept
        // immediately starving in the new mode: must still wait out the minimum dwell
        DecisionRule.Decision d = r.decide(starvingImbalanced(trial, 8.0));
        assertFalse(d.switched());
    }
}

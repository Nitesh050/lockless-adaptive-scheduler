package io.github.nitesh050.sched.adaptive;

import io.github.nitesh050.sched.common.config.AdaptiveThresholds;
import io.github.nitesh050.sched.common.config.Mode;
import java.util.ArrayDeque;
import java.util.Objects;

/**
 * Decides, one sample at a time, which mode the scheduler should be in.
 *
 * <p><b>1. Trigger.</b> Signals suggest the other family of strategy:
 * <ul>
 *   <li>in round-robin: workers are starving (idle while work is queued) and the queued work
 *       is concentrated ({@code imbalance >= imbalanceEnter}), so try stealing: no-wait if the
 *       imbalance is extreme ({@code >= 2 * imbalanceEnter}), wait-based otherwise;</li>
 *   <li>in a stealing mode: workers are starving although work is queued (thieves are not
 *       getting it fast enough), or the load is balanced ({@code imbalance <= imbalanceExit})
 *       and steals mostly fail ({@code success rate <= stealSuccessLow}), so try round-robin.</li>
 * </ul>
 * The trigger must hold for {@code consecutiveSamples x penalty} samples in a row, and at
 * least {@code consecutiveSamples} samples must have passed since the last switch.
 *
 * <p><b>2. Trial.</b> Signals only say a mode is struggling, not that the other one will do
 * better (measured: in round-robin's bad phase, steal-1 is worse still). So a switch is a
 * trial: utilization (fraction of workers busy) over the next {@code TRIAL_FACTOR x
 * consecutiveSamples} samples is compared with the same window before the switch.
 *
 * <p><b>3. Verdict.</b> If utilization fell by more than {@link #REVERT_MARGIN}, switch back
 * and double the penalty (up to {@link #MAX_PENALTY}), so a mode that keeps losing is probed
 * exponentially less often. Otherwise keep it and reset the penalty.
 *
 * <p>Single-threaded: owned by the controller's monitor thread.
 */
public final class DecisionRule {

    /** Trial length, in multiples of {@code consecutiveSamples}. */
    static final int TRIAL_FACTOR = 2;
    /** A trial must not lose more than this fraction of utilization, or it is reverted. */
    static final double REVERT_MARGIN = 0.05;
    static final int MAX_PENALTY = 32;

    /** What the rule decided for one sample, and why. */
    public record Decision(Mode mode, boolean switched, String reason) {
    }

    private final AdaptiveThresholds t;
    private final int window;
    private final ArrayDeque<Double> recentUtilization = new ArrayDeque<>();

    private Mode candidate;
    private int streak;
    private int penalty = 1;
    private int sinceSwitch = Integer.MAX_VALUE / 2;

    // trial state
    private Mode trialFrom;
    private double trialBaseline;
    private double trialSum;
    private int trialSamples;

    public DecisionRule(AdaptiveThresholds thresholds) {
        this.t = Objects.requireNonNull(thresholds, "thresholds");
        this.window = thresholds.consecutiveSamples() * TRIAL_FACTOR;
    }

    /** Current probe penalty (1 = normal; doubles after each failed trial). */
    public int penalty() {
        return penalty;
    }

    public boolean inTrial() {
        return trialFrom != null;
    }

    public Decision decide(Signals s) {
        Mode current = s.mode();
        sinceSwitch++;

        if (trialFrom != null) {
            return continueTrial(s, current);
        }
        remember(s.utilization());

        Mode wanted = wantedMode(s, current);
        if (wanted == current) {
            candidate = null;
            streak = 0;
            return new Decision(current, false, "stay");
        }
        if (wanted == candidate) {
            streak++;
        } else {
            candidate = wanted;
            streak = 1;
        }
        int required = t.consecutiveSamples() * penalty;
        if (streak < required || sinceSwitch < t.consecutiveSamples()) {
            return new Decision(current, false, "pending " + wanted + " " + streak + "/" + required);
        }

        // start a trial of the wanted mode
        trialFrom = current;
        trialBaseline = averageUtilization();
        trialSum = 0;
        trialSamples = 0;
        candidate = null;
        streak = 0;
        sinceSwitch = 0;
        return new Decision(wanted, true, String.format(java.util.Locale.ROOT,
                "trial %s (baseline utilization %.2f, penalty %d)", wanted, trialBaseline, penalty));
    }

    private Decision continueTrial(Signals s, Mode current) {
        trialSum += s.utilization();
        trialSamples++;
        if (trialSamples < window) {
            return new Decision(current, false, "trial " + trialSamples + "/" + window);
        }
        double trialUtilization = trialSum / trialSamples;
        Mode from = trialFrom;
        trialFrom = null;
        recentUtilization.clear();
        sinceSwitch = 0;
        if (trialUtilization < trialBaseline * (1 - REVERT_MARGIN)) {
            penalty = Math.min(MAX_PENALTY, penalty * 2);
            return new Decision(from, true, String.format(java.util.Locale.ROOT,
                    "revert to %s: utilization %.2f < %.2f (penalty now %d)",
                    from, trialUtilization, trialBaseline, penalty));
        }
        penalty = 1;
        return new Decision(current, false, String.format(java.util.Locale.ROOT,
                "keep %s: utilization %.2f vs %.2f", current, trialUtilization, trialBaseline));
    }

    private Mode wantedMode(Signals s, Mode current) {
        boolean starving = s.starving(t.idleRatioHigh());
        if (current == Mode.STATIC_ROUND_ROBIN) {
            if (starving && s.imbalance() >= t.imbalanceEnter()) {
                return s.imbalance() >= 2 * t.imbalanceEnter() ? Mode.NO_WAIT_STEAL : Mode.WAIT_BASED_STEAL;
            }
            return current;
        }
        double rate = s.stealSuccessRate();
        boolean stealsWasted = s.imbalance() <= t.imbalanceExit() && !Double.isNaN(rate) && rate <= t.stealSuccessLow();
        return starving || stealsWasted ? Mode.STATIC_ROUND_ROBIN : current;
    }

    private void remember(double utilization) {
        recentUtilization.addLast(utilization);
        while (recentUtilization.size() > window) {
            recentUtilization.removeFirst();
        }
    }

    private double averageUtilization() {
        if (recentUtilization.isEmpty()) {
            return 0;
        }
        double sum = 0;
        for (double u : recentUtilization) {
            sum += u;
        }
        return sum / recentUtilization.size();
    }
}

package io.akka.redash.domain;

import java.time.Duration;
import java.time.Instant;

/**
 * Whether reaching a state tells anybody (SPEC-001 R20, R21).
 *
 * <p>The answer is not a function of the new state alone: an alert carries its previous
 * state and the instant it last changed, and the same evaluation answers differently
 * depending on both. A `triggered` result on an alert that was already triggered is
 * silence, unless the rearm window has passed.
 */
public final class AlertNotification {

  /**
   * @param state where the alert stands
   * @param lastTriggeredAt when the state last changed - stamped on every change, not only
   *     on the ones that trigger, and it is what the rearm window is measured from
   * @param rearmSeconds seconds before a still-triggered alert says so again; zero and
   *     absent both mean "never repeat", because zero is falsy where the original tests it
   * @param muted whether the message is suppressed - the state change is not
   */
  public record AlertState(
      AlertCondition.Verdict state, Instant lastTriggeredAt, Integer rearmSeconds, boolean muted) {}

  /** What one evaluation does to an alert: the state it now carries, and who hears about it. */
  public record Outcome(AlertCondition.Verdict state, Instant lastTriggeredAt, boolean stateChanged, boolean notified) {}

  private AlertNotification() {}

  public static boolean shouldNotify(AlertState alert, AlertCondition.Verdict newState, Instant now) {
    if (newState != alert.state()) {
      return true;
    }
    if (alert.state() != AlertCondition.Verdict.TRIGGERED) {
      return false;
    }
    // Zero is not "repeat immediately" - it reads as absent, so the alert never repeats.
    if (alert.rearmSeconds() == null || alert.rearmSeconds() == 0 || alert.lastTriggeredAt() == null) {
      return false;
    }
    return alert.lastTriggeredAt().plus(Duration.ofSeconds(alert.rearmSeconds())).isBefore(now);
  }

  /**
   * Apply one evaluation. The state and the stamp are decided before the notification is,
   * so a suppressed message never suppresses the state change with it: an alert whose
   * first evaluation is `ok` really is `ok` afterwards, and a muted alert that triggers
   * really is triggered.
   */
  public static Outcome apply(AlertState alert, AlertCondition.Verdict evaluated, Instant now) {
    if (evaluated == AlertCondition.Verdict.UNEVALUATED) {
      // The comparison could not be made. Nothing moves and nobody is told.
      return new Outcome(alert.state(), alert.lastTriggeredAt(), false, false);
    }

    boolean notify = shouldNotify(alert, evaluated, now);
    if (!notify) {
      return new Outcome(evaluated, alert.lastTriggeredAt(), evaluated != alert.state(), false);
    }

    boolean firstEvaluationSettling =
        alert.state() == AlertCondition.Verdict.UNKNOWN && evaluated == AlertCondition.Verdict.OK;
    boolean tellAnyone = !firstEvaluationSettling && !alert.muted();

    return new Outcome(evaluated, now, evaluated != alert.state(), tellAnyone);
  }
}

package io.akka.redash.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import io.akka.redash.domain.AlertCondition.Verdict;
import io.akka.redash.domain.AlertNotification.AlertState;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * SPEC-001 R20 and R21, against both tables the original produced.
 *
 * <p>`alert-notify-from-redash.txt` is the notification decision over every combination of
 * prior state, new state and rearm shape — 54 rows. `alert-check-from-redash.txt` is what
 * the whole check does to the alert afterwards over prior state × the state the result
 * reaches × muted — 18 rows. The second is the one that says the suppressions suppress the
 * message and not the state change, and no amount of the first would have shown it.
 */
class AlertNotificationTest {

  private static final Instant FROZEN = Instant.parse("2026-08-20T12:00:00Z");

  private static List<String[]> table(String resource) throws IOException {
    var rows = new ArrayList<String[]>();
    try (InputStream in = AlertNotificationTest.class.getClassLoader().getResourceAsStream(resource)) {
      for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
        line = line.trim();
        if (!line.isEmpty() && !line.startsWith("#")) {
          rows.add(line.split("\\|", -1));
        }
      }
    }
    return rows;
  }

  private static Verdict verdict(String name) {
    return switch (name) {
      case "triggered" -> Verdict.TRIGGERED;
      case "ok" -> Verdict.OK;
      case "unknown" -> Verdict.UNKNOWN;
      default -> throw new IllegalArgumentException(name);
    };
  }

  /** The six shapes `rearm` and `last_triggered_at` can be in, as the original named them. */
  private static AlertState alertIn(Verdict prior, String rearmCase) {
    return switch (rearmCase) {
      case "no-rearm" -> new AlertState(prior, null, null, false);
      case "rearm/never-triggered" -> new AlertState(prior, null, 3600, false);
      case "rearm/inside-window" -> new AlertState(prior, FROZEN.minusSeconds(60), 3600, false);
      case "rearm/on-the-boundary" -> new AlertState(prior, FROZEN.minusSeconds(3600), 3600, false);
      case "rearm/outside-window" -> new AlertState(prior, FROZEN.minusSeconds(3601), 3600, false);
      case "rearm=0/triggered-long-ago" -> new AlertState(prior, FROZEN.minusSeconds(3600), 0, false);
      default -> throw new IllegalArgumentException(rearmCase);
    };
  }

  @Test
  void everyNotificationDecisionTheOriginalMade() throws IOException {
    var rows = table("alert-notify-from-redash.txt");
    assertEquals(54, rows.size(), "the exported table changed size");

    var disagreements = new ArrayList<String>();
    for (String[] row : rows) {
      var alert = alertIn(verdict(row[0]), row[2]);
      boolean want = Boolean.parseBoolean(row[3]);
      boolean got = AlertNotification.shouldNotify(alert, verdict(row[1]), FROZEN);
      if (got != want) {
        disagreements.add(
            "prior=%s new=%s %s: redash said %s, this said %s".formatted(row[0], row[1], row[2], want, got));
      }
    }
    if (!disagreements.isEmpty()) {
      fail(disagreements.size() + " row(s) disagree:\n" + String.join("\n", disagreements));
    }
  }

  @Test
  void whatTheWholeCheckDoesToTheAlertAfterwards() throws IOException {
    var rows = table("alert-check-from-redash.txt");
    assertEquals(18, rows.size(), "the exported table changed size");

    var disagreements = new ArrayList<String>();
    for (String[] row : rows) {
      var prior = verdict(row[0]);
      var reaches = verdict(row[1]);
      boolean muted = Boolean.parseBoolean(row[2]);
      var wantState = verdict(row[3]);
      boolean wantStamped = Boolean.parseBoolean(row[4]);
      int wantNotified = Integer.parseInt(row[5]);

      // The original's alerts start with no stamp, so a stamp afterwards means this
      // evaluation set one.
      var alert = new AlertState(prior, null, null, muted);
      var outcome = AlertNotification.apply(alert, reaches, FROZEN);

      if (outcome.state() != wantState) {
        disagreements.add(
            "prior=%s reaches=%s muted=%s: state %s, redash %s"
                .formatted(row[0], row[1], muted, outcome.state(), wantState));
      }
      boolean stamped = outcome.lastTriggeredAt() != null;
      if (stamped != wantStamped) {
        disagreements.add(
            "prior=%s reaches=%s muted=%s: stamped %s, redash %s"
                .formatted(row[0], row[1], muted, stamped, wantStamped));
      }
      int notified = outcome.notified() ? 1 : 0;
      if (notified != wantNotified) {
        disagreements.add(
            "prior=%s reaches=%s muted=%s: notified %d, redash %d"
                .formatted(row[0], row[1], muted, notified, wantNotified));
      }
    }
    if (!disagreements.isEmpty()) {
      fail(disagreements.size() + " disagreement(s):\n" + String.join("\n", disagreements));
    }
  }

  // The rows above are exhaustive; the ones below name what is easiest to get backwards.

  @Test
  void aRearmOfZeroNeverRepeats() {
    var alert = new AlertState(Verdict.TRIGGERED, FROZEN.minus(Duration.ofDays(30)), 0, false);
    assertFalse(AlertNotification.shouldNotify(alert, Verdict.TRIGGERED, FROZEN));
  }

  @Test
  void theRearmWindowIsStrictlyPast() {
    var onTheBoundary = new AlertState(Verdict.TRIGGERED, FROZEN.minusSeconds(3600), 3600, false);
    var justPast = new AlertState(Verdict.TRIGGERED, FROZEN.minusSeconds(3601), 3600, false);
    assertFalse(AlertNotification.shouldNotify(onTheBoundary, Verdict.TRIGGERED, FROZEN));
    assertTrue(AlertNotification.shouldNotify(justPast, Verdict.TRIGGERED, FROZEN));
  }

  @Test
  void aChangeOfStateNotifiesWhateverRearmSays() {
    for (String rearmCase :
        List.of("no-rearm", "rearm/never-triggered", "rearm/inside-window", "rearm/on-the-boundary")) {
      assertTrue(
          AlertNotification.shouldNotify(alertIn(Verdict.OK, rearmCase), Verdict.TRIGGERED, FROZEN),
          rearmCase);
    }
  }

  @Test
  void aMutedAlertStillChangesStateAndStillStamps() {
    var alert = new AlertState(Verdict.OK, null, null, true);
    var outcome = AlertNotification.apply(alert, Verdict.TRIGGERED, FROZEN);
    assertEquals(Verdict.TRIGGERED, outcome.state());
    assertEquals(FROZEN, outcome.lastTriggeredAt());
    assertFalse(outcome.notified());
  }

  @Test
  void aFirstEvaluationSettlingToOkIsSilentButReal() {
    var alert = new AlertState(Verdict.UNKNOWN, null, null, false);
    var outcome = AlertNotification.apply(alert, Verdict.OK, FROZEN);
    assertEquals(Verdict.OK, outcome.state());
    assertEquals(FROZEN, outcome.lastTriggeredAt());
    assertFalse(outcome.notified());
  }

  @Test
  void aComparisonThatCouldNotBeMadeLeavesTheAlertExactlyAsItWas() {
    var stamp = FROZEN.minusSeconds(500);
    var alert = new AlertState(Verdict.TRIGGERED, stamp, 3600, false);
    var outcome = AlertNotification.apply(alert, Verdict.UNEVALUATED, FROZEN);
    assertEquals(Verdict.TRIGGERED, outcome.state());
    assertEquals(stamp, outcome.lastTriggeredAt());
    assertFalse(outcome.stateChanged());
    assertFalse(outcome.notified());
  }
}

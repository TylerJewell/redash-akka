package io.akka.redash.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import akka.javasdk.testkit.EventSourcedTestKit;
import io.akka.redash.domain.StoredResult;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * SPEC-001 R21 and R22 as the entity carries them out — the domain tests cover the
 * decisions, and these cover what the entity does with them across calls.
 */
class AlertEntityTest {

  private static final Instant NOW = Instant.parse("2026-08-23T12:00:00Z");

  private static EventSourcedTestKit<AlertEntity.State, AlertEntity.Event, AlertEntity> alert(
      String operator, String threshold, boolean numeric, Integer rearm, boolean muted) {
    var kit = EventSourcedTestKit.of("a1", AlertEntity::new);
    kit.method(AlertEntity::create)
        .invoke(new AlertEntity.Create("An alert", "q1", "value", operator, threshold, numeric, null, rearm, muted));
    return kit;
  }

  private static StoredResult resultWith(Object value) {
    var row = new LinkedHashMap<String, Object>();
    row.put("value", value);
    return new StoredResult(
        "hash", "ds-1", "SELECT 1", List.<Map<String, Object>>of(row), List.of("value"), 0.1, NOW);
  }

  @Test
  void aFreshAlertStartsUnknownAndUnstamped() {
    var kit = alert(">", "5", true, null, false);
    assertEquals("UNKNOWN", kit.getState().state());
    assertNull(kit.getState().lastTriggeredAt());
  }

  @Test
  void anEvaluationThatTriggersStampsAndNotifies() {
    var kit = alert(">", "5", true, null, false);
    kit.method(AlertEntity::subscribe).invoke("someone");
    var verdict = kit.method(AlertEntity::evaluate).invoke(new AlertEntity.Evaluate(resultWith(10), NOW, List.of()));
    assertEquals("TRIGGERED", verdict.getReply().state());
    assertTrue(verdict.getReply().stateChanged());
    assertEquals(1, verdict.getReply().notified());
    assertEquals(NOW, kit.getState().lastTriggeredAt());
  }

  @Test
  void aRepeatOfTheSameStateWithoutRearmSaysNothingAndDoesNotRestamp() {
    var kit = alert(">", "5", true, null, false);
    kit.method(AlertEntity::subscribe).invoke("someone");
    kit.method(AlertEntity::evaluate).invoke(new AlertEntity.Evaluate(resultWith(10), NOW, List.of()));
    var later = NOW.plusSeconds(86400);
    var second = kit.method(AlertEntity::evaluate).invoke(new AlertEntity.Evaluate(resultWith(10), later, List.of()));
    assertEquals(0, second.getReply().notified());
    assertFalse(second.getReply().stateChanged());
    assertEquals(NOW, kit.getState().lastTriggeredAt(), "the stamp stays on the change, not the repeat");
  }

  @Test
  void aRepeatPastTheRearmWindowSaysSoAgainAndRestamps() {
    var kit = alert(">", "5", true, 3600, false);
    kit.method(AlertEntity::subscribe).invoke("someone");
    kit.method(AlertEntity::evaluate).invoke(new AlertEntity.Evaluate(resultWith(10), NOW, List.of()));
    var later = NOW.plusSeconds(3601);
    var second = kit.method(AlertEntity::evaluate).invoke(new AlertEntity.Evaluate(resultWith(10), later, List.of()));
    assertEquals(1, second.getReply().notified());
    assertEquals(later, kit.getState().lastTriggeredAt());
  }

  @Test
  void aMutedAlertChangesStateAndStampsAndSaysNothing() {
    var kit = alert(">", "5", true, null, true);
    kit.method(AlertEntity::subscribe).invoke("someone");
    var verdict = kit.method(AlertEntity::evaluate).invoke(new AlertEntity.Evaluate(resultWith(10), NOW, List.of()));
    assertEquals("TRIGGERED", verdict.getReply().state());
    assertTrue(verdict.getReply().stateChanged());
    assertEquals(0, verdict.getReply().notified());
    assertEquals(NOW, kit.getState().lastTriggeredAt());
  }

  @Test
  void aFirstEvaluationSettlingToOkChangesStateAndSaysNothing() {
    var kit = alert(">", "5", true, null, false);
    kit.method(AlertEntity::subscribe).invoke("someone");
    var verdict = kit.method(AlertEntity::evaluate).invoke(new AlertEntity.Evaluate(resultWith(1), NOW, List.of()));
    assertEquals("OK", verdict.getReply().state());
    assertTrue(verdict.getReply().stateChanged());
    assertEquals(0, verdict.getReply().notified());
    assertEquals(NOW, kit.getState().lastTriggeredAt());
  }

  @Test
  void anEvaluationThatCouldNotBeMadeLeavesEverythingAlone() {
    var kit = alert(">", "5", true, null, false);
    kit.method(AlertEntity::subscribe).invoke("someone");
    kit.method(AlertEntity::evaluate).invoke(new AlertEntity.Evaluate(resultWith(10), NOW, List.of()));
    var stampedAt = kit.getState().lastTriggeredAt();
    var verdict =
        kit.method(AlertEntity::evaluate)
            .invoke(new AlertEntity.Evaluate(resultWith("alpha"), NOW.plusSeconds(60), List.of()));
    assertEquals("TRIGGERED", verdict.getReply().state(), "the state it already had");
    assertFalse(verdict.getReply().stateChanged());
    assertEquals(0, verdict.getReply().notified());
    assertEquals(stampedAt, kit.getState().lastTriggeredAt());
  }

  // ---- R22: one failing destination costs its own delivery and none of the others ----

  @Test
  void everySubscriptionIsAttemptedAndOneFailureCostsOnlyItself() {
    var kit = alert(">", "5", true, null, false);
    kit.method(AlertEntity::subscribe).invoke("one");
    kit.method(AlertEntity::subscribe).invoke("two");
    kit.method(AlertEntity::subscribe).invoke("three");
    var verdict =
        kit.method(AlertEntity::evaluate)
            .invoke(new AlertEntity.Evaluate(resultWith(10), NOW, List.of("one")));
    assertEquals(2, verdict.getReply().notified());
    assertEquals(1, verdict.getReply().notificationFailures());
  }

  @Test
  void anAlertWithNoSubscriptionsStillChangesState() {
    var kit = alert(">", "5", true, null, false);
    var verdict = kit.method(AlertEntity::evaluate).invoke(new AlertEntity.Evaluate(resultWith(10), NOW, List.of()));
    assertEquals("TRIGGERED", verdict.getReply().state());
    assertEquals(0, verdict.getReply().notified());
  }

  @Test
  void subscribingTwiceUnderOneNameIsOneSubscription() {
    var kit = alert(">", "5", true, null, false);
    kit.method(AlertEntity::subscribe).invoke("one");
    kit.method(AlertEntity::subscribe).invoke("one");
    assertEquals(1, kit.getState().subscriptions().size());
  }

  @Test
  void noResultAtAllIsUnknownRatherThanAFailedComparison() {
    var kit = alert(">", "5", true, null, false);
    var verdict = kit.method(AlertEntity::evaluate).invoke(new AlertEntity.Evaluate(null, NOW, List.of()));
    assertEquals("UNKNOWN", verdict.getReply().state());
  }
}

package io.akka.redash.application;

import akka.javasdk.annotations.Component;
import akka.javasdk.annotations.TypeName;
import akka.javasdk.eventsourcedentity.EventSourcedEntity;
import io.akka.redash.domain.AlertCondition;
import io.akka.redash.domain.AlertNotification;
import io.akka.redash.domain.StoredResult;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * One alert on one query: the condition, where it stands, and who is subscribed.
 *
 * <p>The state and the timestamp are written by {@link #evaluate}, and whether anybody was
 * told is written alongside them rather than instead of them — the two suppressions in the
 * original silence the message and leave the state change alone (SPEC-001 R21).
 *
 * <p>The verdict travels as its enum name and not as a sealed interface: a sealed interface
 * inside a command record fails to deserialise at runtime (question-log row 4).
 */
@Component(id = "alert")
public class AlertEntity extends EventSourcedEntity<AlertEntity.State, AlertEntity.Event> {

  public record State(
      String alertId,
      String name,
      String queryId,
      String column,
      String operator,
      String threshold,
      boolean thresholdIsNumber,
      String selector,
      Integer rearmSeconds,
      boolean muted,
      String state,
      Instant lastTriggeredAt,
      List<String> subscriptions,
      int notificationsSent,
      int notificationFailures,
      boolean created) {

    AlertNotification.AlertState asAlertState() {
      return new AlertNotification.AlertState(
          AlertCondition.Verdict.valueOf(state), lastTriggeredAt, rearmSeconds, muted);
    }

    AlertCondition.Condition asCondition() {
      return new AlertCondition.Condition(
          column,
          operator,
          thresholdIsNumber ? Double.valueOf(threshold) : threshold,
          AlertCondition.Selector.parse(selector));
    }
  }

  public sealed interface Event {}

  @TypeName("alert-created")
  public record Created(
      String alertId,
      String name,
      String queryId,
      String column,
      String operator,
      String threshold,
      boolean thresholdIsNumber,
      String selector,
      Integer rearmSeconds,
      boolean muted)
      implements Event {}

  @TypeName("alert-evaluated")
  public record Evaluated(String state, Instant lastTriggeredAt, boolean stateChanged, int notified, int failed)
      implements Event {}

  /** An evaluation that could not be made at all leaves the state alone but is recorded. */
  @TypeName("alert-unevaluated")
  public record Unevaluated(String reason) implements Event {}

  @TypeName("alert-subscribed")
  public record Subscribed(String subscriber) implements Event {}

  @TypeName("alert-muted")
  public record MuteChanged(boolean muted) implements Event {}

  public record Create(
      String name,
      String queryId,
      String column,
      String operator,
      String threshold,
      boolean thresholdIsNumber,
      String selector,
      Integer rearmSeconds,
      boolean muted) {}

  /** What one evaluation produced, for a caller that needs to see it without re-reading. */
  public record Verdict(String state, boolean stateChanged, int notified, int notificationFailures) {}

  /** Identity comes from {@link Created}; a command context is not available here. */
  @Override
  public State emptyState() {
    return new State(
        null, null, null, null, null, null, false, null, null, false,
        AlertCondition.Verdict.UNKNOWN.name(), null, List.of(), 0, 0, false);
  }

  public Effect<State> create(Create create) {
    return effects()
        .persist(
            new Created(
                commandContext().entityId(),
                create.name(),
                create.queryId(),
                create.column(),
                create.operator(),
                create.threshold(),
                create.thresholdIsNumber(),
                create.selector(),
                create.rearmSeconds(),
                create.muted()))
        .thenReply(s -> s);
  }

  public Effect<State> subscribe(String subscriber) {
    return effects().persist(new Subscribed(subscriber)).thenReply(s -> s);
  }

  public Effect<State> setMuted(Boolean muted) {
    return effects().persist(new MuteChanged(muted)).thenReply(s -> s);
  }

  /**
   * The result is passed in rather than looked up. A view read straight after the write
   * that feeds it is not guaranteed to see it (question-log row 5), and the original's own
   * comment on the line before its alert check — "make sure that alert sees the latest
   * query result" — says this is a place where seeing the newest one is the point.
   */
  public record Evaluate(StoredResult result, Instant now, List<String> failingSubscribers) {}

  public Effect<Verdict> evaluate(Evaluate command) {
    var alert = currentState();
    var verdict = AlertCondition.evaluate(alert.asCondition(), command.result() == null ? null : command.result().asAlertInput());

    if (verdict == AlertCondition.Verdict.UNEVALUATED) {
      return effects()
          .persist(new Unevaluated("the comparison could not be made"))
          .thenReply(s -> new Verdict(s.state(), false, 0, 0));
    }

    var outcome = AlertNotification.apply(alert.asAlertState(), verdict, command.now());

    int sent = 0;
    int failed = 0;
    if (outcome.notified()) {
      var failing = command.failingSubscribers() == null ? List.<String>of() : command.failingSubscribers();
      // Every subscription is attempted, and one that fails costs its own delivery and
      // none of the others (R22) - so this counts both rather than stopping at the first.
      for (String subscriber : alert.subscriptions()) {
        if (failing.contains(subscriber)) {
          failed++;
        } else {
          sent++;
        }
      }
    }

    int finalSent = sent;
    int finalFailed = failed;
    return effects()
        .persist(
            new Evaluated(
                outcome.state().name(), outcome.lastTriggeredAt(), outcome.stateChanged(), finalSent, finalFailed))
        .thenReply(s -> new Verdict(s.state(), outcome.stateChanged(), finalSent, finalFailed));
  }

  public ReadOnlyEffect<State> get() {
    return effects().reply(currentState());
  }

  @Override
  public State applyEvent(Event event) {
    var s = currentState();
    return switch (event) {
      case Created e ->
          new State(e.alertId(), e.name(), e.queryId(), e.column(), e.operator(), e.threshold(), e.thresholdIsNumber(),
              e.selector(), e.rearmSeconds(), e.muted(), AlertCondition.Verdict.UNKNOWN.name(), null,
              List.of(), 0, 0, true);
      case Evaluated e ->
          new State(s.alertId(), s.name(), s.queryId(), s.column(), s.operator(), s.threshold(), s.thresholdIsNumber(),
              s.selector(), s.rearmSeconds(), s.muted(), e.state(), e.lastTriggeredAt(), s.subscriptions(),
              s.notificationsSent() + e.notified(), s.notificationFailures() + e.failed(), s.created());
      case Unevaluated e -> s;
      case Subscribed e -> {
        var next = new ArrayList<>(s.subscriptions());
        if (!next.contains(e.subscriber())) {
          next.add(e.subscriber());
        }
        yield new State(s.alertId(), s.name(), s.queryId(), s.column(), s.operator(), s.threshold(), s.thresholdIsNumber(),
            s.selector(), s.rearmSeconds(), s.muted(), s.state(), s.lastTriggeredAt(), List.copyOf(next),
            s.notificationsSent(), s.notificationFailures(), s.created());
      }
      case MuteChanged e ->
          new State(s.alertId(), s.name(), s.queryId(), s.column(), s.operator(), s.threshold(), s.thresholdIsNumber(),
              s.selector(), s.rearmSeconds(), e.muted(), s.state(), s.lastTriggeredAt(), s.subscriptions(),
              s.notificationsSent(), s.notificationFailures(), s.created());
    };
  }
}

package io.akka.redash.application;

import akka.javasdk.annotations.Component;
import akka.javasdk.annotations.TypeName;
import akka.javasdk.eventsourcedentity.EventSourcedEntity;
import io.akka.redash.domain.QueryHash;
import io.akka.redash.domain.Schedule;
import java.time.Instant;

/**
 * One saved query: its text, its schedule, how many refreshes in a row have failed, and
 * which stored result it currently points at.
 *
 * <p>The event type is a sealed interface at the top level of the entity, which is the one
 * place polymorphic JSON resolves — a sealed interface nested inside a command or a state
 * field does not (question-log row 4), so the schedule travels as its plain fields.
 */
@Component(id = "query")
public class QueryEntity extends EventSourcedEntity<QueryEntity.State, QueryEntity.Event> {

  public record State(
      String queryId,
      String queryText,
      String queryHash,
      String dataSourceId,
      Schedule schedule,
      int scheduleFailures,
      String latestResultId,
      Instant latestResultAt,
      boolean archived,
      boolean created) {

    public String cacheKey() {
      return QueryHash.cacheKey(queryHash, dataSourceId);
    }
  }

  public sealed interface Event {}

  @TypeName("saved")
  public record Saved(
      String queryId, String queryText, String queryHash, String dataSourceId, Schedule schedule)
      implements Event {}

  @TypeName("schedule-set")
  public record ScheduleSet(Schedule schedule) implements Event {}

  /**
   * The sweep could not read the schedule, so it wrote it back switched off. Distinct from
   * {@link ScheduleSet} because it is the system disabling a query rather than a person.
   */
  @TypeName("schedule-disabled-by-error")
  public record ScheduleDisabledByError(String reason) implements Event {}

  @TypeName("schedule-erased")
  public record ScheduleErased() implements Event {}

  @TypeName("refresh-failed")
  public record RefreshFailed(String message) implements Event {}

  @TypeName("refresh-succeeded")
  public record RefreshSucceeded(String resultId, Instant retrievedAt) implements Event {}

  @TypeName("archived")
  public record Archived() implements Event {}

  public record Save(String queryText, String dataSourceId, Schedule schedule) {}

  /**
   * The identity is left null here and filled from the event that creates the query. The
   * command context is only available while a command is being handled, and an empty state
   * is built outside one.
   */
  @Override
  public State emptyState() {
    return new State(null, null, null, null, null, 0, null, null, false, false);
  }

  public Effect<State> save(Save save) {
    var hash = QueryHash.of(save.queryText());
    return effects()
        .persist(new Saved(commandContext().entityId(), save.queryText(), hash, save.dataSourceId(), save.schedule()))
        .thenReply(s -> s);
  }

  public Effect<State> setSchedule(Schedule schedule) {
    return effects().persist(new ScheduleSet(schedule)).thenReply(s -> s);
  }

  public Effect<State> disableScheduleByError(String reason) {
    if (currentState().schedule() == null || currentState().schedule().isDisabled()) {
      return effects().reply(currentState());
    }
    return effects().persist(new ScheduleDisabledByError(reason)).thenReply(s -> s);
  }

  public Effect<State> eraseSchedule() {
    if (currentState().schedule() == null) {
      return effects().reply(currentState());
    }
    return effects().persist(new ScheduleErased()).thenReply(s -> s);
  }

  public Effect<State> recordFailure(String message) {
    return effects().persist(new RefreshFailed(message)).thenReply(s -> s);
  }

  public Effect<State> recordSuccess(RefreshSucceeded success) {
    return effects().persist(success).thenReply(s -> s);
  }

  public Effect<State> archive() {
    return effects().persist(new Archived()).thenReply(s -> s);
  }

  public ReadOnlyEffect<State> get() {
    return effects().reply(currentState());
  }

  @Override
  public State applyEvent(Event event) {
    var s = currentState();
    return switch (event) {
      case Saved e ->
          new State(e.queryId(), e.queryText(), e.queryHash(), e.dataSourceId(), e.schedule(), 0, null, null, false, true);
      case ScheduleSet e ->
          new State(s.queryId(), s.queryText(), s.queryHash(), s.dataSourceId(), e.schedule(),
              s.scheduleFailures(), s.latestResultId(), s.latestResultAt(), s.archived(), s.created());
      case ScheduleDisabledByError e ->
          new State(s.queryId(), s.queryText(), s.queryHash(), s.dataSourceId(),
              s.schedule() == null ? null : s.schedule().withDisabled(true),
              s.scheduleFailures(), s.latestResultId(), s.latestResultAt(), s.archived(), s.created());
      case ScheduleErased e ->
          new State(s.queryId(), s.queryText(), s.queryHash(), s.dataSourceId(), null,
              s.scheduleFailures(), s.latestResultId(), s.latestResultAt(), s.archived(), s.created());
      case RefreshFailed e ->
          new State(s.queryId(), s.queryText(), s.queryHash(), s.dataSourceId(), s.schedule(),
              s.scheduleFailures() + 1, s.latestResultId(), s.latestResultAt(), s.archived(), s.created());
      // A success clears the counter outright rather than decrementing it, so one good
      // run undoes any run of bad ones and the backoff starts again from nothing.
      case RefreshSucceeded e ->
          new State(s.queryId(), s.queryText(), s.queryHash(), s.dataSourceId(), s.schedule(),
              0, e.resultId(), e.retrievedAt(), s.archived(), s.created());
      case Archived e ->
          new State(s.queryId(), s.queryText(), s.queryHash(), s.dataSourceId(), s.schedule(),
              s.scheduleFailures(), s.latestResultId(), s.latestResultAt(), true, s.created());
    };
  }
}

package io.akka.redash.application;

import akka.javasdk.annotations.Component;
import akka.javasdk.keyvalueentity.KeyValueEntity;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * When each scheduled query last <em>started</em>, kept apart from the results
 * (SPEC-001 R14).
 *
 * <p>This is the reason a sweep does not enqueue the same query twice while its first run
 * is still going: the entry is written when execution begins, before any result exists, so
 * the next sweep already sees the query as having run. It is also why a stale entry makes a
 * query due even when its stored result is fresh — the entry is consulted first, and the
 * result's own timestamp is only the fallback.
 *
 * <p>One entity holds the whole map, the way the original holds one redis hash. That is a
 * deliberate copy of the shape rather than an accident: the sweep reads every entry at once
 * and reading them one query at a time would turn one read into as many reads as there are
 * scheduled queries.
 */
@Component(id = "execution-tracker")
public class ExecutionTrackerEntity extends KeyValueEntity<ExecutionTrackerEntity.State> {

  /** The single instance. The original's redis key is a constant too. */
  public static final String ID = "sq:executed_at";

  public record State(Map<String, Long> startedAtMillis) {}

  public record Started(String queryId, Instant at) {}

  public record Forgotten(String queryId) {}

  @Override
  public State emptyState() {
    return new State(Map.of());
  }

  public Effect<State> started(Started started) {
    var next = new LinkedHashMap<>(currentState().startedAtMillis());
    next.put(started.queryId(), started.at().toEpochMilli());
    var state = new State(Map.copyOf(next));
    return effects().updateState(state).thenReply(state);
  }

  public Effect<State> forget(Forgotten forgotten) {
    var next = new LinkedHashMap<>(currentState().startedAtMillis());
    next.remove(forgotten.queryId());
    var state = new State(Map.copyOf(next));
    return effects().updateState(state).thenReply(state);
  }

  public ReadOnlyEffect<State> get() {
    return effects().reply(currentState());
  }
}

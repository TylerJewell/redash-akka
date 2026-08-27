package io.akka.redash.application;

import akka.javasdk.annotations.Component;
import akka.javasdk.eventsourcedentity.EventSourcedEntity;
import java.util.Map;

/**
 * One row of one of redash's eighteen tables.
 *
 * <p>There is a single entity type rather than eighteen because the eighteen are the same
 * shape: redash's models carry a field list and almost no behaviour, with the rules in its
 * handlers and its tasks. Here those rules are in `io.akka.redash.domain` and the
 * endpoints, so eighteen entity classes would differ only in their name. Identity is
 * namespaced instead — `query:5`, `user:1`, `favorite:Query:5:1` — which keeps every row
 * addressable on its own and keeps the write path for a query independent of the write
 * path for a dashboard.
 *
 * <p>It is event-sourced rather than key-value because two of the tables — queries and
 * dashboards — carry a version and a change log, and reconstructing "what changed" from a
 * key-value store means writing the change log twice.
 */
@Component(id = "record")
public class RecordEntity extends EventSourcedEntity<Doc.State, Doc.Event> {

  /**
   * The fields of a write, wrapped in a record.
   *
   * <p>A bare {@code Map<String, Object>} cannot be a command type: the wire serializer
   * records the concrete class it was handed and cannot resolve that back to the erased
   * interface the method declares, so every call fails at the runtime rather than at
   * compile time. The wrapper gives the command a type of its own.
   */
  public record Write(Map<String, Object> fields) {}

  @Override
  public Doc.State emptyState() {
    return Doc.State.empty();
  }

  /**
   * Write a row that is not there yet. Creating one that already exists is not an error —
   * it overwrites, which is what a caller that re-sends the same create expects.
   */
  public Effect<Doc.State> create(Write command) {
    return effects()
        .persist(new Doc.Created(commandContext().entityId(), command.fields()))
        .thenReply(state -> state);
  }

  /** Move the named fields and leave the rest. */
  public Effect<Doc.State> update(Write command) {
    if (!currentState().exists()) {
      return effects().error("not found");
    }
    return effects().persist(new Doc.Updated(command.fields())).thenReply(state -> state);
  }

  /**
   * Write the fields whether or not the row is there, creating it if it is not. Used where
   * redash's own write is an upsert — a favourite, a lock, a cached schema.
   */
  public Effect<Doc.State> put(Write command) {
    if (currentState().exists()) {
      return effects().persist(new Doc.Updated(command.fields())).thenReply(state -> state);
    }
    return effects()
        .persist(new Doc.Created(commandContext().entityId(), command.fields()))
        .thenReply(state -> state);
  }

  /** Mark the row gone. The journal keeps what it was, which is what a change log needs. */
  public Effect<Doc.State> delete() {
    if (!currentState().exists()) {
      return effects().reply(currentState());
    }
    return effects().persist(new Doc.Deleted()).thenReply(state -> state);
  }

  public ReadOnlyEffect<Doc.State> get() {
    return effects().reply(currentState());
  }

  @Override
  public Doc.State applyEvent(Doc.Event event) {
    return Doc.apply(currentState(), event);
  }
}

package io.akka.redash.application;

import akka.javasdk.annotations.Component;
import akka.javasdk.keyvalueentity.KeyValueEntity;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * One table's identifier counter, and the list of identifiers it currently holds.
 *
 * <p>redash's identifiers are database sequences, and both its API and its front end treat
 * them as small consecutive integers — `/api/queries/5`. Keeping that shape is what lets the
 * original's front end be repointed at this rebuild without touching a route, and what lets
 * the two systems be walked side by side at step e. The cost is a single writer per table,
 * which is recorded in the README as a difference in scaling rather than in answers
 * (SPEC-001 D-6).
 *
 * <p>**Why it also holds the identifiers.** Everything above this reads a list through a
 * view, and a view is updated after the write rather than with it. redash reads its own
 * writes — its lists are a SQL query over the table that was just written — so a list read
 * immediately after a create includes it, and this must too. The membership of a table is
 * therefore kept here, where every insert and delete already passes; the view still supplies
 * the row bodies, and only a row the view has not caught up with is read individually. That
 * makes a list correct at the moment it is asked for and costs one extra read of this
 * entity.
 *
 * <p>It was found by making the rebuild faster. For as long as every request took two
 * seconds — a name resolution the benchmark was accidentally measuring — the view had always
 * caught up by the time anything asked, and 157 of 157 walk steps agreed. With that removed
 * and requests down to fifteen milliseconds, thirty-four of them disagreed, every one of them
 * a list that did not contain what the step before had just created.
 */
@Component(id = "sequence")
public class SequenceEntity extends KeyValueEntity<SequenceEntity.Counter> {

  /**
   * @param last the highest identifier handed out
   * @param ids every identifier the table currently holds, in the order they were added
   */
  public record Counter(long last, List<String> ids) {

    public Counter {
      ids = ids == null ? List.of() : ids;
    }
  }

  @Override
  public Counter emptyState() {
    return new Counter(0, List.of());
  }

  /** The next identifier, which is always one more than the last one handed out. */
  public Effect<Long> next() {
    var next = currentState().last() + 1;
    return effects().updateState(new Counter(next, currentState().ids())).thenReply(next);
  }

  /** Where the counter is now, without moving it. */
  public ReadOnlyEffect<Long> peek() {
    return effects().reply(currentState().last());
  }

  /** Every identifier the table holds, as it is at this instant. */
  public ReadOnlyEffect<List<String>> ids() {
    return effects().reply(currentState().ids());
  }

  /**
   * Move the counter forward to at least the given value. Used when a row is written with
   * an identifier chosen elsewhere — a fixture, a restore — so the next allocation does not
   * collide with it.
   */
  public Effect<Long> atLeast(Long value) {
    if (value == null || value <= currentState().last()) {
      return effects().reply(currentState().last());
    }
    return effects().updateState(new Counter(value, currentState().ids())).thenReply(value);
  }

  /** Record that the table now holds this identifier. Adding a known one changes nothing. */
  public Effect<akka.Done> hold(String id) {
    if (currentState().ids().contains(id)) {
      return effects().reply(akka.Done.getInstance());
    }
    Set<String> held = new LinkedHashSet<>(currentState().ids());
    held.add(id);
    return effects()
        .updateState(new Counter(currentState().last(), List.copyOf(held)))
        .thenReply(akka.Done.getInstance());
  }

  /** Record that the table no longer holds it. */
  public Effect<akka.Done> release(String id) {
    if (!currentState().ids().contains(id)) {
      return effects().reply(akka.Done.getInstance());
    }
    Set<String> held = new LinkedHashSet<>(currentState().ids());
    held.remove(id);
    return effects()
        .updateState(new Counter(currentState().last(), List.copyOf(held)))
        .thenReply(akka.Done.getInstance());
  }
}

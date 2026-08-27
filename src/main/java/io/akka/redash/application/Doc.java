package io.akka.redash.application;

import akka.javasdk.annotations.TypeName;
import io.akka.redash.domain.Json;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One stored record, and the three things that can happen to it.
 *
 * <p>Every one of redash's eighteen tables is an entity of this shape. That is deliberate
 * and it mirrors the source: redash's models are thin rows with almost no behaviour on
 * them — the rules live in the handlers and the tasks, and in this rebuild they live in
 * `io.akka.redash.domain` and the endpoints. Giving each table its own hand-written state
 * record would move nothing into the entities except a second copy of the field list.
 *
 * <p>A document holds only values a JSON reader gives back unchanged: strings, whole
 * numbers, doubles, booleans, lists and maps. An instant is stored as its wire string
 * rather than as an `Instant`, because a field typed `Object` deserialises to whatever the
 * JSON says and a round trip through the journal would otherwise hand back a string where
 * an instant went in — a difference that shows up only after a restart.
 */
public final class Doc {

  private Doc() {}

  /** What an entity holds: its own identity, its fields, and whether it is gone. */
  public record State(String id, Map<String, Object> fields, boolean deleted) {
    public static State empty() {
      return new State(null, Map.of(), false);
    }

    public boolean exists() {
      return id != null && !deleted;
    }

    public Object get(String name) {
      return fields.get(name);
    }

    public String text(String name) {
      var value = fields.get(name);
      return value == null ? null : String.valueOf(value);
    }

    public long number(String name, long fallback) {
      var value = fields.get(name);
      return value instanceof Number n ? n.longValue() : fallback;
    }

    public boolean flag(String name) {
      return Boolean.TRUE.equals(fields.get(name));
    }

    public List<Object> list(String name) {
      return Json.asList(fields.get(name));
    }

    public Map<String, Object> map(String name) {
      return Json.asMap(fields.get(name));
    }
  }

  public sealed interface Event {}

  @TypeName("created")
  public record Created(String id, Map<String, Object> fields) implements Event {}

  /** A partial write: only the named fields move, the rest are left as they were. */
  @TypeName("updated")
  public record Updated(Map<String, Object> fields) implements Event {}

  @TypeName("deleted")
  public record Deleted() implements Event {}

  public static State apply(State current, Event event) {
    return switch (event) {
      case Created e -> new State(e.id(), normalise(e.fields()), false);
      case Updated e -> {
        var merged = new LinkedHashMap<>(current.fields());
        merged.putAll(normalise(e.fields()));
        yield new State(current.id(), merged, current.deleted());
      }
      case Deleted ignored -> new State(current.id(), current.fields(), true);
    };
  }

  /**
   * Make a document hold only what a JSON round trip gives back unchanged.
   *
   * <p>Jackson hands an integral number back as an `Integer` when it fits and a `Long` when
   * it does not, so an identifier written as a `long` comes back as an `Integer` and every
   * `equals` against a `Long` quietly fails. Widening on the way in makes the two the same
   * value rather than leaving the difference to be discovered by a comparison that returns
   * false.
   */
  public static Map<String, Object> normalise(Map<String, Object> fields) {
    var out = new LinkedHashMap<String, Object>(fields.size());
    fields.forEach((key, value) -> out.put(key, normaliseValue(value)));
    return out;
  }

  static Object normaliseValue(Object value) {
    return switch (value) {
      case null -> null;
      case Integer number -> number.longValue();
      case Short number -> number.longValue();
      case Byte number -> number.longValue();
      case java.time.Instant instant -> Json.instant(instant);
      case Map<?, ?> map -> normalise(Json.asMap(map));
      case List<?> list -> {
        var copy = new java.util.ArrayList<>(list.size());
        for (Object item : list) {
          copy.add(normaliseValue(item));
        }
        yield copy;
      }
      default -> value;
    };
  }
}

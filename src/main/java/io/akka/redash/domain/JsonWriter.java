package io.akka.redash.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * The exact bytes redash puts on the wire (SPEC-001 R90).
 *
 * <p>This is hand-rolled rather than handed to a JSON library because two details of the
 * output are observable and neither is a library default. Python's encoder separates
 * members with {@code ", "} and keys from values with {@code ": "}, so a response compared
 * byte for byte against the original disagrees on every object if the spaces are dropped.
 * And it writes non-ASCII characters through rather than escaping them, because redash
 * asks it to.
 */
final class JsonWriter {

  private final StringBuilder out = new StringBuilder();

  private JsonWriter() {}

  static String write(Object value) {
    var writer = new JsonWriter();
    writer.value(value);
    return writer.out.toString();
  }

  private void value(Object value) {
    switch (value) {
      case null -> out.append("null");
      case Map<?, ?> map -> {
        out.append('{');
        boolean first = true;
        for (var entry : map.entrySet()) {
          if (!first) {
            out.append(", ");
          }
          string(String.valueOf(entry.getKey()));
          out.append(": ");
          value(entry.getValue());
          first = false;
        }
        out.append('}');
      }
      case List<?> list -> {
        out.append('[');
        for (int i = 0; i < list.size(); i++) {
          if (i > 0) {
            out.append(", ");
          }
          value(list.get(i));
        }
        out.append(']');
      }
      case Boolean bool -> out.append(bool ? "true" : "false");
      case Instant instant -> string(Json.instant(instant));
      case OffsetDateTime instant -> string(Json.instant(instant));
      case LocalDateTime naive -> string(Json.naive(naive));
      case LocalDate date -> string(date.toString());
      case LocalTime time -> string(Json.time(time));
      case byte[] bytes -> string(Json.hex(bytes));
      case BigDecimal number -> out.append(Numbers.pythonNumber(number.doubleValue()));
      case Double d -> number(d);
      case Float f -> number(f.doubleValue());
      case Number number -> out.append(number.toString());
      case CharSequence text -> string(text.toString());
      default -> string(String.valueOf(value));
    }
  }

  /** A number that is not finite is not JSON, so it is written as null wherever it appears. */
  private void number(double value) {
    if (Double.isNaN(value) || Double.isInfinite(value)) {
      out.append("null");
      return;
    }
    if (value == Math.rint(value) && Math.abs(value) < 1e16) {
      out.append((long) value).append(".0");
      return;
    }
    out.append(value);
  }

  private void string(String text) {
    out.append('"');
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      switch (c) {
        case '"' -> out.append("\\\"");
        case '\\' -> out.append("\\\\");
        case '\n' -> out.append("\\n");
        case '\r' -> out.append("\\r");
        case '\t' -> out.append("\\t");
        case '\b' -> out.append("\\b");
        case '\f' -> out.append("\\f");
        default -> {
          if (c < 0x20) {
            out.append(String.format("\\u%04x", (int) c));
          } else {
            out.append(c);
          }
        }
      }
    }
    out.append('"');
  }
}

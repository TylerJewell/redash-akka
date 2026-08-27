package io.akka.redash.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * How a value becomes the JSON redash puts on the wire (SPEC-001 R90).
 *
 * <p>Two things here are not what a JSON library does by default and both are observable.
 * An instant is written with its microseconds cut to milliseconds and a UTC offset written
 * as {@code Z} rather than {@code +00:00}; and a value that is not a number — a NaN or
 * either infinity — becomes {@code null} rather than a token no parser accepts, recursively
 * through every map and list.
 */
public final class Json {

  public static final ObjectMapper MAPPER = new ObjectMapper();

  private static final DateTimeFormatter MILLIS =
      DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS");
  private static final DateTimeFormatter SECONDS =
      DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

  private Json() {}

  /** The wire form of an instant: milliseconds when it has any, {@code Z} for UTC. */
  public static String instant(Instant value) {
    if (value == null) {
      return null;
    }
    var utc = value.atOffset(ZoneOffset.UTC);
    var text = utc.getNano() == 0 ? SECONDS.format(utc) : MILLIS.format(utc);
    return text + "Z";
  }

  /** The wire form of an offset instant, keeping a non-UTC offset as it is. */
  public static String instant(OffsetDateTime value) {
    if (value == null) {
      return null;
    }
    var text = value.getNano() == 0 ? SECONDS.format(value) : MILLIS.format(value);
    if (value.getOffset().getTotalSeconds() == 0) {
      return text + "Z";
    }
    return text + value.getOffset().getId();
  }

  public static String date(LocalDate value) {
    return value == null ? null : value.toString();
  }

  public static String time(LocalTime value) {
    if (value == null) {
      return null;
    }
    // Python's `isoformat` writes microseconds and redash cuts the string at twelve
    // characters, which lands on milliseconds.
    var text = value.toString();
    return text.length() > 12 ? text.substring(0, 12) : text;
  }

  public static String hex(byte[] value) {
    var out = new StringBuilder(value.length * 2);
    for (byte b : value) {
      out.append(Character.forDigit((b >> 4) & 0xf, 16)).append(Character.forDigit(b & 0xf, 16));
    }
    return out.toString();
  }

  /**
   * Replace every non-finite number with null, everywhere. A partly-sanitised document is
   * worse than an unsanitised one, because the reader stops looking after the first level.
   */
  public static Object sanitize(Object value) {
    if (value instanceof Map<?, ?> map) {
      var out = new LinkedHashMap<String, Object>(map.size());
      for (var entry : map.entrySet()) {
        out.put(String.valueOf(entry.getKey()), sanitize(entry.getValue()));
      }
      return out;
    }
    if (value instanceof List<?> list) {
      var out = new ArrayList<>(list.size());
      for (Object item : list) {
        out.add(sanitize(item));
      }
      return out;
    }
    if (value instanceof Double d && (d.isNaN() || d.isInfinite())) {
      return null;
    }
    if (value instanceof Float f && (f.isNaN() || f.isInfinite())) {
      return null;
    }
    return value;
  }

  /** Write a value the way redash writes it, separators and all. */
  public static String dumps(Object value) {
    return JsonWriter.write(value);
  }

  /**
   * An instant with no zone, which is what a naive Python datetime becomes. It carries
   * no trailing marker, so a stored value that never had a zone does not gain one.
   */
  public static String naive(java.time.LocalDateTime value) {
    if (value == null) {
      return null;
    }
    var text = value.getNano() == 0 ? SECONDS.format(value) : MILLIS.format(value);
    return text;
  }

  public static Object loads(String text) {
    try {
      return fromNode(MAPPER.readTree(text));
    } catch (IOException e) {
      throw new IllegalArgumentException("not JSON: " + e.getMessage(), e);
    }
  }

  /** A tree as plain maps, lists and boxed scalars, so one shape flows through the port. */
  public static Object fromNode(JsonNode node) {
    if (node == null || node.isNull()) {
      return null;
    }
    if (node instanceof ObjectNode object) {
      var out = new LinkedHashMap<String, Object>();
      object.properties().forEach(e -> out.put(e.getKey(), fromNode(e.getValue())));
      return out;
    }
    if (node instanceof ArrayNode array) {
      var out = new ArrayList<>(array.size());
      array.forEach(child -> out.add(fromNode(child)));
      return out;
    }
    if (node.isBoolean()) {
      return node.booleanValue();
    }
    if (node.isIntegralNumber()) {
      return node.canConvertToLong() ? (Object) node.longValue() : node.bigIntegerValue();
    }
    if (node.isFloatingPointNumber()) {
      return node.doubleValue();
    }
    return node.asText();
  }

  @SuppressWarnings("unchecked")
  public static Map<String, Object> asMap(Object value) {
    return value instanceof Map<?, ?> map ? (Map<String, Object>) map : new LinkedHashMap<>();
  }

  @SuppressWarnings("unchecked")
  public static List<Object> asList(Object value) {
    return value instanceof List<?> list ? (List<Object>) list : new ArrayList<>();
  }

  public static Map<String, Object> map(Object... pairs) {
    var out = new LinkedHashMap<String, Object>();
    for (int i = 0; i + 1 < pairs.length; i += 2) {
      out.put(String.valueOf(pairs[i]), pairs[i + 1]);
    }
    return out;
  }
}

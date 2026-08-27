package io.akka.redash.domain;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;

/**
 * What a value's type is, and how a number reaches a query's text (SPEC-001 R93, R72).
 *
 * <p>The order matters and is the source's: a string that reads as a whole number is an
 * integer before it is anything else, then a float, then a boolean, then a date. So
 * {@code "007"} is an integer, {@code "nan"} is a float — because Python's {@code float()}
 * accepts it — and {@code "0x10"} is a string, because none of the four accept it.
 */
public final class Numbers {

  public static final String TYPE_INTEGER = "integer";
  public static final String TYPE_FLOAT = "float";
  public static final String TYPE_BOOLEAN = "boolean";
  public static final String TYPE_STRING = "string";
  public static final String TYPE_DATETIME = "datetime";
  public static final String TYPE_DATE = "date";

  public static final List<String> SUPPORTED_TYPES =
      List.of(TYPE_INTEGER, TYPE_FLOAT, TYPE_BOOLEAN, TYPE_STRING, TYPE_DATETIME, TYPE_DATE);

  private Numbers() {}

  public static String guessType(Object value) {
    return switch (value) {
      case Boolean ignored -> TYPE_BOOLEAN;
      case Integer ignored -> TYPE_INTEGER;
      case Long ignored -> TYPE_INTEGER;
      case java.math.BigInteger ignored -> TYPE_INTEGER;
      case Short ignored -> TYPE_INTEGER;
      case Byte ignored -> TYPE_INTEGER;
      case Double ignored -> TYPE_FLOAT;
      case Float ignored -> TYPE_FLOAT;
      case java.math.BigDecimal ignored -> TYPE_FLOAT;
      case null -> TYPE_STRING;
      default -> guessTypeFromString(String.valueOf(value));
    };
  }

  public static String guessTypeFromString(String value) {
    if (value == null || value.isEmpty()) {
      return TYPE_STRING;
    }
    if (isPythonInteger(value)) {
      return TYPE_INTEGER;
    }
    if (isPythonFloat(value)) {
      return TYPE_FLOAT;
    }
    var lower = value.toLowerCase(Locale.ROOT);
    if (lower.equals("true") || lower.equals("false")) {
      return TYPE_BOOLEAN;
    }
    if (looksLikeADate(value)) {
      return TYPE_DATETIME;
    }
    return TYPE_STRING;
  }

  /**
   * Python's {@code int()}: surrounding whitespace, an optional sign, decimal digits, and
   * underscores **between** digits. An underscore at either end, or two together, is not a
   * number.
   */
  public static boolean isPythonInteger(String value) {
    var text = value.strip();
    if (text.isEmpty()) {
      return false;
    }
    int i = 0;
    if (text.charAt(0) == '+' || text.charAt(0) == '-') {
      i = 1;
    }
    if (i >= text.length()) {
      return false;
    }
    boolean previousWasDigit = false;
    boolean sawDigit = false;
    for (; i < text.length(); i++) {
      char c = text.charAt(i);
      if (c == '_') {
        if (!previousWasDigit) {
          return false;
        }
        previousWasDigit = false;
      } else if (c >= '0' && c <= '9') {
        previousWasDigit = true;
        sawDigit = true;
      } else {
        return false;
      }
    }
    return sawDigit && previousWasDigit;
  }

  /** Python's {@code float()}, which accepts {@code nan}, {@code inf} and {@code infinity}. */
  public static boolean isPythonFloat(String value) {
    var text = value.strip().toLowerCase(Locale.ROOT).replace("_", "");
    if (text.isEmpty()) {
      return false;
    }
    var body = text.startsWith("+") || text.startsWith("-") ? text.substring(1) : text;
    if (body.equals("nan") || body.equals("inf") || body.equals("infinity")) {
      return true;
    }
    // Java's own parser accepts a trailing d/f and a hex literal; Python's does not.
    if (body.endsWith("d") || body.endsWith("f") || body.startsWith("0x")) {
      return false;
    }
    try {
      Double.parseDouble(text);
      return true;
    } catch (NumberFormatException e) {
      return false;
    }
  }

  /**
   * Whether a string is a date. The source hands this to `dateutil`, which accepts a very
   * wide range of shapes; this accepts the ISO-8601 family and the two common
   * separator-swapped forms, and README's difference list says so.
   */
  public static boolean looksLikeADate(String value) {
    var text = value.strip();
    if (text.length() < 6) {
      return false;
    }
    try {
      OffsetDateTime.parse(text);
      return true;
    } catch (DateTimeParseException ignored) {
      // fall through
    }
    try {
      LocalDateTime.parse(text.replace(' ', 'T'));
      return true;
    } catch (DateTimeParseException ignored) {
      // fall through
    }
    try {
      LocalDate.parse(text);
      return true;
    } catch (DateTimeParseException ignored) {
      return false;
    }
  }

  /** How Python writes a float: whole values keep their {@code .0}. */
  public static String pythonNumber(double value) {
    if (Double.isNaN(value)) {
      return "nan";
    }
    if (Double.isInfinite(value)) {
      return value > 0 ? "inf" : "-inf";
    }
    if (value == Math.rint(value) && Math.abs(value) < 1e16) {
      return (long) value + ".0";
    }
    return Double.toString(value);
  }

  /** A number for the alert comparison, or null when the value will not convert. */
  public static Double toDouble(Object value) {
    if (value instanceof Number number) {
      return number.doubleValue();
    }
    if (value instanceof CharSequence text) {
      try {
        return Double.parseDouble(text.toString().strip());
      } catch (NumberFormatException e) {
        return null;
      }
    }
    return null;
  }
}

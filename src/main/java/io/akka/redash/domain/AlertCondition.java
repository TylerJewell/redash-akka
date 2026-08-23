package io.akka.redash.domain;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The condition an alert puts to a query's latest result, and the state that produces
 * (SPEC-001 R18, R19).
 *
 * <p>Two things here look like mistakes and are not.
 *
 * <p>The first is that {@link Verdict} carries an {@code UNEVALUATED} value distinct from
 * {@code UNKNOWN}. A value that is neither a number nor convertible to one, put to an
 * ordering operator against a numeric threshold, has no answer at all in the original: the
 * comparison itself fails and the alert is left exactly as it was, which is a different
 * outcome from reaching {@code unknown}. Folding the two together would make a muted alert
 * and a failed comparison indistinguishable.
 *
 * <p>The second is that a value which is not a number and compares false answers
 * {@code UNKNOWN} rather than {@code OK}, but only under an ordering operator. Under an
 * equality operator the same value answers {@code OK} or {@code TRIGGERED} like any other.
 */
public final class AlertCondition {

  public enum Verdict {
    UNKNOWN,
    OK,
    TRIGGERED,
    /** The comparison could not be made; the alert keeps whatever state it had. */
    UNEVALUATED
  }

  public enum Selector {
    FIRST,
    MIN,
    MAX;

    /** An absent selector is `first`, which is a rule rather than a default. */
    public static Selector parse(String value) {
      if (value == null) {
        return FIRST;
      }
      return switch (value) {
        case "min" -> MIN;
        case "max" -> MAX;
        default -> FIRST;
      };
    }
  }

  /** The nine operators the original ships, including the three spelled-out legacy ones. */
  public static final Map<String, String> OPERATORS = buildOperators();

  private static Map<String, String> buildOperators() {
    var map = new LinkedHashMap<String, String>();
    for (String op : List.of(">", ">=", "<", "<=", "==", "!=")) {
      map.put(op, op);
    }
    map.put("greater than", ">");
    map.put("less than", "<");
    map.put("equals", "==");
    return Map.copyOf(map);
  }

  private static final List<String> EQUALITY = List.of("==", "!=");

  private AlertCondition() {}

  /**
   * @param column which column of the result row to read
   * @param operator one of {@link #OPERATORS}; anything else never holds, so the answer is
   *     {@code OK} or {@code UNKNOWN} rather than a failure
   * @param threshold the value to compare against, as it was configured — a string here is
   *     not the same question as a number
   */
  public record Condition(String column, String operator, Object threshold, Selector selector) {}

  public static Verdict evaluate(Condition condition, QueryResultData data) {
    if (data == null || data.rows() == null || data.rows().isEmpty()) {
      return Verdict.UNKNOWN;
    }
    var firstRow = data.rows().get(0);
    if (!firstRow.containsKey(condition.column())) {
      return Verdict.UNKNOWN;
    }

    Object value;
    try {
      value = select(condition, data);
    } catch (NumberFormatException e) {
      // min/max over a column that will not convert - the original's `except ValueError`
      return Verdict.UNKNOWN;
    }
    if (value == null) {
      return Verdict.UNKNOWN;
    }

    return compare(condition, value);
  }

  private static Object select(Condition condition, QueryResultData data) {
    if (condition.selector() == Selector.FIRST) {
      return data.rows().get(0).get(condition.column());
    }
    double best = condition.selector() == Selector.MAX ? Double.NEGATIVE_INFINITY : Double.POSITIVE_INFINITY;
    for (var row : data.rows()) {
      double candidate = asDouble(row.get(condition.column()));
      best = condition.selector() == Selector.MAX ? Math.max(best, candidate) : Math.min(best, candidate);
    }
    return best;
  }

  private static double asDouble(Object value) {
    if (value instanceof Number n) {
      return n.doubleValue();
    }
    if (value == null) {
      throw new NumberFormatException("null");
    }
    return Double.parseDouble(value.toString());
  }

  private static Verdict compare(Condition condition, Object rawValue) {
    String operator = OPERATORS.get(condition.operator());
    if (operator == null) {
      // An operator nobody recognises never holds. The original's `OPERATORS.get(op,
      // lambda v, t: False)` reaches the same answer by a different route.
      return Verdict.OK;
    }

    Object value = rawValue;
    Object threshold = condition.threshold();
    boolean valueIsNumber;

    if (value instanceof Boolean b) {
      // A boolean becomes its lower-cased name first, so that what a person sees in a
      // notification is `true` rather than Python's `True`.
      value = b.toString().toLowerCase(java.util.Locale.ROOT);
      valueIsNumber = false;
    } else {
      Double asNumber = tryNumber(value);
      valueIsNumber = asNumber != null;
      if (valueIsNumber) {
        value = asNumber;
        Double thresholdNumber = tryNumber(threshold);
        if (thresholdNumber == null) {
          return Verdict.UNKNOWN;
        }
        threshold = thresholdNumber;
      } else {
        value = value.toString();
      }
    }

    if (valueIsNumber) {
      int order = Double.compare((Double) value, (Double) threshold);
      return holds(operator, order) ? Verdict.TRIGGERED : Verdict.OK;
    }

    if (!(threshold instanceof String)) {
      // A value that is not a number against a threshold that is: the comparison cannot be
      // made at all, which is not the same as answering unknown.
      return EQUALITY.contains(operator)
          ? (holdsByEquality(operator, value, threshold) ? Verdict.TRIGGERED : Verdict.OK)
          : Verdict.UNEVALUATED;
    }

    int order = ((String) value).compareTo((String) threshold);
    if (holds(operator, order)) {
      return Verdict.TRIGGERED;
    }
    return EQUALITY.contains(operator) ? Verdict.OK : Verdict.UNKNOWN;
  }

  private static boolean holdsByEquality(String operator, Object value, Object threshold) {
    boolean equal = String.valueOf(value).equals(String.valueOf(threshold));
    return operator.equals("==") == equal;
  }

  private static boolean holds(String operator, int order) {
    return switch (operator) {
      case ">" -> order > 0;
      case ">=" -> order >= 0;
      case "<" -> order < 0;
      case "<=" -> order <= 0;
      case "==" -> order == 0;
      case "!=" -> order != 0;
      default -> false;
    };
  }

  private static Double tryNumber(Object value) {
    if (value instanceof Boolean) {
      return null;
    }
    if (value instanceof Number n) {
      return n.doubleValue();
    }
    if (value == null) {
      return null;
    }
    try {
      return Double.valueOf(value.toString());
    } catch (NumberFormatException e) {
      return null;
    }
  }

  /** The rows and columns a stored result carries, as far as an alert is concerned. */
  public record QueryResultData(List<Map<String, Object>> rows, List<String> columns) {}
}

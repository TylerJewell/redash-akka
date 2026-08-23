package io.akka.redash.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import io.akka.redash.domain.AlertCondition.Condition;
import io.akka.redash.domain.AlertCondition.QueryResultData;
import io.akka.redash.domain.AlertCondition.Selector;
import io.akka.redash.domain.AlertCondition.Verdict;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * SPEC-001 R18 and R19, against every row the original produced.
 *
 * <p>The table is not written out here: it is `alert-evaluate-from-redash.txt`, exported
 * from a run of redash over the product of selector, value kind and all nine operators. A
 * rebuild that agreed with a hand-written selection of those rows would be agreeing with
 * whichever ones somebody found interesting.
 */
class AlertEvaluationTest {

  private record Row(String family, String selector, String valueKind, String op, String threshold, String state) {}

  private static List<Row> table() throws IOException {
    var rows = new ArrayList<Row>();
    try (InputStream in =
        AlertEvaluationTest.class.getClassLoader().getResourceAsStream("alert-evaluate-from-redash.txt")) {
      for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
        line = line.trim();
        if (line.isEmpty() || line.startsWith("#")) {
          continue;
        }
        var f = line.split("\\|", -1);
        rows.add(new Row(f[0], f[1], f[2], f[3], f[4], f[5]));
      }
    }
    return rows;
  }

  private static Object cellFor(String valueKind) {
    return switch (valueKind) {
      case "whole-number" -> 10;
      case "float" -> 10.5;
      case "numeric-string" -> "10";
      case "non-numeric-string" -> "alpha";
      case "boolean-true" -> Boolean.TRUE;
      case "boolean-false" -> Boolean.FALSE;
      case "null" -> null;
      default -> throw new IllegalArgumentException(valueKind);
    };
  }

  private static QueryResultData dataFor(Row row) {
    var columns = List.of("value");
    return switch (row.valueKind()) {
      case "rows=3,9,1" -> new QueryResultData(List.of(cell(3), cell(9), cell(1)), columns);
      case "non-numeric-rows" -> new QueryResultData(List.of(cell("alpha"), cell("beta")), columns);
      case "no-result-at-all" -> null;
      case "empty-rows" -> new QueryResultData(List.of(), columns);
      case "missing-column" -> new QueryResultData(List.of(namedCell("other", 10)), columns);
      case "no-result-row" -> new QueryResultData(List.of(cell(null)), columns);
      default -> new QueryResultData(List.of(cell(cellFor(row.valueKind()))), columns);
    };
  }

  private static Map<String, Object> cell(Object value) {
    return namedCell("value", value);
  }

  private static Map<String, Object> namedCell(String name, Object value) {
    var m = new LinkedHashMap<String, Object>();
    m.put(name, value);
    return m;
  }

  private static Object thresholdFor(String raw) {
    try {
      return Integer.valueOf(raw);
    } catch (NumberFormatException e) {
      return raw;
    }
  }

  /** How the original reported each outcome, including the one where it reached no state. */
  private static Verdict expected(String state) {
    return switch (state) {
      case "triggered" -> Verdict.TRIGGERED;
      case "ok" -> Verdict.OK;
      case "unknown" -> Verdict.UNKNOWN;
      case "raises:TypeError" -> Verdict.UNEVALUATED;
      default -> throw new IllegalArgumentException(state);
    };
  }

  @Test
  void everyRowTheOriginalProduced() throws IOException {
    var rows = table();
    assertEquals(89, rows.size(), "the exported table changed size");

    var disagreements = new ArrayList<String>();
    for (Row row : rows) {
      var condition =
          new Condition(
              "value",
              row.op(),
              thresholdFor(row.threshold()),
              Selector.parse(row.selector().equals("absent") ? null : row.selector()));
      var got = AlertCondition.evaluate(condition, dataFor(row));
      if (got != expected(row.state())) {
        disagreements.add(
            "%s selector=%s value=%s op=%s threshold=%s: redash said %s, this said %s"
                .formatted(row.family(), row.selector(), row.valueKind(), row.op(), row.threshold(), row.state(), got));
      }
    }
    if (!disagreements.isEmpty()) {
      fail(disagreements.size() + " row(s) disagree with the original:\n" + String.join("\n", disagreements));
    }
  }

  // The rows above are exhaustive; the ones below name the four results that are easiest
  // to get backwards, so a failure says which rule broke rather than only how many rows.

  @Test
  void anAbsentSelectorIsFirstAndNotMinOrMax() {
    var data = new QueryResultData(List.of(cell(3), cell(9), cell(1)), List.of("value"));
    assertEquals(
        Verdict.OK, AlertCondition.evaluate(new Condition("value", ">", 5, Selector.parse(null)), data));
    assertEquals(
        Verdict.TRIGGERED, AlertCondition.evaluate(new Condition("value", ">", 5, Selector.MAX), data));
  }

  @Test
  void anUnorderedValueThatComparesFalseIsUnknownRatherThanOk() {
    var data = new QueryResultData(List.of(cell("alpha")), List.of("value"));
    assertEquals(
        Verdict.UNKNOWN, AlertCondition.evaluate(new Condition("value", ">", "beta", Selector.FIRST), data));
    assertEquals(
        Verdict.OK, AlertCondition.evaluate(new Condition("value", "==", "beta", Selector.FIRST), data));
  }

  @Test
  void anUnorderedValueAgainstANumericThresholdReachesNoStateAtAll() {
    var data = new QueryResultData(List.of(cell("alpha")), List.of("value"));
    assertEquals(
        Verdict.UNEVALUATED, AlertCondition.evaluate(new Condition("value", ">", 5, Selector.FIRST), data));
    assertEquals(
        Verdict.UNEVALUATED,
        AlertCondition.evaluate(new Condition("value", "greater than", 5, Selector.FIRST), data));
    // ...but an equality operator against the same threshold does answer
    assertEquals(
        Verdict.OK, AlertCondition.evaluate(new Condition("value", "==", 5, Selector.FIRST), data));
  }

  @Test
  void aNumericThresholdThatIsNotANumberLeavesTheAnswerUnknown() {
    var data = new QueryResultData(List.of(cell(10)), List.of("value"));
    assertEquals(
        Verdict.UNKNOWN, AlertCondition.evaluate(new Condition("value", ">", "alpha", Selector.FIRST), data));
  }

  @Test
  void everyOperatorTheOriginalShipsIsRecognised() {
    assertTrue(AlertCondition.OPERATORS.keySet().containsAll(
        List.of(">", ">=", "<", "<=", "==", "!=", "greater than", "less than", "equals")));
    assertEquals(9, AlertCondition.OPERATORS.size());
  }
}

package io.akka.redash.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * SPEC-001 R67 to R75 and R72 to R74, driven by the tables the original printed.
 *
 * <p>Every case in `probe_12_parameters.json` is replayed: the 41 validation cases, the 15
 * template shapes under both renderers, the six missing-name cases and the six safety
 * cases. Nothing is hand-written, so a rule the port gets subtly wrong fails here rather
 * than at step e.
 */
class ParametersTest {

  @Test
  @DisplayName("every validation case the original ran answers the same here")
  void validatesEveryDeclaredType() {
    var mismatches = new ArrayList<String>();
    for (Object entry : Oracle.rows("probe_12_parameters.json", "validation")) {
      var row = Json.asMap(entry);
      var label = String.valueOf(row.get("case"));
      var definition = row.get("definition");
      var schema = definition == null
          ? List.<Map<String, Object>>of()
          : List.of(Json.asMap(definition));

      // The one case whose parameter name is not `p` — a name the schema does not declare.
      var name = "name-not-in-schema".equals(label) ? "other" : "p";
      var values = new java.util.LinkedHashMap<String, Object>();
      values.put(name, row.get("value"));

      var applied = new Parameters("SELECT {{p}}", schema, null).apply(values);
      var expected = Json.asMap(row.get("result"));
      boolean expectedOk = Boolean.TRUE.equals(expected.get("ok"));

      if (applied instanceof Parameters.Applied.Ok ok) {
        if (!expectedOk) {
          mismatches.add(label + ": accepted, the original refused");
        } else if (!String.valueOf(expected.get("query")).equals(ok.text())) {
          mismatches.add(label + ": " + ok.text() + " != " + expected.get("query"));
        }
      } else if (applied instanceof Parameters.Applied.Invalid invalid) {
        if (expectedOk) {
          mismatches.add(label + ": refused, the original accepted");
        } else if (!String.valueOf(expected.get("message")).equals(invalid.message())) {
          mismatches.add(label + ": " + invalid.message() + " != " + expected.get("message"));
        }
      } else {
        mismatches.add(label + ": detached, which the original did not report");
      }
    }
    assertEquals(List.of(), mismatches);
  }

  @Test
  @DisplayName("a range parameter substitutes through its two names")
  void substitutesARange() {
    var recorded = Oracle.section("probe_12_parameters.json", "range_substitution");
    var schema = List.of(Json.map("name", "p", "type", "date-range"));
    var values = Json.map("p", Json.map("start", "2026-08-01", "end", "2026-08-26"));
    var applied = new Parameters("SELECT '{{p.start}}' AND '{{p.end}}'", schema, null).apply(values);
    assertEquals(recorded.get("query"),
        assertInstanceOf(Parameters.Applied.Ok.class, applied).text());
  }

  @Test
  @DisplayName("the names a template still needs are the ones the original collected")
  void collectsMissingNames() {
    var recorded = Oracle.section("probe_12_parameters.json", "missing_params");
    for (var entry : recorded.entrySet()) {
      var parts = entry.getKey().split(" << ", 2);
      var template = parts[0];
      var supplied = Json.asMap(Json.loads(parts[1]));
      var expected = new ArrayList<String>();
      for (Object name : Json.asList(entry.getValue())) {
        expected.add(String.valueOf(name));
      }
      var actual = new ArrayList<>(new Parameters(template, List.of(), null).missing(supplied));
      java.util.Collections.sort(actual);
      assertEquals(expected, actual, entry.getKey());
    }
  }

  @Test
  @DisplayName("safety is false exactly when a text parameter is declared")
  void reportsSafety() {
    var recorded = Oracle.section("probe_12_parameters.json", "is_safe");
    Map<String, List<Map<String, Object>>> schemas = Map.of(
        "no-parameters", List.of(),
        "text-parameter", List.of(Json.map("name", "p", "type", "text")),
        "number-parameter", List.of(Json.map("name", "p", "type", "number")),
        "text-pattern-parameter", List.of(Json.map("name", "p", "type", "text-pattern")),
        "enum-parameter", List.of(Json.map("name", "p", "type", "enum")),
        "mixed", List.of(Json.map("name", "p", "type", "number"),
            Json.map("name", "q", "type", "text")));
    for (var entry : schemas.entrySet()) {
      assertEquals(recorded.get(entry.getKey()),
          new Parameters("x", entry.getValue(), null).isSafe(), entry.getKey());
    }
  }

  @Test
  @DisplayName("every template shape renders the way the original renders it")
  void rendersEveryShape() {
    var recorded = Oracle.section("probe_12_parameters.json", "mustache");
    var mismatches = new ArrayList<String>();
    for (var entry : recorded.entrySet()) {
      var row = Json.asMap(entry.getValue());
      var template = String.valueOf(row.get("template"));
      var context = Json.asMap(row.get("context"));
      var plain = Mustache.render(template, context);
      var escaped = Mustache.renderEscaped(template, context);
      if (!String.valueOf(row.get("render")).equals(plain)) {
        mismatches.add(entry.getKey() + " plain: " + plain + " != " + row.get("render"));
      }
      if (!String.valueOf(row.get("render_escape")).equals(escaped)) {
        mismatches.add(entry.getKey() + " escaped: " + escaped + " != " + row.get("render_escape"));
      }
    }
    assertEquals(List.of(), mismatches);
  }

  @Test
  @DisplayName("a dropdown parameter refuses a value the named query does not offer")
  void checksDropdownValues() {
    var schema = List.of(Json.map("name", "p", "type", "query", "queryId", 7L));
    Parameters.Dropdowns offers = id -> id == 7 ? List.of("a", "b") : null;
    assertInstanceOf(Parameters.Applied.Ok.class,
        new Parameters("SELECT {{p}}", schema, offers).apply(Json.map("p", "a")));
    assertInstanceOf(Parameters.Applied.Invalid.class,
        new Parameters("SELECT {{p}}", schema, offers).apply(Json.map("p", "z")));
  }

  @Test
  @DisplayName("a dropdown query with no data source is a detached error, not an invalid value")
  void reportsADetachedDropdown() {
    var schema = List.of(Json.map("name", "p", "type", "query", "queryId", 9L));
    Parameters.Dropdowns none = id -> null;
    var applied = new Parameters("SELECT {{p}}", schema, none).apply(Json.map("p", "a"));
    var detached = assertInstanceOf(Parameters.Applied.Detached.class, applied);
    assertEquals(9, detached.queryId());
    assertTrue(detached.message().startsWith("This query is detached"));
  }

  @Test
  @DisplayName("a dropdown's two columns are read the way the original reads them")
  void readsDropdownColumns() {
    // A row carrying `name` and `value` uses them; a row carrying neither uses the first
    // column for both; the lookup is case-insensitive because the keys are lowered first.
    assertEquals(List.of(Json.map("name", "A", "value", "1")),
        Parameters.dropdownValues(List.of(Json.map("Name", "A", "Value", 1L)), "Other"));
    assertEquals(List.of(Json.map("name", "only", "value", "only")),
        Parameters.dropdownValues(List.of(Json.map("Label", "only")), "Label"));
  }
}

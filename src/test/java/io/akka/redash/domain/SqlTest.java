package io.akka.redash.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** SPEC-001 R79, R80 and R65, replayed from the tables the original printed. */
class SqlTest {

  @Test
  @DisplayName("every script the original split, splits the same way here")
  void splitsEveryShape() {
    var mismatches = new ArrayList<String>();
    for (Object entry : Oracle.rows("probe_13_sql.json", "split")) {
      var row = Json.asMap(entry);
      var script = String.valueOf(row.get("script"));
      var expected = new ArrayList<String>();
      for (Object statement : Json.asList(row.get("statements"))) {
        expected.add(String.valueOf(statement));
      }
      var actual = Sql.splitStatements(script);
      if (!expected.equals(actual)) {
        mismatches.add(quote(script) + ": " + actual + " != " + expected);
      }
      var combined = Sql.combineStatements(actual);
      if (!String.valueOf(row.get("combined")).equals(combined)) {
        mismatches.add(quote(script) + " combined: " + quote(combined)
            + " != " + quote(String.valueOf(row.get("combined"))));
      }
    }
    assertEquals(List.of(), mismatches);
  }

  @Test
  @DisplayName("every statement the original limited, limits the same way here")
  void appliesTheAutomaticLimit() {
    var mismatches = new ArrayList<String>();
    for (Object entry : Oracle.rows("probe_13_sql.json", "auto_limit_base")) {
      var row = Json.asMap(entry);
      var query = String.valueOf(row.get("query"));
      var off = Sql.applyAutoLimit(query, false, Sql.LimitStyle.TRAILING);
      var on = Sql.applyAutoLimit(query, true, Sql.LimitStyle.TRAILING);
      boolean eligible = Sql.isSelectWithoutLimit(query, Sql.LimitStyle.TRAILING);
      if (!String.valueOf(row.get("off")).equals(off)) {
        mismatches.add(quote(query) + " off: " + quote(off) + " != " + quote(String.valueOf(row.get("off"))));
      }
      if (!String.valueOf(row.get("on")).equals(on)) {
        mismatches.add(quote(query) + " on: " + quote(on) + " != " + quote(String.valueOf(row.get("on"))));
      }
      if (!row.get("is_select_no_limit").equals(eligible)) {
        mismatches.add(quote(query) + " eligible: " + eligible + " != " + row.get("is_select_no_limit"));
      }
    }
    assertEquals(List.of(), mismatches);
  }

  @Test
  @DisplayName("the two placements that are not a trailing LIMIT put it where the original does")
  void appliesTheOtherTwoPlacements() {
    var recorded = Oracle.rows("probe_13_sql.json", "auto_limit_variants");
    for (Object entry : recorded) {
      var row = Json.asMap(entry);
      var limitQuery = String.valueOf(row.get("limit_query"));
      var example = String.valueOf(row.get("example"));
      if (limitQuery.contains("TOP")) {
        assertEquals(example, Sql.applyAutoLimit("SELECT * FROM t", true, Sql.LimitStyle.TOP));
      } else if (limitQuery.contains("FETCH")) {
        assertEquals(example,
            Sql.applyAutoLimit("SELECT * FROM t", true, Sql.LimitStyle.FETCH_NEXT));
      }
    }
  }

  @Test
  @DisplayName("the limit keywords each placement reads are the ones the original declares")
  void readsTheDeclaredLimitKeywords() {
    for (Object entry : Oracle.rows("probe_13_sql.json", "auto_limit_variants")) {
      var row = Json.asMap(entry);
      var declared = new ArrayList<String>();
      for (Object keyword : Json.asList(row.get("limit_keywords"))) {
        declared.add(String.valueOf(keyword));
      }
      var style = switch (String.valueOf(row.get("limit_query")).strip()) {
        case "TOP 1000" -> Sql.LimitStyle.TOP;
        case "FETCH NEXT 1000 ROWS ONLY" -> Sql.LimitStyle.FETCH_NEXT;
        default -> Sql.LimitStyle.TRAILING;
      };
      assertEquals(declared, style.limitKeywords());
      assertEquals(String.valueOf(row.get("limit_query")), style.limitQuery());
      assertEquals(row.get("limit_after_select"), style.afterSelect());
    }
  }

  @Test
  @DisplayName("a statement already limited under one placement is still limited under another")
  void placementsReadTheirOwnKeywords() {
    // `LIMIT` is not one of the SQL Server placement's keywords, so a query carrying it is
    // still eligible there. That is the source's rule and not an accident of this port.
    assertEquals("SELECT TOP 1000 * FROM t LIMIT 5",
        Sql.applyAutoLimit("SELECT * FROM t LIMIT 5", true, Sql.LimitStyle.TOP));
    assertEquals("SELECT * FROM t TOP 5",
        Sql.applyAutoLimit("SELECT * FROM t TOP 5", true, Sql.LimitStyle.TRAILING)
            .replace(" LIMIT 1000", ""));
  }

  @Test
  @DisplayName("the formatter answers what the original answered")
  void formatsTheSameWay() {
    var recorded = Oracle.section("probe_13_sql.json", "format");
    var mismatches = new ArrayList<String>();
    for (var entry : recorded.entrySet()) {
      var actual = Sql.format(entry.getKey());
      if (!String.valueOf(entry.getValue()).equals(actual)) {
        mismatches.add(quote(entry.getKey()) + ": " + quote(actual)
            + " != " + quote(String.valueOf(entry.getValue())));
      }
    }
    assertEquals(List.of(), mismatches);
  }

  private static String quote(String text) {
    return "\"" + text.replace("\n", "\\n").replace("\t", "\\t") + "\"";
  }
}

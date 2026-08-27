package io.akka.redash.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The access matrix, the JSON encoding, the configuration container and the type guesser
 * (SPEC-001 R22, R23, R41, R42, R90, R93), all replayed from what the original answered.
 */
class RulesTest {

  // ------------------------------------------------------------------ access

  @Test
  @DisplayName("all forty group cases the original ran answer the same here")
  void decidesGroupAccessTheSameWay() {
    Map<String, Map<Long, Boolean>> groupShapes = Map.of(
        "all-view-only", Map.of(1L, true, 2L, true),
        "mixed", Map.of(1L, true, 2L, false),
        "none-view-only", Map.of(1L, false, 2L, false),
        "empty", Map.of());
    Map<String, List<String>> permissions = Map.of(
        "admin", List.of("admin"),
        "member-of-1", List.of("view_query"),
        "member-of-2", List.of("view_query"),
        "member-of-both", List.of("view_query"),
        "member-of-none", List.of("view_query"));
    Map<String, List<Long>> memberships = Map.of(
        "admin", List.of(1L),
        "member-of-1", List.of(1L),
        "member-of-2", List.of(2L),
        "member-of-both", List.of(1L, 2L),
        "member-of-none", List.of(9L));

    var mismatches = new ArrayList<String>();
    for (Object entry : Oracle.rows("probe_14_rules.json", "has_access")) {
      var row = Json.asMap(entry);
      var groups = groupShapes.get(String.valueOf(row.get("groups")));
      var user = String.valueOf(row.get("user"));
      boolean needViewOnly = "view".equals(row.get("wanted"));
      boolean allowed = Access.hasAccessToGroups(
          groups, permissions.get(user), memberships.get(user), needViewOnly);
      if (!row.get("allowed").equals(allowed)) {
        mismatches.add(row.get("groups") + "/" + user + "/" + row.get("wanted")
            + ": " + allowed + " != " + row.get("allowed"));
      }
    }
    assertEquals(List.of(), mismatches);
  }

  @Test
  @DisplayName("all ten API-key cases the original ran answer the same here")
  void decidesKeyAccessTheSameWay() {
    var mismatches = new ArrayList<String>();
    for (Object entry : Oracle.rows("probe_14_rules.json", "has_access_api_key")) {
      var row = Json.asMap(entry);
      var label = String.valueOf(row.get("key"));
      boolean withoutDashboards = label.endsWith("/no-dashboards");
      var key = withoutDashboards ? label.substring(0, label.indexOf('/')) : label;
      boolean needViewOnly = "view".equals(row.get("wanted"));
      boolean allowed = Access.hasAccessToObject(
          "OWNKEY", withoutDashboards ? List.of() : List.of("DASHKEY"), key, needViewOnly);
      if (!row.get("allowed").equals(allowed)) {
        mismatches.add(label + "/" + row.get("wanted") + ": " + allowed + " != "
            + row.get("allowed"));
      }
    }
    assertEquals(List.of(), mismatches);
  }

  // ------------------------------------------------------------------ JSON

  @Test
  @DisplayName("every value the original's encoder has a branch for encodes the same here")
  void encodesEveryValueTheSameWay() {
    Map<String, Object> values = new LinkedHashMap<>();
    values.put("decimal", new BigDecimal("1.25"));
    values.put("uuid", UUID.fromString("12345678-1234-5678-1234-567812345678"));
    // The original's `datetime-naive` and `datetime-micro` carry no zone; the JVM's
    // equivalent is a LocalDateTime, and it must not acquire a trailing marker here.
    values.put("datetime-naive", java.time.LocalDateTime.of(2026, 8, 26, 13, 45, 1));
    values.put("datetime-micro",
        java.time.LocalDateTime.of(2026, 8, 26, 13, 45, 1, 123456000));
    values.put("datetime-utc", Instant.parse("2026-08-26T13:45:01Z"));
    values.put("datetime-offset",
        OffsetDateTime.of(2026, 8, 26, 13, 45, 1, 0, ZoneOffset.ofHours(2)));
    values.put("date", LocalDate.of(2026, 8, 26));
    values.put("time", LocalTime.of(13, 45, 1));
    values.put("time-micro", LocalTime.of(13, 45, 1, 123456000));
    values.put("bytes", new byte[] {0, 1, (byte) 0xff});
    values.put("nan", Double.NaN);
    values.put("inf", Double.POSITIVE_INFINITY);
    values.put("-inf", Double.NEGATIVE_INFINITY);
    values.put("nested-nan", Json.map("a", List.of(Double.NaN, 1L)));
    values.put("unicode", "café 中");
    values.put("true", true);
    values.put("none", null);

    var recorded = Oracle.section("probe_14_rules.json", "json_dumps");
    var mismatches = new ArrayList<String>();
    for (var entry : values.entrySet()) {
      // The original writes a list's NaN as null too, so the nested case is compared whole.
      var encoded = Json.dumps(Json.map("v", entry.getValue()));
      var expected = String.valueOf(recorded.get(entry.getKey()));
      if (!expected.equals(encoded)) {
        mismatches.add(entry.getKey() + ": " + encoded + " != " + expected);
      }
    }
    assertEquals(List.of(), mismatches);
  }

  // ------------------------------------------------------------------ configuration

  @Test
  @DisplayName("the eight configuration cases the original ran validate the same here")
  void validatesTheSameWay() {
    var schema = Json.map(
        "type", "object",
        "properties", Json.map(
            "host", Json.map("type", "string"),
            "port", Json.map("type", "number"),
            "flag", Json.map("type", "boolean"),
            "mode", Json.map("type", "string", "extendedEnum",
                List.of(Json.map("value", "a", "name", "A"), Json.map("value", "b", "name", "B")))),
        "required", List.of("host"),
        "secret", List.of("password"));

    Map<String, Map<String, Object>> documents = new LinkedHashMap<>();
    documents.put("valid", Json.map("host", "h"));
    documents.put("missing-required", Json.map("port", 1L));
    documents.put("wrong-type", Json.map("host", 1L));
    documents.put("extra-property", Json.map("host", "h", "unknown", 1L));
    documents.put("enum-member", Json.map("host", "h", "mode", "a"));
    documents.put("enum-non-member", Json.map("host", "h", "mode", "z"));
    documents.put("number-as-string", Json.map("host", "h", "port", "1"));
    documents.put("bool", Json.map("host", "h", "flag", true));

    var recorded = Oracle.section("probe_15_internals.json", "configuration_valid");
    for (var entry : documents.entrySet()) {
      assertEquals(recorded.get(entry.getKey()),
          Configuration.isValid(entry.getValue(), schema), entry.getKey());
    }
  }

  @Test
  @DisplayName("a secret masks, survives the placeholder, and is replaced by a real value")
  void handlesSecretsTheSameWay() {
    var schema = Json.map(
        "type", "object",
        "properties", Json.map("user", Json.map("type", "string"),
            "password", Json.map("type", "string")),
        "secret", List.of("password"));
    var recorded = Oracle.section("probe_15_internals.json", "configuration_secrets");

    var stored = Json.map("user", "u", "password", "p");
    assertEquals(recorded.get("plain"), stored);
    assertEquals(recorded.get("masked"), Configuration.mask(stored, schema));

    var afterPlaceholder = Configuration.merge(stored,
        Json.map("user", "u2", "password", Configuration.SECRET_PLACEHOLDER), schema);
    assertEquals(recorded.get("after_placeholder_update"), afterPlaceholder);

    var afterReal = Configuration.merge(afterPlaceholder,
        Json.map("user", "u3", "password", "new"), schema);
    assertEquals(recorded.get("after_real_update"), afterReal);
  }

  // ------------------------------------------------------------------ result shaping

  @Test
  @DisplayName("a repeated column name is numbered the way the original numbers it")
  void numbersRepeatedColumns() {
    var recorded = Oracle.rows("probe_15_internals.json", "fetch_columns");
    var columns = RunColumns.of();
    assertEquals(recorded, columns);
  }

  @Test
  @DisplayName("every value's type is guessed the way the original guesses it")
  void guessesEveryType() {
    var recorded = Oracle.section("probe_15_internals.json", "guess_type");
    Map<String, Object> values = new LinkedHashMap<>();
    values.put("True", true);
    values.put("False", false);
    values.put("1", 1L);
    values.put("1.5", 1.5d);
    values.put("'1'", "1");
    values.put("'1.5'", "1.5");
    values.put("'true'", "true");
    values.put("'TRUE'", "TRUE");
    values.put("'2026-08-26'", "2026-08-26");
    values.put("'2026-08-26T13:45:00'", "2026-08-26T13:45:00");
    values.put("'hello'", "hello");
    values.put("''", "");
    values.put("None", null);
    values.put("'1e5'", "1e5");
    values.put("'0x10'", "0x10");
    values.put("'-3'", "-3");
    var mismatches = new ArrayList<String>();
    for (var entry : values.entrySet()) {
      var guessed = Numbers.guessType(entry.getValue());
      if (!String.valueOf(recorded.get(entry.getKey())).equals(guessed)) {
        mismatches.add(entry.getKey() + ": " + guessed + " != " + recorded.get(entry.getKey()));
      }
    }
    var fromStrings = Oracle.section("probe_15_internals.json", "guess_type_from_string");
    Map<String, String> strings = new LinkedHashMap<>();
    strings.put("''", "");
    strings.put("'007'", "007");
    strings.put("'1_000'", "1_000");
    strings.put("'nan'", "nan");
    strings.put("'inf'", "inf");
    for (var entry : strings.entrySet()) {
      var guessed = Numbers.guessTypeFromString(entry.getValue());
      if (!String.valueOf(fromStrings.get(entry.getKey())).equals(guessed)) {
        mismatches.add(entry.getKey() + ": " + guessed + " != " + fromStrings.get(entry.getKey()));
      }
    }
    assertEquals(List.of(), mismatches);
  }

  @Test
  @DisplayName("the annotation is the original's, including the empty one")
  void annotatesTheSameWay() {
    var recorded = Oracle.section("probe_15_internals.json", "annotate_query");
    var type = io.akka.redash.queryrunner.Registry.get("pg");
    assertEquals(recorded.get("empty"), type.annotate("SELECT 1", Map.of()));
    assertEquals(recorded.get("one"), type.annotate("SELECT 1", Json.map("Username", "a")));
    var metadata = new LinkedHashMap<String, Object>();
    metadata.put("Username", "a");
    metadata.put("query_id", 3L);
    metadata.put("Job ID", "j");
    metadata.put("Scheduled", false);
    assertEquals(recorded.get("many"), type.annotate("SELECT 1", metadata));
  }

  @Test
  @DisplayName("a runner that declares no annotation leaves the statement alone")
  void leavesUnannotatedRunnersAlone() {
    assertEquals("SELECT 1",
        io.akka.redash.queryrunner.Registry.get("mssql").annotate("SELECT 1",
            Json.map("Username", "a")));
  }

  @Test
  @DisplayName("the settings parser accepts and refuses exactly what the original does")
  void parsesBooleansTheSameWay() {
    var recorded = Oracle.section("probe_15_internals.json", "parse_boolean");
    for (var entry : recorded.entrySet()) {
      var text = entry.getKey().substring(1, entry.getKey().length() - 1);
      var expected = entry.getValue();
      if (expected instanceof Boolean bool) {
        assertEquals(bool, Settings.parseBoolean(text), entry.getKey());
      } else {
        var thrown = false;
        try {
          Settings.parseBoolean(text);
        } catch (IllegalArgumentException e) {
          thrown = true;
        }
        assertTrue(thrown, entry.getKey() + " should have been refused");
      }
    }
  }

  @Test
  @DisplayName("a comma-separated setting splits without trimming")
  void splitsListsTheSameWay() {
    var recorded = Oracle.section("probe_15_internals.json", "array_from_string");
    for (var entry : recorded.entrySet()) {
      var text = entry.getKey().substring(1, entry.getKey().length() - 1);
      var expected = new ArrayList<String>();
      for (Object value : Json.asList(entry.getValue())) {
        expected.add(String.valueOf(value));
      }
      assertEquals(expected, Settings.arrayFromString(text), entry.getKey());
    }
  }

  @Test
  @DisplayName("the slug rule keeps what the original keeps")
  void slugifiesTheSameWay() {
    var recorded = Oracle.section("probe_10_crypto.json", "slugify");
    for (var entry : recorded.entrySet()) {
      assertEquals(entry.getValue(), Text.slugify(entry.getKey()), entry.getKey());
    }
  }

  @Test
  @DisplayName("a download filename strips control characters and the unsafe set")
  void buildsDownloadFilenames() {
    assertEquals("a_b_2026_08_26.csv",
        Text.downloadFilename("a/b", 3L, null, "2026_08_26", "csv"));
    assertEquals("3_2026_08_26.csv",
        Text.downloadFilename("\0", 3L, null, "2026_08_26", "csv"));
    assertEquals("17_2026_08_26.json",
        Text.downloadFilename(null, null, "17", "2026_08_26", "json"));
    assertFalse(Text.toFilename("  spaced  ").startsWith("_"));
  }

  /** The four columns the original's own probe asked for, named once so the test reads. */
  private static final class RunColumns {
    static List<Map<String, Object>> of() {
      return io.akka.redash.queryrunner.RunResult.fetchColumns(
          List.of("a", "a", "a", "b"),
          List.of("integer", "string", "string", "float"));
    }
  }
}

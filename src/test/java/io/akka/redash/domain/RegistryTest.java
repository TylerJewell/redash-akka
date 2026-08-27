package io.akka.redash.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.akka.redash.destinations.Destinations;
import io.akka.redash.queryrunner.Registry;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The seventy-five data source types and the twelve destinations, against the original's
 * own registries (SPEC-001 R3, R39, R40).
 *
 * <p>These are wire formats, not lists: the front end draws every data source form and
 * every destination form from the configuration schema in these documents, so a field with
 * the wrong title or a missing default is a form the person cannot fill in. Each schema is
 * therefore compared whole rather than by spot check.
 */
class RegistryTest {

  @Test
  void registersEveryTypeTheOriginalRegisters() {
    var recorded = Oracle.rows("probe_11_registries.json", "registered_query_runner_types");
    var expected = new ArrayList<String>();
    for (Object type : recorded) {
      expected.add(String.valueOf(type));
    }
    var actual = new ArrayList<>(Registry.all().keySet());
    java.util.Collections.sort(expected);
    java.util.Collections.sort(actual);
    assertEquals(expected, actual, "the registered data source types");
  }

  @Test
  void answersEveryDataSourceSchemaTheOriginalDoes() {
    var recorded = Oracle.section("probe_11_registries.json", "query_runners");
    var mismatched = new ArrayList<String>();
    for (var entry : recorded.entrySet()) {
      var declared = Json.asMap(entry.getValue());
      var type = Registry.get(String.valueOf(declared.get("type")));
      if (type == null) {
        mismatched.add(declared.get("type") + ": not registered");
        continue;
      }
      var mine = canonical(type.configurationSchema());
      var theirs = canonical(declared.get("configuration_schema"));
      if (!mine.equals(theirs)) {
        mismatched.add(declared.get("type") + ":\n  original " + theirs + "\n  port     " + mine);
      }
      if (!String.valueOf(declared.get("name")).equals(type.name())) {
        mismatched.add(declared.get("type") + ": name " + declared.get("name")
            + " against " + type.name());
      }
    }
    assertTrue(mismatched.isEmpty(), String.join("\n", mismatched));
  }

  @Test
  void carriesEveryRunnerTraitTheOriginalCarries() {
    var recorded = Oracle.section("probe_11_registries.json", "query_runner_traits");
    var mismatched = new ArrayList<String>();
    for (var entry : recorded.entrySet()) {
      var traits = Json.asMap(entry.getValue());
      var name = String.valueOf(traits.get("class"));
      var type = Registry.get(typeOf(recorded, name));
      if (type == null) {
        continue;
      }
      if (Boolean.TRUE.equals(traits.get("should_annotate_query")) != type.shouldAnnotate()) {
        mismatched.add(name + ": should_annotate_query");
      }
      if (Boolean.TRUE.equals(traits.get("supports_auto_limit")) != type.supportsAutoLimit()) {
        mismatched.add(name + ": supports_auto_limit");
      }
      if (Boolean.TRUE.equals(traits.get("deprecated")) != type.deprecated()) {
        mismatched.add(name + ": deprecated");
      }
      var syntax = traits.get("syntax");
      if (syntax != null && !String.valueOf(syntax).equals(type.syntax())) {
        mismatched.add(name + ": syntax " + syntax + " against " + type.syntax());
      }
    }
    assertTrue(mismatched.isEmpty(), String.join("\n", mismatched));
  }

  @Test
  void registersEveryDestinationTheOriginalRegisters() {
    var recorded = Oracle.rows("probe_11_registries.json", "registered_destination_types");
    var expected = new ArrayList<String>();
    for (Object type : recorded) {
      expected.add(String.valueOf(type));
    }
    var actual = new ArrayList<>(Destinations.all().keySet());
    java.util.Collections.sort(expected);
    java.util.Collections.sort(actual);
    // Membership only: the probe that recorded this list sorted it, so the order it holds
    // is the probe's. The order a caller actually sees is compared against the running
    // original by walk step 113 in `bench/REPORT.md`.
    assertEquals(expected, actual, "the registered destination types");
  }

  @Test
  void answersEveryDestinationSchemaTheOriginalDoes() {
    var recorded = Oracle.section("probe_11_registries.json", "destinations");
    var mismatched = new ArrayList<String>();
    for (var entry : recorded.entrySet()) {
      var declared = Json.asMap(entry.getValue());
      var type = Destinations.get(String.valueOf(declared.get("type")));
      if (type == null) {
        mismatched.add(declared.get("type") + ": not registered");
        continue;
      }
      var mine = canonical(type.configurationSchema());
      var theirs = canonical(declared.get("configuration_schema"));
      if (!mine.equals(theirs)) {
        mismatched.add(declared.get("type") + ":\n  original " + theirs + "\n  port     " + mine);
      }
      if (!String.valueOf(declared.get("icon")).equals(type.icon())) {
        mismatched.add(declared.get("type") + ": icon " + declared.get("icon")
            + " against " + type.icon());
      }
    }
    assertTrue(mismatched.isEmpty(), String.join("\n", mismatched));
  }

  /**
   * A schema as a document rather than as bytes.
   *
   * <p>The two sides put the *top-level* keys in a different order — the original writes
   * `required, extra_options, order, secret` where this writes
   * `order, required, secret, extra_options` — and nothing reads a schema by the position
   * of those four. What is inside them is compared exactly, **including the order of
   * `properties`**, because a schema with no `order` list is drawn as a form in the order
   * its properties appear.
   */
  private static String canonical(Object schema) {
    if (schema instanceof Map<?, ?> document) {
      var sorted = new java.util.TreeMap<String, Object>(Json.asMap(document));
      return Json.dumps(sorted);
    }
    return Json.dumps(schema);
  }

  /** The type name for a runner class, read out of the recorded registry. */
  private static String typeOf(Map<String, Object> traits, String className) {
    var runners = Oracle.section("probe_11_registries.json", "query_runners");
    for (var entry : runners.entrySet()) {
      if (entry.getKey().equals(className)) {
        return String.valueOf(Json.asMap(entry.getValue()).get("type"));
      }
    }
    return className;
  }

  /** Kept so a reader can see what the two registries hold without running anything. */
  static List<String> registeredTypes() {
    return List.copyOf(Registry.all().keySet());
  }
}

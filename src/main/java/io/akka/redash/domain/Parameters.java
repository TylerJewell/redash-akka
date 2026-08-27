package io.akka.redash.domain;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * A query's parameters: what is accepted, what is substituted, and what may be run by
 * somebody with read-only access (SPEC-001 R67 to R75).
 *
 * <p>The validator is a table from declared type to a predicate, and a type the table does
 * not hold is refused — which is why a typo in a parameter's `type` makes the query
 * unrunnable rather than unvalidated. A parameter whose name the schema does not declare is
 * refused as well, and the message names every offending parameter at once rather than the
 * first, because a form submits all of them together.
 */
public final class Parameters {

  /** A query's text with its parameters applied, or the reason it could not be. */
  public sealed interface Applied {
    record Ok(String text, List<String> missing) implements Applied {}

    /** Names, in the order the parameter map yielded them, and the source's own wording. */
    record Invalid(List<String> names) implements Applied {
      public String message() {
        return "The following parameter values are incompatible with their definitions: "
            + String.join(", ", names);
      }
    }

    /** A dropdown parameter naming a query that has no data source to read options from. */
    record Detached(long queryId) implements Applied {
      public String message() {
        return "This query is detached from any data source. Please select a different query.";
      }
    }
  }

  /** How a dropdown parameter's options are found, so the validator does not need a store. */
  public interface Dropdowns {
    /** The `value` of each option, or null when the named query has no data source. */
    List<String> valuesFor(long queryId);
  }

  private final String template;
  private final List<Map<String, Object>> schema;
  private final Dropdowns dropdowns;

  public Parameters(String template, List<Map<String, Object>> schema, Dropdowns dropdowns) {
    this.template = template == null ? "" : template;
    this.schema = schema == null ? List.of() : schema;
    this.dropdowns = dropdowns;
  }

  /** True exactly when the schema declares no `text` parameter. */
  public boolean isSafe() {
    return schema.stream().noneMatch(p -> "text".equals(p.get("type")));
  }

  /** The names the template needs that the supplied values do not provide. */
  public List<String> missing(Map<String, Object> supplied) {
    var wanted = new ArrayList<>(Mustache.keys(template));
    wanted.removeAll(Mustache.suppliedNames(supplied));
    return wanted;
  }

  public Applied apply(Map<String, Object> values) {
    var offending = new ArrayList<String>();
    for (var entry : values.entrySet()) {
      try {
        if (!valid(entry.getKey(), entry.getValue())) {
          offending.add(entry.getKey());
        }
      } catch (DetachedException e) {
        return new Applied.Detached(e.queryId);
      }
    }
    if (!offending.isEmpty()) {
      return new Applied.Invalid(offending);
    }
    var joined = joinListValues(values);
    var text = Mustache.render(template, joined);
    return new Applied.Ok(text, missing(values));
  }

  /** A list value becomes one string, wrapped and joined the way the parameter declares. */
  Map<String, Object> joinListValues(Map<String, Object> values) {
    var out = new LinkedHashMap<String, Object>();
    for (var entry : values.entrySet()) {
      if (!(entry.getValue() instanceof List<?> list)) {
        out.put(entry.getKey(), entry.getValue());
        continue;
      }
      var definition = definitionFor(entry.getKey());
      var multi = Json.asMap(definition == null ? null : definition.get("multiValuesOptions"));
      var separator = String.valueOf(multi.getOrDefault("separator", ","));
      var prefix = String.valueOf(multi.getOrDefault("prefix", ""));
      var suffix = String.valueOf(multi.getOrDefault("suffix", ""));
      var joined = new StringBuilder();
      for (int i = 0; i < list.size(); i++) {
        joined.append(i == 0 ? "" : separator).append(prefix)
            .append(Mustache.pythonString(list.get(i))).append(suffix);
      }
      out.put(entry.getKey(), joined.toString());
    }
    return out;
  }

  Map<String, Object> definitionFor(String name) {
    for (var definition : schema) {
      if (name.equals(definition.get("name"))) {
        return definition;
      }
    }
    return null;
  }

  /** Raised through the validator so a detached dropdown is not flattened into "invalid". */
  static final class DetachedException extends RuntimeException {
    final long queryId;

    DetachedException(long queryId) {
      super("detached", null, false, false);
      this.queryId = queryId;
    }
  }

  boolean valid(String name, Object value) {
    if (schema.isEmpty()) {
      return true;
    }
    var definition = definitionFor(name);
    if (definition == null) {
      return false;
    }
    var type = String.valueOf(definition.get("type"));
    boolean allowsList = definition.get("multiValuesOptions") instanceof Map;

    try {
      return switch (type) {
        case "text" -> value instanceof CharSequence;
        case "text-pattern" -> matchesPattern(value, definition.get("regex"));
        case "number" -> isNumber(value);
        case "enum" -> withinOptions(value, enumOptions(definition), allowsList);
        case "query" -> withinOptions(value, queryOptions(definition), allowsList);
        case "date", "datetime-local", "datetime-with-seconds" -> isDate(value);
        case "date-range", "datetime-range", "datetime-range-with-seconds" -> isRange(value);
        default -> false;
      };
    } catch (DetachedException e) {
      throw e;
    } catch (RuntimeException e) {
      // Every other failure inside a validator is an invalid value, which is what the
      // source's own blanket catch does.
      return false;
    }
  }

  static boolean isNumber(Object value) {
    if (value instanceof Number || value instanceof Boolean) {
      return true;
    }
    return value instanceof CharSequence text && Numbers.isPythonFloat(text.toString());
  }

  static boolean matchesPattern(Object value, Object regex) {
    if (!(value instanceof CharSequence text) || regex == null) {
      return false;
    }
    try {
      return Pattern.compile(String.valueOf(regex)).matcher(text).matches();
    } catch (PatternSyntaxException e) {
      return false;
    }
  }

  static boolean isDate(Object value) {
    return value instanceof CharSequence text && Numbers.looksLikeADate(text.toString());
  }

  static boolean isRange(Object value) {
    if (!(value instanceof Map<?, ?> map)) {
      return false;
    }
    return isDate(map.get("start")) && isDate(map.get("end"));
  }

  static List<String> enumOptions(Map<String, Object> definition) {
    var declared = definition.get("enumOptions");
    if (declared instanceof CharSequence text) {
      return List.of(text.toString().split("\n"));
    }
    var out = new ArrayList<String>();
    for (Object option : Json.asList(declared)) {
      out.add(String.valueOf(option));
    }
    return out;
  }

  List<String> queryOptions(Map<String, Object> definition) {
    var queryId = Numbers.toDouble(definition.get("queryId"));
    if (queryId == null || dropdowns == null) {
      return List.of();
    }
    var values = dropdowns.valuesFor(queryId.longValue());
    if (values == null) {
      throw new DetachedException(queryId.longValue());
    }
    return values;
  }

  /** A list is accepted only when the parameter declares it, and every member must be valid. */
  static boolean withinOptions(Object value, List<String> options, boolean allowsList) {
    if (value instanceof List<?> list) {
      if (!allowsList) {
        return false;
      }
      for (Object item : list) {
        if (!options.contains(Mustache.pythonString(item))) {
          return false;
        }
      }
      return true;
    }
    return options.contains(Mustache.pythonString(value));
  }

  /**
   * The two columns a dropdown query's rows are read through: a column literally called
   * `name` or `value` wins, and otherwise the first column stands in for both. The lookup
   * is case-insensitive because the row keys are lower-cased first.
   */
  public static List<Map<String, Object>> dropdownValues(List<Map<String, Object>> rows,
      String firstColumnName) {
    var out = new ArrayList<Map<String, Object>>();
    Function<Map<String, Object>, Map<String, Object>> lower =
        row -> {
          var copy = new LinkedHashMap<String, Object>();
          row.forEach((k, v) -> copy.put(k.toLowerCase(Locale.ROOT), v));
          return copy;
        };
    var fallback = firstColumnName == null ? "" : firstColumnName.toLowerCase(Locale.ROOT);
    for (var row : rows) {
      var lowered = lower.apply(row);
      var nameColumn = lowered.containsKey("name") ? "name" : fallback;
      var valueColumn = lowered.containsKey("value") ? "value" : fallback;
      out.add(Json.map(
          "name", lowered.get(nameColumn),
          "value", Mustache.pythonString(lowered.get(valueColumn))));
    }
    return out;
  }
}

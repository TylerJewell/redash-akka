package io.akka.redash.domain;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A data source's or a destination's options, and the schema that decides what they may be
 * (SPEC-001 R41, R42).
 *
 * <p>Three behaviours here are load-bearing and none of them is obvious. A property the
 * schema does not mention is **accepted**, because the schema does not forbid additional
 * properties. An {@code extendedEnum} — which the front end uses to draw a labelled
 * dropdown — is rewritten to a plain {@code enum} of its values before validation, so the
 * labels are never checked against. And a property named in {@code secret} is read back as
 * eight dashes and, when those eight dashes are written back, the stored value survives —
 * which is how a form can be re-submitted without the password being retyped or erased.
 */
public final class Configuration {

  public static final String SECRET_PLACEHOLDER = "--------";

  private Configuration() {}

  /** What a validation failure was, so a caller can say more than "no". */
  public record Problem(String property, String reason) {}

  /**
   * Validate a document against one of redash's configuration schemas.
   *
   * @return the problems found, empty when the document is acceptable
   */
  public static List<Problem> validate(Map<String, Object> document, Map<String, Object> schema) {
    var problems = new ArrayList<Problem>();
    if (schema == null) {
      return problems;
    }
    var properties = Json.asMap(schema.get("properties"));
    for (Object required : Json.asList(schema.get("required"))) {
      var name = String.valueOf(required);
      if (!document.containsKey(name) || document.get(name) == null) {
        problems.add(new Problem(name, "is a required property"));
      }
    }
    for (var entry : document.entrySet()) {
      var declared = properties.get(entry.getKey());
      if (!(declared instanceof Map<?, ?>)) {
        continue;
      }
      var property = Json.asMap(declared);
      var value = entry.getValue();
      var type = property.get("type");
      if (type != null && !matchesType(value, String.valueOf(type))) {
        problems.add(new Problem(entry.getKey(), "is not of type " + type));
        continue;
      }
      var allowed = allowedValues(property);
      if (allowed != null && !allowed.contains(value)) {
        problems.add(new Problem(entry.getKey(), "is not one of " + allowed));
      }
    }
    return problems;
  }

  /** The values an enum property accepts, with an extendedEnum reduced to its values. */
  static List<Object> allowedValues(Map<String, Object> property) {
    if (property.get("extendedEnum") instanceof List<?> extended) {
      var out = new ArrayList<>();
      for (Object option : extended) {
        out.add(Json.asMap(option).get("value"));
      }
      return out;
    }
    if (property.get("enum") instanceof List<?> plain) {
      return new ArrayList<Object>(plain);
    }
    return null;
  }

  static boolean matchesType(Object value, String type) {
    if (value == null) {
      return true;
    }
    return switch (type) {
      // JSON Schema's `number` covers integers and, unlike Python's own truth, does not
      // cover a string that happens to hold digits.
      case "number" -> value instanceof Number && !(value instanceof Boolean);
      case "integer" -> value instanceof Integer || value instanceof Long;
      case "boolean" -> value instanceof Boolean;
      case "string" -> value instanceof CharSequence;
      case "object" -> value instanceof Map;
      case "array" -> value instanceof List;
      default -> true;
    };
  }

  public static boolean isValid(Map<String, Object> document, Map<String, Object> schema) {
    return validate(document, schema).isEmpty();
  }

  /**
   * The secret property names a schema declares, or an empty list when it declares none.
   *
   * <p>One of the source's twelve destinations declares this as a bare string rather than
   * a list — `mattermost`'s is `"url"` — and the source's membership test is Python's `in`,
   * which reads a string as a substring test. So a name the string *contains* is masked,
   * which for that schema is `url` and also, in principle, `u`, `r`, `l`, `ur` and `rl`.
   * The declaration is carried through as the string it is, and the test below is the same
   * test the source makes, so both the wire format and the masking agree with it.
   */
  public static List<String> secrets(Map<String, Object> schema) {
    var out = new ArrayList<String>();
    var declared = schema == null ? null : schema.get("secret");
    if (declared instanceof CharSequence text) {
      out.add(text.toString());
      return out;
    }
    for (Object name : Json.asList(declared)) {
      out.add(String.valueOf(name));
    }
    return out;
  }

  /** Whether a property is one of the secrets, the way the source asks it. */
  static boolean isSecret(Map<String, Object> schema, String property) {
    var declared = schema == null ? null : schema.get("secret");
    if (declared instanceof CharSequence text) {
      return text.toString().contains(property);
    }
    return secrets(schema).contains(property);
  }

  /** What a caller is allowed to read back. */
  public static Map<String, Object> mask(Map<String, Object> document, Map<String, Object> schema) {
    if (schema == null || schema.get("secret") == null) {
      return document;
    }
    var out = new LinkedHashMap<String, Object>();
    document.forEach((key, value) ->
        out.put(key, isSecret(schema, key) ? SECRET_PLACEHOLDER : value));
    return out;
  }

  /**
   * Merge an update over what is stored. The whole document is replaced — a property left
   * out of the update is dropped, not kept — except that a secret written back as the
   * placeholder keeps the value that was already there.
   */
  public static Map<String, Object> merge(Map<String, Object> stored,
      Map<String, Object> update, Map<String, Object> schema) {
    var out = new LinkedHashMap<String, Object>();
    for (var entry : update.entrySet()) {
      if (isSecret(schema, entry.getKey()) && SECRET_PLACEHOLDER.equals(entry.getValue())) {
        out.put(entry.getKey(), stored.get(entry.getKey()));
      } else {
        out.put(entry.getKey(), entry.getValue());
      }
    }
    return out;
  }

  /** Drop the properties whose value is null, which is what a data source write does first. */
  public static Map<String, Object> withoutNulls(Map<String, Object> document) {
    var out = new LinkedHashMap<String, Object>();
    for (var entry : document.entrySet()) {
      if (entry.getValue() != null) {
        out.put(entry.getKey(), entry.getValue());
      }
    }
    return out;
  }
}

package io.akka.redash.application;

import io.akka.redash.domain.Json;
import io.akka.redash.domain.Mustache;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The subject and body an alert notification carries (SPEC-001 R142).
 *
 * <p>Both are Mustache templates rendered with escaping on, over a context of twelve names
 * the original defines. A template that is absent renders as the empty string rather than
 * as an error, and the caller then falls back to its own default — which is why an alert
 * with no custom body still sends the standard one.
 */
public final class AlertTemplates {

  /** The two rendered strings, plus the default body in case both are empty. */
  public record Rendered(String subject, String body, String defaultBody) {}

  private AlertTemplates() {}

  public static Rendered render(Map<String, Object> alert, Map<String, Object> query,
      Map<String, Object> result, String host, String state, String defaultBodyTemplate) {
    var context = context(alert, query, result, host, state);
    var options = Json.asMap(alert.get("options"));
    var body = options.get("custom_body") != null
        ? options.get("custom_body") : options.get("template");
    return new Rendered(
        render(options.get("custom_subject"), context),
        render(body, context),
        render(defaultBodyTemplate, context));
  }

  static String render(Object template, Map<String, Object> context) {
    if (template == null) {
      return "";
    }
    return Mustache.renderEscaped(String.valueOf(template), context);
  }

  /**
   * The twelve names a template may use. `QUERY_RESULT_TABLE` is the rows as a
   * two-dimensional array in column order, which is what a Mustache section can iterate;
   * the map form cannot be, because Mustache has no way to name a column.
   */
  static Map<String, Object> context(Map<String, Object> alert, Map<String, Object> query,
      Map<String, Object> result, String host, String state) {
    var options = Json.asMap(alert.get("options"));
    var data = result == null ? Map.<String, Object>of() : Json.asMap(result.get("data"));
    var columns = new ArrayList<String>();
    for (Object column : Json.asList(data.get("columns"))) {
      columns.add(String.valueOf(Json.asMap(column).get("name")));
    }
    var rows = new ArrayList<Map<String, Object>>();
    for (Object row : Json.asList(data.get("rows"))) {
      rows.add(Json.asMap(row));
    }
    var table = new ArrayList<List<Object>>(rows.size());
    for (Map<String, Object> row : rows) {
      var line = new ArrayList<>(columns.size());
      for (String column : columns) {
        line.add(row.get(column));
      }
      table.add(line);
    }
    var column = String.valueOf(options.get("column"));
    Object value = rows.isEmpty() ? null : rows.get(0).get(column);

    var out = new LinkedHashMap<String, Object>();
    out.put("ALERT_NAME", alert.get("name"));
    out.put("ALERT_URL", host + "/alerts/" + alert.get("id"));
    out.put("ALERT_STATUS", state == null ? "" : state.toUpperCase(Locale.ROOT));
    out.put("ALERT_SELECTOR", options.get("selector"));
    out.put("ALERT_CONDITION", options.get("op"));
    out.put("ALERT_THRESHOLD", options.get("value"));
    out.put("QUERY_NAME", query == null ? null : query.get("name"));
    out.put("QUERY_URL", host + "/queries/" + (query == null ? "" : query.get("id")));
    out.put("QUERY_RESULT_VALUE", value);
    out.put("QUERY_RESULT_ROWS", rows);
    out.put("QUERY_RESULT_COLS", Json.asList(data.get("columns")));
    out.put("QUERY_RESULT_TABLE", table);
    return out;
  }

  /**
   * The default alert body, from the file the deployment names.
   *
   * <p>`REDASH_ALERTS_DEFAULT_MAIL_BODY_TEMPLATE_FILE` points at a file on disk, and its
   * default points into the front end's build directory — which on this rebuild is inside
   * the packaged application rather than on disk beside it. So a named file is read when it
   * is there, and the vendored copy answers when it is not, which is the same template.
   */
  public static String defaultBodyTemplate(io.akka.redash.domain.Settings settings) {
    var named = settings.alertsDefaultMailBodyTemplateFile();
    if (named != null && !named.isEmpty()) {
      var path = java.nio.file.Path.of(named);
      if (java.nio.file.Files.isReadable(path)) {
        try {
          return java.nio.file.Files.readString(path, java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
          return defaultBodyTemplate();
        }
      }
    }
    return defaultBodyTemplate();
  }

  /** The template the original ships, read out of the resources this rebuild vendored. */
  public static String defaultBodyTemplate() {
    try (var stream = AlertTemplates.class.getClassLoader()
        .getResourceAsStream("templates/emails/alert.html")) {
      if (stream == null) {
        return "";
      }
      return new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
    } catch (java.io.IOException e) {
      return "";
    }
  }
}

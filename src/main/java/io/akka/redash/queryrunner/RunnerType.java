package io.akka.redash.queryrunner;

import io.akka.redash.domain.Json;
import io.akka.redash.domain.Sql;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One kind of data source: what it is called, what it will accept as configuration, how
 * its query text is decorated, and how a query actually reaches it.
 *
 * @param type the wire name, which is what a data source stores and the URL carries
 * @param name what a person sees in the list
 * @param deprecated whether the front end greys it out
 * @param syntax which editor mode the front end opens — {@code sql}, {@code json},
 *     {@code yaml} or {@code custom}
 * @param shouldAnnotate whether the job's metadata is prefixed to the statement as a
 *     comment; forty-three of the seventy-five say no, because their query language has no
 *     comment syntax to hide it in
 * @param supportsAutoLimit whether the automatic row limit is offered at all
 * @param limitStyle where that limit goes, which differs three ways across the registry
 * @param noopQuery what a connection test sends, or null when the type has no such thing
 * @param transport how a query is sent
 * @param configurationSchema the document the form is drawn from and the options validated
 *     against
 */
public record RunnerType(
    String type,
    String name,
    boolean deprecated,
    String syntax,
    boolean shouldAnnotate,
    boolean supportsAutoLimit,
    Sql.LimitStyle limitStyle,
    String noopQuery,
    Transport transport,
    Map<String, Object> configurationSchema) {

  /** What `/api/data_sources/types` answers for this type. */
  public Map<String, Object> asDocument() {
    var out = new LinkedHashMap<String, Object>();
    out.put("name", name);
    out.put("type", type);
    out.put("configuration_schema", configurationSchema);
    if (deprecated) {
      out.put("deprecated", true);
    }
    return out;
  }

  /**
   * Prefix the job's metadata as a comment. An empty metadata map still produces the
   * delimiters with nothing between them, which is what the original does and what the
   * hash then normalises away.
   */
  public String annotate(String query, Map<String, Object> metadata) {
    if (!shouldAnnotate) {
      return query;
    }
    var joined = new StringBuilder();
    boolean first = true;
    for (var entry : metadata.entrySet()) {
      joined.append(first ? "" : ", ")
          .append(entry.getKey())
          .append(": ")
          .append(pythonValue(entry.getValue()));
      first = false;
    }
    return "/* " + joined + " */ " + query;
  }

  /** A boolean in an annotation is written the way Python writes it, not the way JSON does. */
  private static String pythonValue(Object value) {
    if (value instanceof Boolean bool) {
      return bool ? "True" : "False";
    }
    return String.valueOf(value);
  }

  public String applyAutoLimit(String queryText, boolean shouldApply) {
    if (!supportsAutoLimit) {
      return queryText;
    }
    return Sql.applyAutoLimit(queryText, shouldApply, limitStyle);
  }

  /** The names of every property the schema marks secret. */
  public List<String> secrets() {
    return io.akka.redash.domain.Configuration.secrets(configurationSchema);
  }

  public Map<String, Object> mask(Map<String, Object> options) {
    return io.akka.redash.domain.Configuration.mask(options, configurationSchema);
  }

  /** A configuration is acceptable when nothing in it breaks the schema. */
  public boolean accepts(Map<String, Object> options) {
    return io.akka.redash.domain.Configuration.isValid(options, configurationSchema);
  }

  /** The whole of what running a query against this type does. */
  public RunResult run(String query, Map<String, Object> options) {
    return transport.run(this, query, options);
  }

  public RunResult schema(Map<String, Object> options) {
    return transport.schema(this, options);
  }

  /** A connection test, expressed as the source expresses it: run the no-op and see. */
  public String testConnection(Map<String, Object> options) {
    if (noopQuery == null) {
      return "Data source type does not support connection testing";
    }
    var result = run(noopQuery, options);
    return result.error();
  }

  /** A helper for the endpoints, which hand around plain documents rather than records. */
  public Map<String, Object> summary() {
    return Json.map("name", name, "type", type, "syntax", syntax,
        "supports_auto_limit", supportsAutoLimit);
  }
}

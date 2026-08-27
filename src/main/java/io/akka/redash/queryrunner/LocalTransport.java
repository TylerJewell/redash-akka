package io.akka.redash.queryrunner;

import io.akka.redash.domain.Json;
import io.akka.redash.domain.Numbers;
import java.io.BufferedReader;
import java.io.StringReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The three types that read something rather than talking to a database.
 *
 * <ul>
 *   <li>{@code csv} — a delimited file fetched from a URL and read as a table.
 *   <li>{@code excel} — the same, from a spreadsheet, which needs a reader this deployment
 *       does not carry; the type is registered and formed all the same.
 *   <li>{@code results} — a query over the stored results of *other* queries, which the
 *       execution side supplies rather than this transport reading anything itself.
 * </ul>
 */
public final class LocalTransport implements Transport {

  private static final HttpClient CLIENT =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

  /** How the query-results runner is given the results it is meant to query over. */
  public interface ResultsProvider {
    RunResult query(String query, Map<String, Object> options);
  }

  private static volatile ResultsProvider resultsProvider;

  /**
   * Wired once at start-up. It is a static hook rather than a constructor argument because
   * the registry is built before anything that could hold a store exists, and a data source
   * type is a value in that registry rather than a component.
   */
  public static void useResultsProvider(ResultsProvider provider) {
    resultsProvider = provider;
  }

  private final String type;

  private LocalTransport(String type) {
    this.type = type;
  }

  static LocalTransport forType(String type) {
    return switch (type) {
      case "csv", "results" -> new LocalTransport(type);
      default -> null;
    };
  }

  @Override
  public RunResult run(RunnerType runner, String query, Map<String, Object> options) {
    if ("results".equals(type)) {
      var provider = resultsProvider;
      if (provider == null) {
        return RunResult.failed("The query results data source is not available here.");
      }
      return provider.query(query, options);
    }
    try {
      var request = HttpRequest.newBuilder(URI.create(query.strip()))
          .timeout(Duration.ofSeconds(60)).GET().build();
      var response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() >= 400) {
        return RunResult.failed("Endpoint returned unexpected status code ("
            + response.statusCode() + ").");
      }
      return readDelimited(response.body(), delimiter(options));
    } catch (Exception e) {
      return RunResult.failed(String.valueOf(e.getMessage()));
    }
  }

  private static String delimiter(Map<String, Object> options) {
    var declared = options.get("delimiter");
    return declared == null || String.valueOf(declared).isEmpty() ? "," : String.valueOf(declared);
  }

  /**
   * A delimited file as a table. Quoting follows the same convention the writer uses: a
   * field may be wrapped in double quotes, and a doubled quote inside one is a literal
   * quote.
   */
  static RunResult readDelimited(String body, String delimiter) {
    var rows = new ArrayList<List<String>>();
    try (var reader = new BufferedReader(new StringReader(body))) {
      String line;
      while ((line = reader.readLine()) != null) {
        rows.add(splitLine(line, delimiter.charAt(0)));
      }
    } catch (java.io.IOException e) {
      return RunResult.failed(e.getMessage());
    }
    if (rows.isEmpty()) {
      return RunResult.of(List.of(), List.of());
    }
    var names = rows.get(0);
    var values = new ArrayList<List<Object>>(rows.size() - 1);
    for (int i = 1; i < rows.size(); i++) {
      values.add(new ArrayList<>(rows.get(i)));
    }
    return RunResult.fromRows(names, values);
  }

  static List<String> splitLine(String line, char delimiter) {
    var out = new ArrayList<String>();
    var field = new StringBuilder();
    boolean quoted = false;
    for (int i = 0; i < line.length(); i++) {
      char c = line.charAt(i);
      if (quoted) {
        if (c == '"') {
          if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
            field.append('"');
            i++;
          } else {
            quoted = false;
          }
        } else {
          field.append(c);
        }
      } else if (c == '"') {
        quoted = true;
      } else if (c == delimiter) {
        out.add(field.toString());
        field.setLength(0);
      } else {
        field.append(c);
      }
    }
    out.add(field.toString());
    return out;
  }

  /** Small helper for the results runner, which hands rows back as plain documents. */
  public static RunResult fromDocuments(List<Map<String, Object>> documents) {
    var names = new java.util.LinkedHashSet<String>();
    for (var document : documents) {
      names.addAll(document.keySet());
    }
    var values = new ArrayList<List<Object>>(documents.size());
    for (var document : documents) {
      var row = new ArrayList<>();
      for (String name : names) {
        row.add(document.get(name));
      }
      values.add(row);
    }
    return RunResult.fromRows(new ArrayList<>(names), values);
  }

  /** Exposed so a caller can name the type without importing the constants. */
  public static String stringType() {
    return Numbers.TYPE_STRING;
  }

  /** Kept so the class has a single place that knows how a result document is shaped. */
  public static Map<String, Object> emptyData() {
    return Json.map("columns", List.of(), "rows", List.of());
  }
}

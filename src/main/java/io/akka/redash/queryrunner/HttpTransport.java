package io.akka.redash.queryrunner;

import io.akka.redash.domain.Json;
import io.akka.redash.domain.Numbers;
import io.akka.redash.domain.Sql;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The data source types whose protocol is HTTP, driven through the JDK's own client.
 *
 * <p>Each of these is a different document in and a different document out, so unlike the
 * JDBC family they are not one engine with a table of URLs — they are a switch over the
 * type, with one method each. What they share is the transport, the authentication and the
 * shaping of whatever came back into columns and rows.
 */
public final class HttpTransport implements Transport {

  private static final HttpClient CLIENT =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
          .followRedirects(HttpClient.Redirect.NEVER).build();

  private final String type;

  private HttpTransport(String type) {
    this.type = type;
  }

  static HttpTransport forType(String type) {
    return switch (type) {
      case "clickhouse", "tinybird", "url", "json", "prometheus", "druid", "pinot", "d1",
          "influxdb", "elasticsearch2", "elasticsearch2_OpenDistroSQLElasticSearch",
          "elasticsearch2_XPackSQLElasticSearch" -> new HttpTransport(type);
      default -> null;
    };
  }

  @Override
  public RunResult run(RunnerType runner, String query, Map<String, Object> options) {
    try {
      return switch (type) {
        case "clickhouse", "tinybird" -> clickhouse(query, options);
        case "url" -> url(query, options);
        case "json" -> json(query, options);
        case "prometheus" -> prometheus(query, options);
        case "druid" -> druid(query, options);
        case "pinot" -> pinot(query, options);
        case "d1" -> d1(query, options);
        case "influxdb" -> influx(query, options);
        default -> elasticsearchSql(query, options);
      };
    } catch (RuntimeException e) {
      return RunResult.failed(String.valueOf(e.getMessage()));
    } catch (Exception e) {
      return RunResult.failed(e.getClass().getSimpleName() + ": " + e.getMessage());
    }
  }

  @Override
  public RunResult schema(RunnerType runner, Map<String, Object> options) {
    if (!"clickhouse".equals(type) && !"tinybird".equals(type)) {
      return RunResult.failed(NOT_SUPPORTED);
    }
    var result = run(runner,
        "SELECT database, table, name, type as data_type FROM system.columns"
            + " WHERE database NOT IN ('system', 'information_schema', 'INFORMATION_SCHEMA')",
        options);
    if (result.isFailure()) {
      return result;
    }
    var tables = new java.util.TreeMap<String, List<Map<String, Object>>>();
    for (Map<String, Object> row : result.rows()) {
      var name = row.get("database") + "." + row.get("table");
      tables.computeIfAbsent(name, ignored -> new ArrayList<>())
          .add(Json.map("name", row.get("name"), "type", row.get("data_type")));
    }
    var out = new ArrayList<Map<String, Object>>(tables.size());
    tables.forEach((name, columns) -> out.add(Json.map("name", name, "columns", columns)));
    return RunResult.of(List.of(), out);
  }

  // ------------------------------------------------------------------ per type

  /**
   * ClickHouse over its HTTP interface, which is what the original uses rather than a
   * driver. A script of several statements is sent statement by statement inside one
   * session, and the answer is the last statement's.
   */
  private RunResult clickhouse(String query, Map<String, Object> options) throws Exception {
    var statements = new ArrayList<String>();
    for (String statement : Sql.splitStatements(query)) {
      if (!statement.isEmpty()) {
        statements.add(statement);
      }
    }
    if (statements.isEmpty()) {
      return RunResult.failed("Query is empty");
    }
    String sessionId = statements.size() > 1
        ? "redash_" + java.util.UUID.randomUUID().toString().replace("-", "") : null;
    RunResult last = null;
    for (int i = 0; i < statements.size(); i++) {
      last = clickhouseOne(statements.get(i), options, sessionId, i > 0);
      if (last.isFailure()) {
        return last;
      }
    }
    return last;
  }

  private RunResult clickhouseOne(String statement, Map<String, Object> options,
      String sessionId, boolean checkSession) throws Exception {
    var base = JdbcTransport.string(options, "url", "http://127.0.0.1:8123");
    var params = new LinkedHashMap<String, String>();
    params.put("user", JdbcTransport.string(options, "user", "default"));
    params.put("password", JdbcTransport.string(options, "password", ""));
    params.put("database", JdbcTransport.string(options, "dbname", ""));
    params.put("default_format", "JSON");
    if (sessionId != null) {
      params.put("session_id", sessionId);
      params.put("session_check", checkSession ? "1" : "0");
      params.put("session_timeout", String.valueOf(JdbcTransport.number(options, "timeout", 30)));
    }
    var response = post(base + "?" + queryString(params), statement + "\nFORMAT JSON",
        Map.of(), options);
    if (response.statusCode() >= 400) {
      return RunResult.failed(response.body());
    }
    if (response.body().isEmpty()) {
      return RunResult.of(List.of(), List.of());
    }
    var document = Json.asMap(Json.loads(response.body()));
    if (document.containsKey("exception")) {
      return RunResult.failed(String.valueOf(document.get("exception")));
    }
    var names = new ArrayList<String>();
    var types = new ArrayList<String>();
    for (Object meta : Json.asList(document.get("meta"))) {
      var column = Json.asMap(meta);
      names.add(String.valueOf(column.get("name")));
      types.add(clickhouseType(String.valueOf(column.get("type"))));
    }
    var columns = RunResult.fetchColumns(names, types);
    var rows = new ArrayList<Map<String, Object>>();
    for (Object row : Json.asList(document.get("data"))) {
      rows.add(Json.asMap(row));
    }
    return RunResult.of(columns, rows);
  }

  /** ClickHouse names its own types; a nullable wrapper is stripped before the mapping. */
  static String clickhouseType(String declared) {
    var name = declared.toLowerCase(Locale.ROOT);
    if (name.startsWith("nullable(") && name.endsWith(")")) {
      name = name.substring("nullable(".length(), name.length() - 1);
    }
    if (name.startsWith("int") || name.startsWith("uint")) {
      return Numbers.TYPE_INTEGER;
    }
    if (name.startsWith("float")) {
      return Numbers.TYPE_FLOAT;
    }
    if (name.equals("datetime")) {
      return Numbers.TYPE_DATETIME;
    }
    if (name.equals("date")) {
      return Numbers.TYPE_DATE;
    }
    return Numbers.TYPE_STRING;
  }

  /**
   * The deprecated URL runner: the query *is* a path, joined onto the configured base, and
   * the answer is whatever came back — not a table.
   */
  private RunResult url(String query, Map<String, Object> options) throws Exception {
    var base = JdbcTransport.string(options, "url", "");
    var path = query.strip();
    if (!base.isEmpty() && path.contains("://")) {
      return RunResult.failed("Accepting only relative URLs to '" + base + "'");
    }
    var response = get(base + path, options);
    if (response.statusCode() != 200) {
      return RunResult.failed("Endpoint returned unexpected status code ("
          + response.statusCode() + ").");
    }
    var body = response.body().strip();
    if (body.isEmpty()) {
      return RunResult.failed("Got empty response from '" + base + path + "'.");
    }
    return asTable(Json.loads(body));
  }

  /** The JSON runner: the query is a small document describing a request to make. */
  private RunResult json(String query, Map<String, Object> options) throws Exception {
    var request = Json.asMap(Json.loads(query));
    var url = String.valueOf(request.get("url"));
    var method = String.valueOf(request.getOrDefault("method", "get")).toUpperCase(Locale.ROOT);
    var params = new LinkedHashMap<String, String>();
    Json.asMap(request.get("params")).forEach((k, v) -> params.put(k, String.valueOf(v)));
    var target = params.isEmpty() ? url : url + "?" + queryString(params);
    HttpResponse<String> response = "POST".equals(method)
        ? post(target, Json.dumps(request.get("data")),
            Map.of("Content-Type", "application/json"), options)
        : get(target, options);
    if (response.statusCode() >= 400) {
      return RunResult.failed("Endpoint returned unexpected status code ("
          + response.statusCode() + ").");
    }
    var document = Json.loads(response.body());
    var path = request.get("path");
    if (path != null) {
      for (String part : String.valueOf(path).split("\\.")) {
        document = Json.asMap(document).get(part);
      }
    }
    return asTable(document);
  }

  /** Prometheus: an instant query, or a range query when a start is given. */
  private RunResult prometheus(String query, Map<String, Object> options) throws Exception {
    var base = JdbcTransport.string(options, "url", "");
    var parsed = parseQueryString(query.strip());
    boolean range = parsed.containsKey("start");
    var endpoint = base + (range ? "/api/v1/query_range" : "/api/v1/query");
    if (!parsed.containsKey("time") && !range) {
      parsed.put("time", String.valueOf(System.currentTimeMillis() / 1000));
    }
    var response = get(endpoint + "?" + queryString(parsed), options);
    if (response.statusCode() != 200) {
      return RunResult.failed(response.body());
    }
    var document = Json.asMap(Json.loads(response.body()));
    var data = Json.asMap(document.get("data"));
    var rows = new ArrayList<Map<String, Object>>();
    var names = new java.util.LinkedHashSet<String>();
    for (Object entry : Json.asList(data.get("result"))) {
      var metric = Json.asMap(Json.asMap(entry).get("metric"));
      names.addAll(metric.keySet());
      if (range) {
        for (Object pair : Json.asList(Json.asMap(entry).get("values"))) {
          rows.add(sample(metric, Json.asList(pair)));
        }
      } else {
        rows.add(sample(metric, Json.asList(Json.asMap(entry).get("value"))));
      }
    }
    names.add("timestamp");
    names.add("value");
    var types = new ArrayList<String>();
    for (String name : names) {
      types.add("timestamp".equals(name) ? Numbers.TYPE_DATETIME : Numbers.TYPE_STRING);
    }
    return RunResult.of(RunResult.fetchColumns(new ArrayList<>(names), types), rows);
  }

  private static Map<String, Object> sample(Map<String, Object> metric, List<Object> pair) {
    var row = new LinkedHashMap<String, Object>(metric);
    if (pair.size() == 2) {
      var seconds = Numbers.toDouble(pair.get(0));
      row.put("timestamp",
          seconds == null ? null : java.time.Instant.ofEpochMilli((long) (seconds * 1000)));
      row.put("value", pair.get(1));
    }
    return row;
  }

  /** Druid's SQL endpoint, which answers an array of objects. */
  private RunResult druid(String query, Map<String, Object> options) throws Exception {
    var url = scheme(options) + "://" + JdbcTransport.string(options, "host", "127.0.0.1")
        + ":" + JdbcTransport.number(options, "port", 8082) + "/druid/v2/sql/";
    var response = post(url, Json.dumps(Json.map("query", query)),
        Map.of("Content-Type", "application/json"), options);
    if (response.statusCode() >= 400) {
      return RunResult.failed(response.body());
    }
    return asTable(Json.loads(response.body()));
  }

  /** Pinot's broker endpoint. */
  private RunResult pinot(String query, Map<String, Object> options) throws Exception {
    var url = JdbcTransport.string(options, "brokerHost", "127.0.0.1") + ":"
        + JdbcTransport.number(options, "brokerPort", 8099) + "/query/sql";
    var response = post("http://" + url, Json.dumps(Json.map("sql", query)),
        Map.of("Content-Type", "application/json"), options);
    if (response.statusCode() >= 400) {
      return RunResult.failed(response.body());
    }
    var document = Json.asMap(Json.loads(response.body()));
    var table = Json.asMap(document.get("resultTable"));
    var schema = Json.asMap(table.get("dataSchema"));
    var names = new ArrayList<String>();
    var types = new ArrayList<String>();
    var declaredNames = Json.asList(schema.get("columnNames"));
    var declaredTypes = Json.asList(schema.get("columnDataTypes"));
    for (int i = 0; i < declaredNames.size(); i++) {
      names.add(String.valueOf(declaredNames.get(i)));
      types.add(pinotType(String.valueOf(
          i < declaredTypes.size() ? declaredTypes.get(i) : "STRING")));
    }
    var rows = new ArrayList<Map<String, Object>>();
    var columns = RunResult.fetchColumns(names, types);
    for (Object entry : Json.asList(table.get("rows"))) {
      var values = Json.asList(entry);
      var row = new LinkedHashMap<String, Object>();
      for (int i = 0; i < columns.size(); i++) {
        row.put(String.valueOf(columns.get(i).get("name")), i < values.size() ? values.get(i) : null);
      }
      rows.add(row);
    }
    return RunResult.of(columns, rows);
  }

  static String pinotType(String declared) {
    return switch (declared.toUpperCase(Locale.ROOT)) {
      case "INT", "LONG" -> Numbers.TYPE_INTEGER;
      case "FLOAT", "DOUBLE" -> Numbers.TYPE_FLOAT;
      case "BOOLEAN" -> Numbers.TYPE_BOOLEAN;
      case "TIMESTAMP" -> Numbers.TYPE_DATETIME;
      default -> Numbers.TYPE_STRING;
    };
  }

  /** Cloudflare D1, whose REST API answers `{result: [{results: [...]}]}`. */
  private RunResult d1(String query, Map<String, Object> options) throws Exception {
    var account = JdbcTransport.string(options, "account_id", "");
    var database = JdbcTransport.string(options, "database_id", "");
    var url = "https://api.cloudflare.com/client/v4/accounts/" + account
        + "/d1/database/" + database + "/query";
    var response = post(url, Json.dumps(Json.map("sql", query)),
        Map.of("Content-Type", "application/json",
            "Authorization", "Bearer " + JdbcTransport.string(options, "api_token", "")),
        options);
    if (response.statusCode() >= 400) {
      return RunResult.failed(response.body());
    }
    var document = Json.asMap(Json.loads(response.body()));
    var results = Json.asList(document.get("result"));
    if (results.isEmpty()) {
      return RunResult.of(List.of(), List.of());
    }
    return asTable(Json.asMap(results.get(0)).get("results"));
  }

  /** InfluxDB 1.x, whose answer is a list of series each with its own columns. */
  private RunResult influx(String query, Map<String, Object> options) throws Exception {
    var url = JdbcTransport.string(options, "url", "") + "/query?q="
        + URLEncoder.encode(query, StandardCharsets.UTF_8);
    var response = get(url, options);
    if (response.statusCode() >= 400) {
      return RunResult.failed(response.body());
    }
    var document = Json.asMap(Json.loads(response.body()));
    var names = new ArrayList<String>();
    var values = new ArrayList<List<Object>>();
    for (Object result : Json.asList(document.get("results"))) {
      for (Object series : Json.asList(Json.asMap(result).get("series"))) {
        var one = Json.asMap(series);
        if (names.isEmpty()) {
          for (Object column : Json.asList(one.get("columns"))) {
            names.add(String.valueOf(column));
          }
        }
        for (Object row : Json.asList(one.get("values"))) {
          values.add(Json.asList(row));
        }
      }
    }
    return RunResult.fromRows(names, values);
  }

  /** The three Elasticsearch SQL variants, which differ only in their endpoint. */
  private RunResult elasticsearchSql(String query, Map<String, Object> options) throws Exception {
    var base = JdbcTransport.string(options, "server", JdbcTransport.string(options, "url", ""));
    var path = switch (type) {
      case "elasticsearch2_OpenDistroSQLElasticSearch" -> "/_opendistro/_sql";
      case "elasticsearch2_XPackSQLElasticSearch" -> "/_sql?format=json";
      default -> "/_sql";
    };
    var body = "elasticsearch2".equals(type)
        ? query
        : Json.dumps(Json.map("query", query));
    var response = post(base + path, body, Map.of("Content-Type", "application/json"), options);
    if (response.statusCode() >= 400) {
      return RunResult.failed(response.body());
    }
    var document = Json.asMap(Json.loads(response.body()));
    var names = new ArrayList<String>();
    var types = new ArrayList<String>();
    for (Object column : Json.asList(document.get("columns"))) {
      var one = Json.asMap(column);
      names.add(String.valueOf(one.get("name")));
      types.add(Numbers.TYPE_STRING);
    }
    var values = new ArrayList<List<Object>>();
    for (Object row : Json.asList(document.get("rows"))) {
      values.add(Json.asList(row));
    }
    if (names.isEmpty()) {
      return asTable(document);
    }
    return RunResult.fromRows(names, values);
  }

  // ------------------------------------------------------------------ plumbing

  /** A list of objects becomes a table; anything else becomes one column called `json`. */
  static RunResult asTable(Object document) {
    if (document instanceof List<?> list) {
      var names = new java.util.LinkedHashSet<String>();
      for (Object item : list) {
        names.addAll(Json.asMap(item).keySet());
      }
      var values = new ArrayList<List<Object>>(list.size());
      for (Object item : list) {
        var row = Json.asMap(item);
        var value = new ArrayList<>();
        for (String name : names) {
          value.add(row.get(name));
        }
        values.add(value);
      }
      return RunResult.fromRows(new ArrayList<>(names), values);
    }
    return RunResult.fromRows(List.of("json"), List.of(List.of(Json.dumps(document))));
  }

  private static String scheme(Map<String, Object> options) {
    return Boolean.TRUE.equals(options.get("ssl")) ? "https" : "http";
  }

  static String queryString(Map<String, String> params) {
    var out = new StringBuilder();
    params.forEach((key, value) -> {
      if (out.length() > 0) {
        out.append('&');
      }
      out.append(URLEncoder.encode(key, StandardCharsets.UTF_8))
          .append('=')
          .append(URLEncoder.encode(value, StandardCharsets.UTF_8));
    });
    return out.toString();
  }

  static Map<String, String> parseQueryString(String text) {
    var out = new LinkedHashMap<String, String>();
    for (String pair : text.split("&")) {
      if (pair.isEmpty()) {
        continue;
      }
      int equals = pair.indexOf('=');
      if (equals < 0) {
        out.put(java.net.URLDecoder.decode(pair, StandardCharsets.UTF_8), "");
      } else {
        out.put(java.net.URLDecoder.decode(pair.substring(0, equals), StandardCharsets.UTF_8),
            java.net.URLDecoder.decode(pair.substring(equals + 1), StandardCharsets.UTF_8));
      }
    }
    return out;
  }

  private HttpResponse<String> get(String url, Map<String, Object> options) throws Exception {
    var builder = HttpRequest.newBuilder(URI.create(url)).GET()
        .timeout(Duration.ofSeconds(JdbcTransport.number(options, "timeout", 60)));
    authenticate(builder, options);
    return CLIENT.send(builder.build(), HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<String> post(String url, String body, Map<String, String> headers,
      Map<String, Object> options) throws Exception {
    var builder = HttpRequest.newBuilder(URI.create(url))
        .POST(HttpRequest.BodyPublishers.ofString(body == null ? "" : body))
        .timeout(Duration.ofSeconds(JdbcTransport.number(options, "timeout", 60)));
    headers.forEach(builder::header);
    authenticate(builder, options);
    return CLIENT.send(builder.build(), HttpResponse.BodyHandlers.ofString());
  }

  /** HTTP basic authentication, which is what every one of these types offers. */
  private static void authenticate(HttpRequest.Builder builder, Map<String, Object> options) {
    var user = JdbcTransport.string(options, "username", JdbcTransport.string(options, "user", null));
    var password = JdbcTransport.string(options, "password", null);
    if (user != null && password != null) {
      var token = Base64.getEncoder()
          .encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8));
      builder.header("Authorization", "Basic " + token);
    }
  }
}

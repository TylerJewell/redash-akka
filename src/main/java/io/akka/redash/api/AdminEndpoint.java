package io.akka.redash.api;

import akka.http.javadsl.model.HttpResponse;
import akka.javasdk.annotations.Acl;
import akka.javasdk.annotations.http.Get;
import akka.javasdk.annotations.http.HttpEndpoint;
import akka.javasdk.client.ComponentClient;
import io.akka.redash.application.Store;
import io.akka.redash.domain.Json;
import io.akka.redash.domain.Settings;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * `/api/admin/queries/*`, `/status.json` and `/ping` (SPEC-001 R118, R147).
 *
 * <p>The outdated list is not recomputed: it is the set of identifiers the last refresh
 * sweep wrote down, read back with the instant that sweep finished, so the screen shows
 * what the scheduler actually did rather than what it would decide now.
 */
@Acl(allow = @Acl.Matcher(principal = Acl.Principal.ALL))
@HttpEndpoint("")
public class AdminEndpoint extends ApiBase {

  public AdminEndpoint(ComponentClient client) {
    super(new Service(client));
  }

  @Get("/ping")
  public HttpResponse ping() {
    // Decorated like every other answer: the source's security headers and the token cookie
    // are set by a hook that runs after every request, the liveness check included.
    return decorate(Http.text(200, "text/html; charset=utf-8", "PONG."));
  }

  @Get("/api/admin/queries/outdated")
  public HttpResponse outdated() {
    return answer(() -> {
      var caller = caller();
      caller.require("super_admin");
      var status = store().find(Store.STATE, "refresh");
      if (status == null || status.get("last_refresh_at") == null) {
        // The original reads the sweep's status out of a redis hash and then subscripts it
        // for `last_refresh_at`. Before the first sweep has run the hash is empty and the
        // subscript raises, so the answer is the framework's own error page rather than a
        // handler's refusal. This is a plain route rather than a flask-restful resource,
        // which is why the body is HTML here and JSON everywhere else a 500 appears.
        record(caller, Json.map("action", "list", "object_type", "outdated_queries"));
        return Http.html(500, Http.SERVER_ERROR_PAGE);
      }
      var ids = Json.asList(status.get("query_ids"));
      var queries = new ArrayList<Map<String, Object>>();
      for (Object id : ids) {
        var query = service.queryById(Service.number(id));
        if (query != null) {
          queries.add(query);
        }
      }
      queries.sort(Comparator.comparing(
          (Map<String, Object> query) -> String.valueOf(query.getOrDefault("created_at", "")))
          .reversed());
      var serialized = new ArrayList<Map<String, Object>>(queries.size());
      for (Map<String, Object> query : queries) {
        var latest = store().find(Store.QUERY_RESULTS, query.get("latest_query_data_id"));
        serialized.add(Serializers.query(query, service.userById(query.get("user_id")), null,
            service.parameterized(query).isSafe(), true, latest, null));
      }
      record(caller, Json.map("action", "list", "object_type", "outdated_queries"));
      return Json.map("queries", serialized,
          "updated_at", status.get("last_refresh_at"));
    });
  }

  @Get("/api/admin/queries/rq_status")
  public HttpResponse rqStatus() {
    return answer(() -> {
      var caller = caller();
      caller.require("super_admin");
      record(caller, Json.map("action", "list", "object_type", "rq_status"));
      return Json.map("queues", jobQueues(), "workers", List.of());
    });
  }

  @Get("/status.json")
  public HttpResponse status() {
    return answer(() -> {
      var caller = caller();
      caller.require("super_admin");
      var out = new LinkedHashMap<String, Object>();
      out.put("version", Settings.VERSION);
      out.put("workers", List.of());
      // Named after the cache redash keeps its transient state in. This rebuild has no
      // redis; what plays that part is the process's own heap, and that is what is
      // reported here so the admin screen has the number it draws.
      var runtime = Runtime.getRuntime();
      var held = runtime.totalMemory() - runtime.freeMemory();
      out.put("redis_used_memory", held);
      out.put("redis_used_memory_human", humanBytes(held));
      out.put("queries_count", (long) service.allQueries().size());
      if (service.settings().featureShowQueryResultsCount()) {
        var results = store().byOrg(Store.QUERY_RESULTS, 1L);
        out.put("query_results_count", (long) results.size());
        out.put("unused_query_results_count", (long) unusedResults().size());
      }
      out.put("dashboards_count", (long) store().byOrg(Store.DASHBOARDS, 1L).size());
      out.put("widgets_count", (long) store().all(Store.WIDGETS).size());
      var manager = new LinkedHashMap<String, Object>();
      var refresh = store().find(Store.STATE, "refresh");
      if (refresh != null) {
        refresh.forEach((name, value) -> {
          if (!"id".equals(name)) {
            manager.put(name, value);
          }
        });
      }
      manager.put("queues", queues());
      out.put("manager", manager);
      out.put("database_metrics", Json.map("metrics", List.of(
          List.of("Query Results Size", storedBytes(Store.QUERY_RESULTS)),
          List.of("Redash DB Size", storedBytes(null)))));
      return out;
    });
  }

  /**
   * The three queues a redash deployment runs, and how much is waiting on each.
   *
   * <p>The names are the source's: `default` carries the general jobs — recording an event,
   * sending mail — `queries` carries query executions, and `schemas` carries schema
   * refreshes. An admin screen draws one row per queue, so the set of names is part of the
   * answer even where every size is zero.
   */
  private Map<String, Object> queues() {
    return Json.map(
        "default", Json.map("size", 0L),
        "queries", Json.map("size", waiting()),
        "schemas", Json.map("size", 0L));
  }

  /** The same three queues in the shape the job monitor reads them in. */
  private Map<String, Object> jobQueues() {
    return Json.map(
        "default", Json.map("name", "default", "queued", 0L, "started", List.of()),
        "queries", Json.map("name", "queries", "queued", waiting(), "started", List.of()),
        "schemas", Json.map("name", "schemas", "queued", 0L, "started", List.of()));
  }

  /** How many query executions are in flight, which is what the `queries` queue holds. */
  private long waiting() {
    return store().all(Store.LOCKS).size();
  }

  /** The bytes one table holds, or every table when no table is named. */
  private long storedBytes(String table) {
    long total = 0;
    for (String name : table == null ? Store.TABLES : List.of(table)) {
      for (Map<String, Object> row : store().all(name)) {
        total += Json.dumps(row).getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
      }
    }
    return total;
  }

  /** redis's own rendering of a byte count, which the admin screen prints as it arrives. */
  private static String humanBytes(long bytes) {
    if (bytes < 1024) {
      return bytes + "B";
    }
    String[] units = {"K", "M", "G", "T"};
    double value = bytes;
    int unit = -1;
    while (value >= 1024 && unit < units.length - 1) {
      value /= 1024;
      unit++;
    }
    return String.format(java.util.Locale.ROOT, "%.2f%s", value, units[unit]);
  }

  private List<Map<String, Object>> unusedResults() {
    var pointedAt = new java.util.HashSet<Long>();
    for (Map<String, Object> query : service.allQueries()) {
      if (query.get("latest_query_data_id") != null) {
        pointedAt.add(Service.number(query.get("latest_query_data_id")));
      }
    }
    var out = new ArrayList<Map<String, Object>>();
    for (Map<String, Object> result : store().byOrg(Store.QUERY_RESULTS, 1L)) {
      if (!pointedAt.contains(Service.number(result.get("id")))) {
        out.add(result);
      }
    }
    return out;
  }
}

package io.akka.redash.api;

import io.akka.redash.domain.Crypto;
import io.akka.redash.domain.Json;
import io.akka.redash.domain.Text;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * What each stored row looks like on the wire (SPEC-001 R44, R51, R126, R130, R146).
 *
 * <p>These are field lists rather than logic, and they are exact: the front end reads them
 * and the benchmark at step e compares them against the original's, field for field. Two
 * of them carry a value that is computed rather than stored and are worth pointing at — a
 * dashboard's `slug` is derived from its **name** on every read rather than from the stored
 * slug, and a user's `profile_image_url` is a gravatar address derived from the lower-cased
 * email unless one was set.
 */
public final class Serializers {

  private Serializers() {}

  // ------------------------------------------------------------------ user

  public static Map<String, Object> user(Map<String, Object> user, boolean withApiKey) {
    var out = new LinkedHashMap<String, Object>();
    out.put("id", user.get("id"));
    out.put("name", user.get("name"));
    out.put("email", user.get("email"));
    out.put("profile_image_url", profileImageUrl(user));
    out.put("groups", user.get("groups"));
    out.put("updated_at", user.get("updated_at"));
    out.put("created_at", user.get("created_at"));
    out.put("disabled_at", user.get("disabled_at"));
    out.put("is_disabled", user.get("disabled_at") != null);
    out.put("active_at", user.get("active_at"));
    out.put("is_invitation_pending", Boolean.TRUE.equals(user.get("is_invitation_pending")));
    out.put("is_email_verified", !Boolean.FALSE.equals(user.get("is_email_verified")));
    out.put("auth_type", user.get("password_hash") == null ? "external" : "password");
    if (withApiKey) {
      out.put("api_key", user.get("api_key"));
    }
    return out;
  }

  public static String profileImageUrl(Map<String, Object> user) {
    var declared = user.get("profile_image_url");
    if (declared != null) {
      return String.valueOf(declared);
    }
    var email = String.valueOf(user.getOrDefault("email", "")).toLowerCase(Locale.ROOT);
    return "https://www.gravatar.com/avatar/" + Crypto.md5Hex(email) + "?s=40&d=identicon";
  }

  /** The four fields a dashboard embeds about its author, and no more. */
  public static Map<String, Object> shortUser(Map<String, Object> user) {
    if (user == null) {
      return null;
    }
    return Json.map(
        "id", user.get("id"),
        "name", user.get("name"),
        "email", user.get("email"),
        "profile_image_url", profileImageUrl(user));
  }

  // ------------------------------------------------------------------ group

  public static Map<String, Object> group(Map<String, Object> group) {
    return Json.map(
        "id", group.get("id"),
        "name", group.get("name"),
        "permissions", group.get("permissions"),
        "type", group.get("type"),
        "created_at", group.get("created_at"));
  }

  // ------------------------------------------------------------------ data source

  /**
   * @param full whether the caller is an administrator, who sees the options and the queues
   * @param viewOnly the caller's own view-only verdict, or null to leave it out
   */
  public static Map<String, Object> dataSource(Map<String, Object> dataSource,
      io.akka.redash.queryrunner.RunnerType type, boolean full, Boolean viewOnly) {
    var out = new LinkedHashMap<String, Object>();
    out.put("id", dataSource.get("id"));
    out.put("name", dataSource.get("name"));
    out.put("type", dataSource.get("type"));
    out.put("syntax", type == null ? "sql" : type.syntax());
    // The original reads this out of redis with `exists`, which answers 0 or 1 rather than
    // a boolean, and the front end sees the number.
    out.put("paused", dataSource.get("pause_reason") != null
        || Boolean.TRUE.equals(dataSource.get("paused")) ? 1L : 0L);
    out.put("pause_reason", dataSource.get("pause_reason"));
    out.put("supports_auto_limit", type != null && type.supportsAutoLimit());
    if (full) {
      out.put("options", type == null
          ? dataSource.get("options")
          : type.mask(Json.asMap(dataSource.get("options"))));
      out.put("queue_name", dataSource.getOrDefault("queue_name", "queries"));
      out.put("scheduled_queue_name",
          dataSource.getOrDefault("scheduled_queue_name", "scheduled_queries"));
      out.put("groups", dataSource.get("groups"));
    }
    if (viewOnly != null) {
      out.put("view_only", viewOnly);
    }
    return out;
  }

  // ------------------------------------------------------------------ query

  /** Everything a caller may be told about a query, with the four optional halves. */
  public static Map<String, Object> query(Map<String, Object> query, Map<String, Object> author,
      Map<String, Object> lastModifiedBy, boolean isSafe, boolean withStats,
      Map<String, Object> latestResult, List<Map<String, Object>> visualizations) {
    var out = new LinkedHashMap<String, Object>();
    out.put("id", query.get("id"));
    out.put("latest_query_data_id", query.get("latest_query_data_id"));
    out.put("name", query.get("name"));
    out.put("description", query.get("description"));
    out.put("query", query.get("query"));
    out.put("query_hash", query.get("query_hash"));
    out.put("schedule", query.get("schedule"));
    out.put("api_key", query.get("api_key"));
    out.put("is_archived", Boolean.TRUE.equals(query.get("is_archived")));
    out.put("is_draft", Boolean.TRUE.equals(query.get("is_draft")));
    out.put("updated_at", query.get("updated_at"));
    out.put("created_at", query.get("created_at"));
    out.put("data_source_id", query.get("data_source_id"));
    out.put("options", query.getOrDefault("options", Map.of()));
    out.put("version", query.get("version"));
    out.put("tags", query.get("tags") == null ? List.of() : query.get("tags"));
    out.put("is_safe", isSafe);
    if (author != null) {
      out.put("user", user(author, false));
    } else {
      out.put("user_id", query.get("user_id"));
    }
    if (lastModifiedBy != null) {
      out.put("last_modified_by", user(lastModifiedBy, false));
    } else {
      out.put("last_modified_by_id", query.get("last_modified_by_id"));
    }
    if (withStats) {
      out.put("retrieved_at", latestResult == null ? null : latestResult.get("retrieved_at"));
      out.put("runtime", latestResult == null ? null : latestResult.get("runtime"));
    }
    if (visualizations != null) {
      out.put("visualizations", visualizations);
    }
    return out;
  }

  // ------------------------------------------------------------------ visualization, widget

  public static Map<String, Object> visualization(Map<String, Object> visualization,
      Map<String, Object> query) {
    var out = new LinkedHashMap<String, Object>();
    out.put("id", visualization.get("id"));
    out.put("type", visualization.get("type"));
    out.put("name", visualization.get("name"));
    out.put("description", visualization.get("description"));
    out.put("options", visualization.get("options"));
    out.put("updated_at", visualization.get("updated_at"));
    out.put("created_at", visualization.get("created_at"));
    if (query != null) {
      out.put("query", query);
    }
    return out;
  }

  public static Map<String, Object> widget(Map<String, Object> widget,
      Map<String, Object> visualization) {
    var out = new LinkedHashMap<String, Object>();
    out.put("id", widget.get("id"));
    out.put("width", widget.get("width"));
    out.put("options", widget.get("options"));
    out.put("dashboard_id", widget.get("dashboard_id"));
    out.put("text", widget.get("text"));
    out.put("updated_at", widget.get("updated_at"));
    out.put("created_at", widget.get("created_at"));
    if (visualization != null) {
      out.put("visualization", visualization);
    }
    return out;
  }

  /** A widget the caller may not see keeps its geometry and loses everything else. */
  public static Map<String, Object> restrictedWidget(Map<String, Object> widget) {
    var out = new LinkedHashMap<String, Object>();
    out.put("id", widget.get("id"));
    out.put("width", widget.get("width"));
    out.put("dashboard_id", widget.get("dashboard_id"));
    out.put("options", widget.get("options"));
    out.put("created_at", widget.get("created_at"));
    out.put("updated_at", widget.get("updated_at"));
    out.put("restricted", true);
    return out;
  }

  /** The reduced widget a public dashboard carries, with a reduced visualisation inside. */
  public static Map<String, Object> publicWidget(Map<String, Object> widget,
      Map<String, Object> visualization, Map<String, Object> query) {
    var out = new LinkedHashMap<String, Object>();
    out.put("id", widget.get("id"));
    out.put("width", widget.get("width"));
    out.put("options", widget.get("options"));
    out.put("text", widget.get("text"));
    out.put("updated_at", widget.get("updated_at"));
    out.put("created_at", widget.get("created_at"));
    if (visualization != null) {
      out.put("visualization", Json.map(
          "type", visualization.get("type"),
          "name", visualization.get("name"),
          "description", visualization.get("description"),
          "options", visualization.get("options"),
          "updated_at", visualization.get("updated_at"),
          "created_at", visualization.get("created_at"),
          "query", Json.map(
              "id", query == null ? null : query.get("id"),
              "name", query == null ? null : query.get("name"),
              "description", query == null ? null : query.get("description"),
              "options", query == null ? null : query.get("options"))));
    }
    return out;
  }

  // ------------------------------------------------------------------ dashboard

  public static Map<String, Object> dashboard(Map<String, Object> dashboard,
      Map<String, Object> author, List<Map<String, Object>> widgets) {
    var out = new LinkedHashMap<String, Object>();
    out.put("id", dashboard.get("id"));
    // Derived from the name on every read, not from the stored slug.
    out.put("slug", Text.slugify(String.valueOf(dashboard.getOrDefault("name", ""))));
    out.put("name", dashboard.get("name"));
    out.put("user_id", dashboard.get("user_id"));
    out.put("user", shortUser(author));
    out.put("layout", dashboard.getOrDefault("layout", List.of()));
    out.put("dashboard_filters_enabled",
        Boolean.TRUE.equals(dashboard.get("dashboard_filters_enabled")));
    out.put("widgets", widgets);
    out.put("options", dashboard.getOrDefault("options", Map.of()));
    out.put("is_archived", Boolean.TRUE.equals(dashboard.get("is_archived")));
    out.put("is_draft", Boolean.TRUE.equals(dashboard.get("is_draft")));
    out.put("tags", dashboard.get("tags") == null ? List.of() : dashboard.get("tags"));
    out.put("updated_at", dashboard.get("updated_at"));
    out.put("created_at", dashboard.get("created_at"));
    out.put("version", dashboard.get("version"));
    return out;
  }

  /** The seven keys a public dashboard exposes, and no others. */
  public static Map<String, Object> publicDashboard(Map<String, Object> serialized,
      List<Map<String, Object>> widgets) {
    var out = new LinkedHashMap<String, Object>();
    for (String key : List.of("name", "layout", "dashboard_filters_enabled", "updated_at",
        "created_at", "options")) {
      out.put(key, serialized.get(key));
    }
    out.put("widgets", widgets);
    return out;
  }

  // ------------------------------------------------------------------ alert

  public static Map<String, Object> alert(Map<String, Object> alert, Map<String, Object> query,
      Map<String, Object> author) {
    var out = new LinkedHashMap<String, Object>();
    out.put("id", alert.get("id"));
    out.put("name", alert.get("name"));
    out.put("options", alert.get("options"));
    out.put("state", alert.get("state"));
    out.put("last_triggered_at", alert.get("last_triggered_at"));
    out.put("updated_at", alert.get("updated_at"));
    out.put("created_at", alert.get("created_at"));
    out.put("rearm", alert.get("rearm"));
    if (query != null || author != null) {
      out.put("query", query);
      out.put("user", author == null ? null : user(author, false));
    } else {
      out.put("query_id", alert.get("query_id"));
      out.put("user_id", alert.get("user_id"));
    }
    return out;
  }

  public static Map<String, Object> alertSubscription(Map<String, Object> subscription,
      Map<String, Object> subscriber, Map<String, Object> destination) {
    var out = new LinkedHashMap<String, Object>();
    out.put("id", subscription.get("id"));
    out.put("user", subscriber == null ? null : user(subscriber, false));
    out.put("alert_id", subscription.get("alert_id"));
    if (destination != null) {
      out.put("destination", destination);
    }
    return out;
  }

  // ------------------------------------------------------------------ destination, snippet

  public static Map<String, Object> destination(Map<String, Object> destination,
      io.akka.redash.destinations.DestinationType type, boolean full) {
    var out = new LinkedHashMap<String, Object>();
    out.put("id", destination.get("id"));
    out.put("name", destination.get("name"));
    out.put("type", destination.get("type"));
    out.put("icon", type == null ? "fa-bullseye" : type.icon());
    if (full) {
      out.put("options", type == null
          ? destination.get("options")
          : io.akka.redash.domain.Configuration.mask(
              Json.asMap(destination.get("options")), type.configurationSchema()));
    }
    return out;
  }

  public static Map<String, Object> snippet(Map<String, Object> snippet,
      Map<String, Object> author) {
    var out = new LinkedHashMap<String, Object>();
    out.put("id", snippet.get("id"));
    out.put("trigger", snippet.get("trigger"));
    out.put("description", snippet.get("description"));
    out.put("snippet", snippet.get("snippet"));
    out.put("user", author == null ? null : user(author, false));
    out.put("updated_at", snippet.get("updated_at"));
    out.put("created_at", snippet.get("created_at"));
    return out;
  }

  // ------------------------------------------------------------------ result, job, event

  /**
   * A stored result.
   *
   * @param abbreviated the short form the source answers a *run* with when the caller is an
   *     API user: the data and the instant, and nothing that names the row. A request for a
   *     stored result answers the whole row whoever asks.
   */
  public static Map<String, Object> queryResult(Map<String, Object> result,
      boolean abbreviated) {
    if (abbreviated) {
      return Json.map("data", result.get("data"), "retrieved_at", result.get("retrieved_at"));
    }
    var out = new LinkedHashMap<String, Object>();
    out.put("id", result.get("id"));
    out.put("query_hash", result.get("query_hash"));
    out.put("query", result.get("query"));
    out.put("data", result.get("data"));
    out.put("data_source_id", result.get("data_source_id"));
    out.put("runtime", result.get("runtime"));
    out.put("retrieved_at", result.get("retrieved_at"));
    return out;
  }

  /**
   * The job document (SPEC-001 R83). A cancelled job reports **4**, not 5, because the
   * cancellation is read before the status is mapped.
   */
  public static Map<String, Object> job(Map<String, Object> job) {
    var status = String.valueOf(job.getOrDefault("status", "queued"));
    long code = switch (status) {
      case "started" -> 2;
      case "finished" -> 3;
      case "failed" -> 4;
      case "canceled" -> 5;
      case "deferred" -> 6;
      case "scheduled" -> 7;
      default -> 1;
    };
    Object updatedAt = "started".equals(status) && job.get("started_at") != null
        ? job.get("started_at") : 0L;
    Object result = job.get("result");
    Object error = "";
    if (Boolean.TRUE.equals(job.get("cancelled"))) {
      error = "Query cancelled by user.";
      code = 4;
      result = null;
    } else if (job.get("error") != null) {
      error = job.get("error");
      code = 4;
      result = null;
    }
    return Json.map("job", Json.map(
        "id", job.get("id"),
        "updated_at", updatedAt,
        "status", code,
        "error", error,
        "result", result,
        "query_result_id", result));
  }

  public static Map<String, Object> event(Map<String, Object> event) {
    var properties = Json.asMap(event.get("additional_properties"));
    var out = new LinkedHashMap<String, Object>();
    out.put("org_id", event.get("org_id"));
    out.put("user_id", event.get("user_id"));
    out.put("action", event.get("action"));
    out.put("object_type", event.get("object_type"));
    out.put("object_id", event.get("object_id"));
    out.put("created_at", event.get("created_at"));
    if (event.get("user_id") != null) {
      out.put("user_name", properties.getOrDefault("user_name", "User " + event.get("user_id")));
    } else {
      out.put("user_name", properties.getOrDefault("api_key", "Unknown"));
    }
    out.put("browser", userAgent(String.valueOf(properties.getOrDefault("user_agent", ""))));
    out.put("location", "Unknown");
    out.put("details", eventDetails(event, properties));
    return out;
  }

  static Map<String, Object> eventDetails(Map<String, Object> event,
      Map<String, Object> properties) {
    var objectType = String.valueOf(event.get("object_type"));
    var action = String.valueOf(event.get("action"));
    if ("data_source".equals(objectType) && "execute_query".equals(action)) {
      return Json.map("query", properties.get("query"), "data_source", event.get("object_id"));
    }
    if ("page".equals(objectType) && "view".equals(action)) {
      return Json.map("page", event.get("object_id"));
    }
    return Json.map("object_id", event.get("object_id"), "object_type", event.get("object_type"));
  }

  /**
   * How the event list describes a caller's client.
   *
   * <p>The original hands the header to a user-agent library and prints its
   * `device / operating system / browser` summary. This produces the same three parts,
   * in the same order, with the same wording for the clients that library recognises by
   * name — checked by running it against eight headers, from `Python-urllib/3.14` to a
   * mobile Safari. It carries the families and versions rather than that library's whole
   * signature database, so an unusual client falls back to `Other` where the original
   * would name it. The README lists that as a difference.
   */
  static String userAgent(String header) {
    if (header == null || header.isEmpty()) {
      return "Other / Other / Other";
    }
    var browser = browserOf(header);
    var system = systemOf(header);
    var device = deviceOf(header, browser, system);
    return device + " / " + system + " / " + browser;
  }

  /** The browser family and its version, trimmed the way the library trims it. */
  static String browserOf(String header) {
    for (String[] candidate : new String[][] {
        {"Edg/", "Edge"},
        {"OPR/", "Opera"},
        {"Firefox/", "Firefox"},
        {"Chrome/", "Chrome"},
        {"Python-urllib/", "Python-urllib"},
        {"curl/", "curl"},
        {"Wget/", "Wget"}}) {
      var version = versionAfter(header, candidate[0]);
      if (version != null) {
        var name = candidate[1];
        if ("Chrome".equals(name) && header.contains("Mobile")) {
          name = "Chrome Mobile";
        }
        return name + " " + trimVersion(version, 3);
      }
    }
    if (header.contains("Safari/")) {
      var version = versionAfter(header, "Version/");
      var name = header.contains("Mobile") ? "Mobile Safari" : "Safari";
      return version == null ? name : name + " " + trimVersion(version, 2);
    }
    return "Other";
  }

  /** The operating system, named as the library names it. */
  static String systemOf(String header) {
    if (header.contains("Windows NT 10.0")) {
      return "Windows 10";
    }
    if (header.contains("Windows NT 6.3")) {
      return "Windows 8.1";
    }
    if (header.contains("Windows NT 6.1")) {
      return "Windows 7";
    }
    if (header.contains("Windows")) {
      return "Windows";
    }
    if (header.contains("iPhone OS") || header.contains("CPU OS")) {
      var version = versionAfter(header.replace('_', '.'), "OS ");
      return version == null ? "iOS" : "iOS " + trimVersion(version, 2);
    }
    if (header.contains("Mac OS X")) {
      var version = versionAfter(header.replace('_', '.'), "Mac OS X ");
      return version == null ? "Mac OS X" : "Mac OS X " + trimVersion(version, 3);
    }
    if (header.contains("Android")) {
      var version = versionAfter(header, "Android ");
      return version == null ? "Android" : "Android " + trimVersion(version, 2);
    }
    if (header.contains("Linux") || header.contains("X11")) {
      return "Linux";
    }
    return "Other";
  }

  /** The device, which the library reports as a class rather than a model. */
  static String deviceOf(String header, String browser, String system) {
    if (header.startsWith("Python-urllib") || header.startsWith("python-requests")
        || header.contains("bot") || header.contains("Bot") || header.contains("spider")) {
      return "Spider";
    }
    if (header.contains("iPhone")) {
      return "iPhone";
    }
    if (header.contains("iPad")) {
      return "iPad";
    }
    if (header.contains("Android")) {
      return "Generic Smartphone";
    }
    if (!"Other".equals(system) && !"Other".equals(browser)) {
      return "PC";
    }
    return "Other";
  }

  /** The run of digits and dots that follows a marker, or null when it is not there. */
  static String versionAfter(String header, String marker) {
    int at = header.indexOf(marker);
    if (at < 0) {
      return null;
    }
    int from = at + marker.length();
    int to = from;
    while (to < header.length()
        && (Character.isDigit(header.charAt(to)) || header.charAt(to) == '.')) {
      to++;
    }
    return to == from ? null : header.substring(from, to);
  }

  /** At most the given number of parts, with a trailing empty part dropped. */
  static String trimVersion(String version, int parts) {
    var pieces = version.split("\\.");
    var out = new StringBuilder();
    for (int i = 0; i < Math.min(parts, pieces.length); i++) {
      if (pieces[i].isEmpty()) {
        break;
      }
      if (i > 0) {
        out.append('.');
      }
      out.append(pieces[i]);
    }
    return out.toString();
  }

  /**
   * Whether the caller has starred this thing, and when.
   *
   * <p>The source adds these two keys after serialising rather than inside it, and only for
   * a caller that is a person — an API user is never told. `starred_at` appears only where
   * there is a favourite, which is what makes ordering by it put unstarred rows last.
   */
  public static Map<String, Object> withFavourite(Map<String, Object> document,
      Map<String, Object> favourite) {
    document.put("is_favorite", favourite != null);
    if (favourite != null) {
      document.put("starred_at", favourite.get("created_at"));
    }
    return document;
  }

  /** Small helper: a list of rows through one serialiser. */
  public static List<Map<String, Object>> each(List<Map<String, Object>> rows,
      java.util.function.Function<Map<String, Object>, Map<String, Object>> serializer) {
    var out = new ArrayList<Map<String, Object>>(rows.size());
    for (Map<String, Object> row : rows) {
      out.add(serializer.apply(row));
    }
    return out;
  }
}

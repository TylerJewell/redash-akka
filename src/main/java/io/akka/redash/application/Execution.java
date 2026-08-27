package io.akka.redash.application;

import io.akka.redash.domain.AlertCondition;
import io.akka.redash.domain.AlertNotification;
import io.akka.redash.domain.Json;
import io.akka.redash.domain.QueryHash;
import io.akka.redash.destinations.Delivery;
import io.akka.redash.destinations.DestinationType;
import io.akka.redash.destinations.Destinations;
import io.akka.redash.destinations.Mail;
import io.akka.redash.queryrunner.Registry;
import io.akka.redash.queryrunner.RunResult;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * What happens when a query is actually run (SPEC-001 R110, R112 to R116).
 *
 * <p>The order is the argument, and it is the source's: the result is stored, then every
 * non-archived query sharing its cache key is pointed at it, then the alerts of exactly
 * those queries are checked. Getting that wrong shows up as an alert answering with the
 * previous run's data, which no table of returned values would catch.
 *
 * <p>The stored result travels into the alert check **by value**. A view read straight
 * after the write that feeds it is not guaranteed to see it (question-log row 5), and the
 * original's own comment where it enqueues its alert check — "make sure that alert sees the
 * latest query result" — says this is a place where seeing the newest one is the point.
 */
public final class Execution {

  /** How large a command payload the target will hold, from the target probe (row 1). */
  public static final int PAYLOAD_CEILING_BYTES = 1_048_475;

  /** What one run did, in the order it did it. */
  public record Outcome(
      boolean succeeded,
      String error,
      Long resultId,
      long runtimeMillis,
      List<Long> fannedOutTo,
      List<String> trace) {}

  private final Store store;
  private final Mail.Server mailServer;
  private final String host;
  private final String defaultSubjectTemplate;
  private final String defaultAlertBody;

  public Execution(Store store, Mail.Server mailServer, String host,
      String defaultSubjectTemplate, String defaultAlertBody) {
    this.store = store;
    this.mailServer = mailServer;
    this.host = host;
    this.defaultSubjectTemplate = defaultSubjectTemplate;
    this.defaultAlertBody = defaultAlertBody;
  }

  /**
   * Run the text against the data source and take the result all the way through.
   *
   * @param queryText the text **after** substitution and the automatic limit — what is
   *     hashed, cached and executed are all this string (SPEC-001 R78)
   */
  public Outcome run(long dataSourceId, String queryText, Map<String, Object> metadata) {
    var trace = new ArrayList<String>();
    var dataSource = store.find(Store.DATA_SOURCES, dataSourceId);
    if (dataSource == null) {
      return new Outcome(false, "Target data source not available.", null, 0, List.of(), trace);
    }
    var type = Registry.get(String.valueOf(dataSource.get("type")));
    if (type == null) {
      return new Outcome(false, "Unknown data source type.", null, 0, List.of(), trace);
    }

    var annotated = type.annotate(queryText, metadata);
    trace.add("run:" + type.type());
    var startedAt = System.nanoTime();
    RunResult result;
    try {
      result = type.run(annotated, Json.asMap(dataSource.get("options")));
    } catch (RuntimeException e) {
      result = RunResult.failed(String.valueOf(e.getMessage()));
    }
    long runtimeMillis = (System.nanoTime() - startedAt) / 1_000_000;

    if (result.isFailure()) {
      trace.add("failed");
      return new Outcome(false, result.error(), null, runtimeMillis, List.of(), trace);
    }

    var data = result.data();
    // SPEC-001 R155: a result the store will not hold is refused with the count that would
    // not fit, and nothing already cached is disturbed.
    var encoded = Json.dumps(data);
    if (encoded.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
        > PAYLOAD_CEILING_BYTES) {
      trace.add("refused:too-large");
      return new Outcome(false,
          "The result of " + result.rows().size() + " rows is larger than this deployment"
              + " will store.", null, runtimeMillis, List.of(), trace);
    }

    var hash = QueryHash.of(queryText);
    var retrievedAt = Json.instant(Instant.now());
    var stored = store.insert(Store.QUERY_RESULTS, Json.map(
        "org_id", dataSource.getOrDefault("org_id", 1L),
        "data_source_id", dataSourceId,
        "query_hash", hash,
        "query", queryText,
        "group_key", QueryHash.cacheKey(hash, String.valueOf(dataSourceId)),
        "data", data,
        "runtime", runtimeMillis / 1000.0,
        "retrieved_at", retrievedAt));
    trace.add("stored:" + stored.get("id"));

    var fannedOutTo = fanOut(stored, dataSourceId, hash, trace);
    checkAlerts(fannedOutTo, stored, trace);
    return new Outcome(true, null, number(stored.get("id")), runtimeMillis, fannedOutTo, trace);
  }

  /**
   * Point every non-archived query with this cache key at the new result, and clear its
   * failure counter (SPEC-001 R101, R110). An archived query is left where it was.
   */
  List<Long> fanOut(Map<String, Object> stored, long dataSourceId, String hash,
      List<String> trace) {
    var out = new ArrayList<Long>();
    for (Map<String, Object> query : store.byOrg(Store.QUERIES, 1L)) {
      if (Boolean.TRUE.equals(query.get("is_archived"))) {
        continue;
      }
      if (!hash.equals(query.get("query_hash"))
          || number(query.get("data_source_id")) != dataSourceId) {
        continue;
      }
      var updates = new LinkedHashMap<String, Object>();
      updates.put("latest_query_data_id", stored.get("id"));
      updates.put("schedule_failures", 0L);
      store.update(Store.QUERIES, query.get("id"), updates);
      out.add(number(query.get("id")));
    }
    trace.add("fanned-out:" + out.size());
    return out;
  }

  /** Every alert of exactly those queries, and no others. */
  void checkAlerts(List<Long> queryIds, Map<String, Object> result, List<String> trace) {
    for (Long queryId : queryIds) {
      for (Map<String, Object> alert : store.byParent(Store.ALERTS, queryId)) {
        evaluate(alert, result, false, trace);
      }
    }
  }

  /**
   * Evaluate one alert against one result and, when the rule says so, tell its subscribers.
   *
   * <p>The state and the trigger instant are written **before** the notification decision,
   * so a first `unknown → ok` and a muted alert suppress the message and not the state
   * (SPEC-001 R115).
   */
  public String evaluate(Map<String, Object> alert, Map<String, Object> result,
      boolean alwaysNotify, List<String> trace) {
    var options = Json.asMap(alert.get("options"));
    var data = result == null ? Map.<String, Object>of() : Json.asMap(result.get("data"));
    var rows = new ArrayList<Map<String, Object>>();
    for (Object row : Json.asList(data.get("rows"))) {
      rows.add(Json.asMap(row));
    }
    var columns = new ArrayList<String>();
    for (Object column : Json.asList(data.get("columns"))) {
      columns.add(String.valueOf(Json.asMap(column).get("name")));
    }

    var condition = new AlertCondition.Condition(
        String.valueOf(options.get("column")),
        String.valueOf(options.get("op")),
        options.get("value"),
        AlertCondition.Selector.parse(
            options.get("selector") == null ? null : String.valueOf(options.get("selector"))));
    var verdict = AlertCondition.evaluate(condition,
        result == null ? null : new AlertCondition.QueryResultData(rows, columns));

    var previous = new AlertNotification.AlertState(
        verdictOf(alert.get("state")),
        alert.get("last_triggered_at") == null
            ? null : Instant.parse(String.valueOf(alert.get("last_triggered_at"))),
        alert.get("rearm") instanceof Number n ? n.intValue() : null,
        Boolean.TRUE.equals(options.get("muted")));

    var outcome = AlertNotification.apply(previous, verdict, Instant.now());
    if (verdict == AlertCondition.Verdict.UNEVALUATED) {
      trace.add("alert:" + alert.get("id") + ":unevaluated");
      return null;
    }

    // The state and the stamp move before anybody is told, and only when the notification
    // rule says a notification is due — which is what makes a muted alert still change.
    if (outcome.stateChanged() || outcome.notified()) {
      var updates = new LinkedHashMap<String, Object>();
      updates.put("state", outcome.state().name().toLowerCase(Locale.ROOT));
      updates.put("last_triggered_at",
          outcome.lastTriggeredAt() == null ? null : Json.instant(outcome.lastTriggeredAt()));
      store.update(Store.ALERTS, alert.get("id"), updates);
    }

    var newState = outcome.state().name().toLowerCase(Locale.ROOT);
    if (outcome.notified() || alwaysNotify) {
      notifySubscribers(alert, newState, result, trace);
    }
    trace.add("alert:" + alert.get("id") + ":" + newState
        + (outcome.notified() ? ":notified" : ""));
    return newState;
  }

  /** A stored state name back into the verdict the rules work in. */
  static AlertCondition.Verdict verdictOf(Object stored) {
    var name = stored == null ? "unknown" : String.valueOf(stored).toUpperCase(Locale.ROOT);
    try {
      return AlertCondition.Verdict.valueOf(name);
    } catch (IllegalArgumentException e) {
      return AlertCondition.Verdict.UNKNOWN;
    }
  }

  /** Every subscription is attempted; one that fails costs its own delivery (R116). */
  public void notifySubscribers(Map<String, Object> alert, String newState,
      Map<String, Object> result, List<String> trace) {
    var query = store.find(Store.QUERIES, alert.get("query_id"));
    var options = Json.asMap(alert.get("options"));
    var rendered = AlertTemplates.render(alert, query, result, host, newState, defaultAlertBody);

    for (Map<String, Object> subscription :
        store.byParent(Store.ALERT_SUBSCRIPTIONS, number(alert.get("id")))) {
      try {
        DestinationType type;
        Map<String, Object> destinationOptions;
        if (subscription.get("destination_id") == null) {
          // No destination means the subscriber's own address, through the email one.
          var subscriber = store.find(Store.USERS, subscription.get("user_id"));
          type = Destinations.get("email");
          destinationOptions = Json.map("addresses",
              subscriber == null ? "" : subscriber.get("email"));
        } else {
          var destination = store.find(Store.DESTINATIONS, subscription.get("destination_id"));
          if (destination == null) {
            continue;
          }
          type = Destinations.get(String.valueOf(destination.get("type")));
          destinationOptions = Json.asMap(destination.get("options"));
        }
        var notification = new Delivery.Notification(
            number(alert.get("id")),
            String.valueOf(alert.get("name")),
            newState,
            rendered.subject(),
            rendered.body(),
            query == null ? 0 : number(query.get("id")),
            query == null ? "" : String.valueOf(query.get("name")),
            host,
            shortAlert(alert),
            Map.of(),
            defaultAlertBody == null ? "" : rendered.defaultBody(),
            defaultSubjectTemplate,
            mailServer);
        var failure = Delivery.notify(type, destinationOptions, notification);
        trace.add("notify:" + subscription.get("id") + (failure == null ? ":ok" : ":" + failure));
      } catch (RuntimeException e) {
        trace.add("notify:" + subscription.get("id") + ":error:" + e.getMessage());
      }
    }
  }

  /** The short alert document a webhook carries. */
  static Map<String, Object> shortAlert(Map<String, Object> alert) {
    var out = new LinkedHashMap<String, Object>();
    out.put("id", alert.get("id"));
    out.put("name", alert.get("name"));
    out.put("options", alert.get("options"));
    out.put("state", alert.get("state"));
    out.put("last_triggered_at", alert.get("last_triggered_at"));
    out.put("updated_at", alert.get("updated_at"));
    out.put("created_at", alert.get("created_at"));
    out.put("rearm", alert.get("rearm"));
    out.put("query_id", alert.get("query_id"));
    out.put("user_id", alert.get("user_id"));
    return out;
  }

  static long number(Object value) {
    return value instanceof Number n ? n.longValue() : 0;
  }

  /** Lower-cased, so a state written by a caller and one written here compare equal. */
  static String state(Object value) {
    return value == null ? "unknown" : String.valueOf(value).toLowerCase(Locale.ROOT);
  }
}

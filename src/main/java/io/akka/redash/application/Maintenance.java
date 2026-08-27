package io.akka.redash.application;

import io.akka.redash.api.Caller;
import io.akka.redash.api.QueryRunning;
import io.akka.redash.api.Service;
import io.akka.redash.destinations.Mail;
import io.akka.redash.domain.Json;
import io.akka.redash.domain.QueryHash;
import io.akka.redash.domain.RefreshSelection;
import io.akka.redash.domain.Settings;
import io.akka.redash.queryrunner.Registry;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The eight things redash's scheduler does on a timer, and nothing else
 * (SPEC-001 R117 to R123).
 *
 * <p>Each is a plain method so it can be driven from a test without a clock: the timers that
 * arm them live in {@link SchedulerAction}, and every one of them takes no argument and
 * answers nothing, exactly like the source's own job functions.
 */
public final class Maintenance {

  /** Where the refresh sweep writes what it did, which the admin screen reads back. */
  public static final String REFRESH_STATUS = "refresh";

  /** Where a request records that somebody was active, for the minute-by-minute sync. */
  public static final String LAST_ACTIVE = "last-active";

  private final Store store;
  private final Service service;
  private final Settings settings;

  public Maintenance(Service service) {
    this.service = service;
    this.store = service.store();
    this.settings = service.settings();
  }

  // ------------------------------------------------------------------ refresh queries

  /**
   * One pass over every scheduled query, enqueueing the ones that are due
   * (SPEC-001 R95 to R111, R118).
   *
   * <p>What it wrote down is part of the behaviour rather than a log: `/api/admin/queries/
   * outdated` answers the identifiers this pass enqueued and the instant it finished, not a
   * fresh decision about what is due now.
   */
  public void refreshQueries() {
    var startedAt = Instant.now();
    var candidates = new ArrayList<RefreshSelection.Candidate>();
    var byId = new LinkedHashMap<String, Map<String, Object>>();
    for (Map<String, Object> query : store.byOrg(Store.QUERIES, 1L)) {
      if (Boolean.TRUE.equals(query.get("is_archived"))) {
        continue;
      }
      var id = String.valueOf(query.get("id"));
      byId.put(id, query);
      candidates.add(candidate(id, query));
    }

    var tracker = new LinkedHashMap<String, Long>();
    for (Map<String, Object> entry : store.all(Store.TRACKER)) {
      var at = Service.instant(entry.get("executed_at"));
      if (at != null) {
        tracker.put(String.valueOf(entry.get("id")), at.toEpochMilli());
      }
    }

    var plan = RefreshSelection.plan(candidates, tracker, startedAt);
    for (String id : plan.disableByError()) {
      var query = byId.get(id);
      var schedule = new LinkedHashMap<String, Object>(Json.asMap(query.get("schedule")));
      schedule.put("disabled", true);
      store.update(Store.QUERIES, query.get("id"), Json.map("schedule", schedule));
    }

    var enqueued = new ArrayList<Object>();
    if (!settings.featureDisableRefreshQueries()) {
      for (String id : plan.enqueue()) {
        var query = byId.get(id);
        if (!shouldRefresh(query)) {
          continue;
        }
        try {
          enqueue(query);
          enqueued.add(Service.number(query.get("id")));
        } catch (RuntimeException e) {
          // The source catches, reports and carries on: one query that cannot be enqueued
          // must not stop the pass over the rest.
          trackFailure(query, "Could not enqueue query " + query.get("id") + " due to " + e);
        }
      }
    }

    store.put(Store.STATE, REFRESH_STATUS, Json.map(
        "started_at", Json.instant(startedAt),
        "outdated_queries_count", (long) enqueued.size(),
        "last_refresh_at", Json.instant(Instant.now()),
        "query_ids", enqueued));
  }

  private RefreshSelection.Candidate candidate(String id, Map<String, Object> query) {
    var result = store.find(Store.QUERY_RESULTS, query.get("latest_query_data_id"));
    return new RefreshSelection.Candidate(
        id,
        QueryHash.cacheKey(String.valueOf(query.get("query_hash")),
            String.valueOf(query.get("data_source_id"))),
        scheduleOf(query),
        query.get("schedule_failures") instanceof Number n ? n.intValue() : 0,
        result == null ? null : Service.instant(result.get("retrieved_at")),
        shouldRefresh(query));
  }

  /** The stored schedule document as the arithmetic wants it (SPEC-001 R104, R106). */
  static io.akka.redash.domain.Schedule scheduleOf(Map<String, Object> query) {
    var document = query.get("schedule");
    if (!(document instanceof Map<?, ?>)) {
      return null;
    }
    var stored = Json.asMap(document);
    var raw = stored.get("interval");
    Long seconds = null;
    if (raw instanceof Number number) {
      seconds = number.longValue();
    } else if (raw != null) {
      try {
        seconds = Long.parseLong(String.valueOf(raw).strip());
      } catch (NumberFormatException e) {
        seconds = null;
      }
    }
    return new io.akka.redash.domain.Schedule(
        seconds,
        raw == null ? null : String.valueOf(raw),
        stored.get("time") == null ? null : String.valueOf(stored.get("time")),
        stored.get("day_of_week") == null ? null : String.valueOf(stored.get("day_of_week")),
        stored.get("until") == null ? null : String.valueOf(stored.get("until")),
        Boolean.TRUE.equals(stored.get("disabled")));
  }

  /** The four reasons a due query is passed over anyway (SPEC-001 R105). */
  private boolean shouldRefresh(Map<String, Object> query) {
    var org = store.find(Store.ORGANIZATIONS, 1L);
    if (org != null && Boolean.TRUE.equals(org.get("is_disabled"))) {
      return false;
    }
    var dataSource = service.dataSourceById(query.get("data_source_id"));
    if (dataSource == null) {
      return false;
    }
    return dataSource.get("pause_reason") == null
        && !Boolean.TRUE.equals(dataSource.get("paused"));
  }

  private void enqueue(Map<String, Object> query) {
    var dataSource = service.dataSourceById(query.get("data_source_id"));
    var owner = service.userById(query.get("user_id"));
    var caller = owner == null
        ? Caller.anonymous(store.find(Store.ORGANIZATIONS, 1L))
        : Caller.person(owner, service.permissionsOf(owner), service.groupIdsOf(owner),
            store.find(Store.ORGANIZATIONS, 1L));

    var text = String.valueOf(query.get("query"));
    var defaults = new LinkedHashMap<String, Object>();
    for (Map<String, Object> parameter : service.parameterSchema(query)) {
      if (parameter.get("value") != null) {
        defaults.put(String.valueOf(parameter.get("name")), parameter.get("value"));
      }
    }
    if (!defaults.isEmpty()) {
      var applied = service.parameterized(query).apply(defaults);
      if (applied instanceof io.akka.redash.domain.Parameters.Applied.Ok ok) {
        text = ok.text();
      } else {
        trackFailure(query, "Skipping refresh of " + query.get("id")
            + " because of invalid parameters");
        return;
      }
    }
    var runner = service.runnerFor(dataSource);
    boolean autoLimit = Boolean.TRUE.equals(
        Json.asMap(query.get("options")).get("apply_auto_limit"));
    if (runner != null) {
      text = runner.applyAutoLimit(text, autoLimit);
    }
    QueryRunning.enqueue(service, caller, dataSource, text, QueryHash.of(text),
        Service.number(query.get("id")), true);
  }

  // ------------------------------------------------------------------ the other seven

  /** A query whose `until` has passed loses its schedule entirely (SPEC-001 R117). */
  public void emptySchedules() {
    var now = Instant.now();
    for (Map<String, Object> query : store.byOrg(Store.QUERIES, 1L)) {
      var schedule = Json.asMap(query.get("schedule"));
      var until = schedule.get("until");
      if (until == null) {
        continue;
      }
      var boundary = Service.instant(until);
      if (boundary == null) {
        boundary = endOfDay(String.valueOf(until));
      }
      if (boundary != null && !boundary.isAfter(now)) {
        store.update(Store.QUERIES, query.get("id"), Json.map("schedule", null));
      }
    }
  }

  /** `until` is a date, and the day it names is included. */
  static Instant endOfDay(String date) {
    try {
      return java.time.LocalDate.parse(date).plusDays(1).atStartOfDay(java.time.ZoneOffset.UTC)
          .toInstant();
    } catch (RuntimeException e) {
      return null;
    }
  }

  /** Every lock whose job is no longer queued or started is removed (SPEC-001 R121). */
  public void removeGhostLocks() {
    for (Map<String, Object> lock : store.all(Store.LOCKS)) {
      var jobId = String.valueOf(lock.get("job_id"));
      var job = jobDocument(jobId);
      var status = job == null ? null : String.valueOf(job.get("status"));
      boolean live = "queued".equals(status) || "started".equals(status);
      if (!live) {
        store.delete(Store.LOCKS, lock.get("id"));
      }
    }
  }

  private Map<String, Object> jobDocument(String jobId) {
    try {
      var job = store.client().forWorkflow(jobId)
          .method(QueryExecutionWorkflow::get).invoke();
      return job == null || job.isEmpty() ? null : job;
    } catch (RuntimeException e) {
      return null;
    }
  }

  /**
   * Results nothing points at and old enough to drop (SPEC-001 R120).
   *
   * <p>Both bounds are the source's and both matter: the age keeps a result somebody may
   * still have open in a browser, and the count keeps one pass from deleting everything.
   */
  public void cleanupQueryResults() {
    if (!settings.queryResultsCleanupEnabled()) {
      return;
    }
    var pointedAt = new java.util.HashSet<Long>();
    for (Map<String, Object> query : store.byOrg(Store.QUERIES, 1L)) {
      if (query.get("latest_query_data_id") != null) {
        pointedAt.add(Service.number(query.get("latest_query_data_id")));
      }
    }
    var cutoff = Instant.now().minus(settings.queryResultsCleanupMaxAgeDays(), ChronoUnit.DAYS);
    int deleted = 0;
    for (Map<String, Object> result : store.byOrg(Store.QUERY_RESULTS, 1L)) {
      if (deleted >= settings.queryResultsCleanupCount()) {
        break;
      }
      if (pointedAt.contains(Service.number(result.get("id")))) {
        continue;
      }
      var retrievedAt = Service.instant(result.get("retrieved_at"));
      if (retrievedAt != null && retrievedAt.isAfter(cutoff)) {
        continue;
      }
      store.delete(Store.QUERY_RESULTS, result.get("id"));
      deleted++;
    }
  }

  /** Every data source's schema, refreshed and cached (SPEC-001 R49, R123). */
  public void refreshSchemas() {
    var blacklist = new java.util.HashSet<Long>();
    var stored = store.find(Store.STATE, "schema-blacklist");
    if (stored != null) {
      for (Object id : Json.asList(stored.get("data_source_ids"))) {
        blacklist.add(Service.number(id));
      }
    }
    var org = store.find(Store.ORGANIZATIONS, 1L);
    if (org != null && Boolean.TRUE.equals(org.get("is_disabled"))) {
      return;
    }
    for (Map<String, Object> dataSource : store.byOrg(Store.DATA_SOURCES, 1L)) {
      long id = Service.number(dataSource.get("id"));
      if (blacklist.contains(id)) {
        continue;
      }
      if (dataSource.get("pause_reason") != null
          || Boolean.TRUE.equals(dataSource.get("paused"))) {
        continue;
      }
      var runner = Registry.get(String.valueOf(dataSource.get("type")));
      if (runner == null) {
        continue;
      }
      var result = runner.schema(Json.asMap(dataSource.get("options")));
      if (result.isFailure()) {
        continue;
      }
      store.put(Store.STATE, "schema:" + id, Json.map(
          "schema", io.akka.redash.api.Service.sortedSchema(result.rows()),
          "refreshed_at", Json.instant(Instant.now())));
    }
  }

  /**
   * Move the instants requests recorded into the people they belong to.
   *
   * <p>The source keeps these in redis and syncs them once a minute rather than writing a
   * row on every request; this does the same through one state record, for the same reason
   * — a write per request on a busy instance is the request's cost, not the record's.
   */
  public void syncUserDetails() {
    for (Map<String, Object> pending : store.all(Store.STATE)) {
      var key = String.valueOf(pending.get("id"));
      if (!key.startsWith(LAST_ACTIVE + ":")) {
        continue;
      }
      var user = service.userById(pending.get("user_id"));
      if (user != null) {
        store.update(Store.USERS, user.get("id"), Json.map("active_at", pending.get("at")));
      }
      store.delete(Store.STATE, key);
    }
  }

  /**
   * One email per person whose scheduled queries failed, grouping repeats
   * (SPEC-001 R122).
   */
  public void sendAggregatedErrors() {
    for (Map<String, Object> pending : store.all(Store.STATE)) {
      var key = String.valueOf(pending.get("id"));
      if (!key.startsWith("failures:")) {
        continue;
      }
      var userId = key.substring("failures:".length());
      var user = service.userById(Long.parseLong(userId));
      var failures = Json.asList(pending.get("failures"));
      store.delete(Store.STATE, key);
      if (user == null || failures.isEmpty()) {
        continue;
      }
      var counts = new LinkedHashMap<String, Long>();
      var unique = new LinkedHashMap<String, Map<String, Object>>();
      for (Object entry : failures) {
        var failure = Json.asMap(entry);
        // A NUL between the two, written as an escape rather than as itself: a control
        // character in a string literal is invisible in a diff and in an editor, and this
        // one is a deliberate separator — neither an identifier nor a message can contain
        // it, so two failures group together only when both halves match.
        // A NUL between the two, written as an escape rather than as itself: a control
        // character in a string literal is invisible in a diff and in an editor, and
        // this one is a deliberate separator — neither an identifier nor a message can
        // contain it, so two failures group together only when both halves match.
        var groupKey = failure.get("id") + "\0" + failure.get("message");
        counts.merge(groupKey, 1L, Long::sum);
        unique.put(groupKey, failure);
      }
      var rows = new ArrayList<Map<String, Object>>();
      unique.forEach((groupKey, failure) -> {
        long count = Service.number(failure.get("schedule_failures"));
        String comment = count > settings.maxFailureReportsPerQuery() * 0.75
            ? "NOTICE: This query has failed a total of " + count + " times.\n"
                + "        Reporting may stop when the query exceeds "
                + settings.maxFailureReportsPerQuery() + " overall failures."
            : null;
        rows.add(Json.map(
            "id", failure.get("id"),
            "name", failure.get("name"),
            "failed_at", failure.get("failed_at"),
            "failure_reason", failure.get("message"),
            "failure_count", counts.get(groupKey),
            "comment", comment));
      });
      var context = Json.map("failures", rows, "base_url", service.baseUrl());
      var renderer = service.templates();
      var subject = "Redash failed to execute " + rows.size() + " of your scheduled queries";
      Mail.send(service.mailServer(), List.of(String.valueOf(user.get("email"))), subject,
          renderer.render("emails/failures.html", context),
          renderer.render("emails/failures.txt", context));
    }
  }

  /** Whether a newer redash exists, which the shell shows as a banner. */
  public void versionCheck() {
    if (!settings.versionCheck()) {
      return;
    }
    store.put(Store.STATE, "version-check", Json.map(
        "checked_at", Json.instant(Instant.now()),
        "latest_version", Settings.VERSION));
  }

  // ------------------------------------------------------------------ failures

  /**
   * Record a failed scheduled refresh against its owner (SPEC-001 R122).
   *
   * <p>Three conditions gate it, and all three are the source's: the organisation must
   * subscribe to the report, the owner must not be disabled, and the query's own failure
   * count must be below the ceiling — past which a query stops being reported at all.
   */
  public void trackFailure(Map<String, Object> query, String message) {
    long failures = Service.number(query.get("schedule_failures")) + 1;
    store.update(Store.QUERIES, query.get("id"),
        Json.map("schedule_failures", failures, "last_failure", message));

    var org = store.find(Store.ORGANIZATIONS, 1L);
    boolean subscribed = Boolean.TRUE.equals(io.akka.redash.api.ClientConfig.setting(
        settings, org, "send_email_on_failed_scheduled_queries"));
    var owner = service.userById(query.get("user_id"));
    if (!subscribed || owner == null || owner.get("disabled_at") != null
        || failures >= settings.maxFailureReportsPerQuery()) {
      return;
    }
    var key = "failures:" + owner.get("id");
    var pending = store.find(Store.STATE, key);
    var collected = new ArrayList<>(pending == null ? List.of() : Json.asList(
        pending.get("failures")));
    collected.add(0, Json.map(
        "id", query.get("id"),
        "name", query.get("name"),
        "message", message,
        "schedule_failures", failures,
        "failed_at", Json.instant(Instant.now())));
    store.put(Store.STATE, key, Json.map("failures", collected));
  }
}

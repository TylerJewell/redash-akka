package io.akka.redash.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import akka.javasdk.testkit.TestKitSupport;
import io.akka.redash.api.Service;
import io.akka.redash.domain.Json;
import io.akka.redash.domain.Oracle;
import io.akka.redash.domain.Settings;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The eight jobs redash's scheduler runs, each driven directly (SPEC-001 R117 to R123).
 *
 * <p>They are driven rather than waited for. A test that arms a timer and sleeps is a test
 * of the timer; what these rules say is what each pass *decides*, and the interval it runs
 * on is a separate claim, checked below against the schedule the original itself registers.
 *
 * <p>None of this is reachable through HTTP on either side — it is what a worker does rather
 * than what a handler answers — so the original was asked directly.
 * `probes/probe_18_jobs.py` runs inside a container of redash, in its own application
 * context, and prints the schedule it registers, which locks its ghost-lock pass removes,
 * which results its cleanup deletes at four ages, and what its failure tracking records
 * under four conditions. Those answers are committed at
 * `src/test/resources/from-redash/probe_18_jobs.json` and are what the assertions below
 * read: an expectation written by hand would be this port's opinion of redash rather than
 * redash's own answer.
 *
 * <p>One thing the original registers has no counterpart here and is not compared: each job
 * carries a timeout and a result lifetime, which are how long its queue lets a job run and
 * how long it keeps the outcome. Those belong to the queue rather than to the job, and this
 * rebuild has a timer where the source has a queue. It is listed as a difference in the
 * README.
 */
class PeriodicJobsIntegrationTest extends TestKitSupport {

  static final String RECORDED = "probe_18_jobs.json";

  private Service service() {
    return new Service(componentClient);
  }

  private Maintenance maintenance() {
    return new Maintenance(service());
  }

  @Test
  void armsTheSameJobsOnTheSameIntervalsAsTheOriginal() {
    var recorded = Oracle.section(RECORDED, "schedule");
    var settings = new Settings(Map.of(
        "REDASH_VERSION_CHECK", String.valueOf(recorded.get("version_check")),
        "REDASH_QUERY_RESULTS_CLEANUP_ENABLED",
        String.valueOf(recorded.get("cleanup_enabled")),
        "REDASH_SCHEMAS_REFRESH_SCHEDULE",
        String.valueOf(number(recorded.get("schemas_refresh_minutes"))),
        "REDASH_SEND_FAILURE_EMAIL_INTERVAL",
        String.valueOf(number(recorded.get("failure_email_minutes")))));

    var wanted = new ArrayList<String>();
    var wantedIntervals = new ArrayList<Long>();
    for (Object entry : Json.asList(recorded.get("jobs"))) {
      var job = Json.asMap(entry);
      wanted.add(String.valueOf(job.get("name")));
      wantedIntervals.add(number(job.get("interval_seconds")));
    }

    var jobs = SchedulerAction.jobs(settings);
    assertEquals(wanted, jobs.stream().map(SchedulerAction.Job::name).toList(),
        "the same jobs, registered in the same order");
    assertEquals(wantedIntervals,
        jobs.stream().map(job -> job.interval().toSeconds()).toList(),
        "on the same intervals");
  }

  @Test
  void leavesTheTwoOptionalJobsOutWhenTheirSettingIsOff() {
    var settings = new Settings(Map.of(
        "REDASH_VERSION_CHECK", "false",
        "REDASH_QUERY_RESULTS_CLEANUP_ENABLED", "false"));
    var names = SchedulerAction.jobs(settings).stream().map(SchedulerAction.Job::name).toList();
    assertFalse(names.contains("version_check"));
    assertFalse(names.contains("cleanup_query_results"));
    assertEquals(6, names.size());
  }

  @Test
  void erasesTheScheduleOfAQueryWhoseUntilHasPassed() {
    var service = service();
    var store = service.store();
    service.setup("Probe", "Admin", "empty-schedules@example.com", "probe-password-1");

    var past = store.insert(Store.QUERIES, Json.map(
        "org_id", 1L, "name", "past", "query", "SELECT 1", "schedule",
        Json.map("interval", 86400L, "until", "2020-01-01"), "created_at", Service.now()));
    var future = store.insert(Store.QUERIES, Json.map(
        "org_id", 1L, "name", "future", "query", "SELECT 2", "schedule",
        Json.map("interval", 86400L, "until", "2999-01-01"), "created_at", Service.now()));

    maintenance().emptySchedules();

    assertNull(store.find(Store.QUERIES, past.get("id")).get("schedule"),
        "a query whose until has passed loses its schedule entirely");
    assertNotNull(store.find(Store.QUERIES, future.get("id")).get("schedule"),
        "a query whose until is ahead keeps it");
  }

  @Test
  void removesTheSameLocksTheOriginalRemoves() {
    var recorded = Oracle.section(RECORDED, "ghost_locks");
    var before = Json.asList(recorded.get("before"));
    var after = Json.asList(recorded.get("after"));
    assertTrue(Boolean.TRUE.equals(recorded.get("had_a_live_job")),
        "the recorded run had a live job to hold a lock against; without one the "
            + "difference between a ghost and a live lock was never put to the original");

    var service = service();
    var store = service.store();
    // The original's locks are redis keys named `query_hash_job:<cache key>`; here they are
    // rows of the lock table keyed by the same cache key. The names are the recorded ones
    // with that prefix taken off, so a lock added to the probe shows up here.
    var live = startAJobThatStaysInFlight(service);
    for (Object name : before) {
      var key = String.valueOf(name).replace("query_hash_job:", "");
      var jobId = after.contains(name) ? live : "a-job-that-never-existed-" + key;
      store.put(Store.LOCKS, key, Json.map("job_id", jobId, "created_at", Service.now()));
    }

    maintenance().removeGhostLocks();

    for (Object name : before) {
      var key = String.valueOf(name).replace("query_hash_job:", "");
      if (after.contains(name)) {
        assertNotNull(store.find(Store.LOCKS, key),
            "a lock naming a job that is still in flight is left alone: " + key);
      } else {
        assertNull(store.find(Store.LOCKS, key),
            "a lock naming a job nothing has heard of is removed: " + key);
      }
    }
  }

  /**
   * A query execution that is still running when the pass looks at it.
   *
   * <p>The lock the pass reads names a query-execution job — that is the only kind
   * `enqueue_query` writes one for — so the job here has to be one of those, and it has to
   * be genuinely in flight rather than merely started and finished. It runs
   * `SELECT pg_sleep(...)` against the same PostgreSQL the rest of the suite uses, and the
   * caller waits until the job reports `queued` or `started` before the pass runs, so what
   * is checked is the pass's decision rather than a race with it.
   */
  private String startAJobThatStaysInFlight(Service service) {
    var store = service.store();
    var source = store.insert(Store.DATA_SOURCES, Json.map(
        "org_id", 1L, "name", "in-flight", "type", "pg",
        "options", Json.map("host", pgHost(), "port", pgPort(),
            "user", "postgres", "dbname", "postgres"),
        "created_at", Service.now()));
    var jobId = "in-flight-for-the-lock-test";
    var request = new QueryExecutionWorkflow.Request(
        Service.number(source.get("id")), "SELECT pg_sleep(30)", null, null, false,
        Map.of(), "in-flight-cache-key", false);
    // Started on a thread of its own: the step runs the statement inline, so a caller that
    // waits for the call to come back waits for the sleep as well.
    var starter = new Thread(() -> {
      try {
        store.client().forWorkflow(jobId)
            .method(QueryExecutionWorkflow::start).invoke(request);
      } catch (RuntimeException ignored) {
        // The job is cancelled out from under this call when the test finishes with it.
      }
    });
    starter.setDaemon(true);
    starter.start();

    for (int attempt = 0; attempt < 200; attempt++) {
      var job = jobStatus(store, jobId);
      if ("queued".equals(job) || "started".equals(job)) {
        return jobId;
      }
      try {
        Thread.sleep(50);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        break;
      }
    }
    throw new IllegalStateException("no job stayed in flight to hold a lock against");
  }

  private static String jobStatus(Store store, String jobId) {
    try {
      var job = store.client().forWorkflow(jobId)
          .method(QueryExecutionWorkflow::get).invoke();
      return job == null || job.isEmpty() ? null : String.valueOf(job.get("status"));
    } catch (RuntimeException e) {
      return null;
    }
  }

  private static String pgHost() {
    var value = System.getenv("REDASH_PROBE_PG_HOST");
    return value == null || value.isBlank() ? "127.0.0.1" : value;
  }

  private static long pgPort() {
    var value = System.getenv("REDASH_PROBE_PG_PORT");
    return value == null || value.isBlank() ? 26602L : Long.parseLong(value);
  }

  @Test
  void deletesTheSameResultsTheOriginalDeletes() {
    var recorded = Oracle.section(RECORDED, "result_cleanup");
    var survived = Json.asMap(recorded.get("survived"));
    long maxAge = number(recorded.get("max_age_days"));

    var service = service();
    var store = service.store();
    // The four cases the probe put to the original, at the same four ages relative to the
    // same configured boundary.
    var ages = Map.of(
        "old_unused", maxAge + 1,
        "just_inside", maxAge - 1,
        "fresh_unused", 0L,
        "old_pointed_at", maxAge + 1);
    var made = new java.util.LinkedHashMap<String, Object>();
    for (var entry : ages.entrySet()) {
      var at = Json.instant(Instant.now().minus(entry.getValue(), ChronoUnit.DAYS));
      var row = store.insert(Store.QUERY_RESULTS, Json.map(
          "org_id", 1L, "query_hash", entry.getKey(), "data_source_id", 1L,
          "group_key", entry.getKey() + ":1", "retrieved_at", at, "data", Json.map()));
      made.put(entry.getKey(), row.get("id"));
    }
    store.insert(Store.QUERIES, Json.map(
        "org_id", 1L, "name", "keeper", "query", "SELECT 3",
        "latest_query_data_id", made.get("old_pointed_at"), "created_at", Service.now()));

    new Maintenance(new Service(store,
        new Settings(Map.of(
            "REDASH_QUERY_RESULTS_CLEANUP_ENABLED", "true",
            "REDASH_QUERY_RESULTS_CLEANUP_MAX_AGE", String.valueOf(maxAge)))))
        .cleanupQueryResults();

    for (var entry : made.entrySet()) {
      boolean shouldSurvive = Boolean.TRUE.equals(survived.get(entry.getKey()));
      if (shouldSurvive) {
        assertNotNull(store.find(Store.QUERY_RESULTS, entry.getValue()),
            entry.getKey() + " survives the sweep on the original and must here");
      } else {
        assertNull(store.find(Store.QUERY_RESULTS, entry.getValue()),
            entry.getKey() + " is deleted by the sweep on the original and must be here");
      }
    }
  }

  @Test
  void movesRecordedActivityOntoThePersonItBelongsTo() {
    var service = service();
    var store = service.store();
    var created = service.createUser("sync@example.com", "Sync", List.of(), null, false);
    var seenAt = Json.instant(Instant.now().minusSeconds(30));
    store.put(Store.STATE, Maintenance.LAST_ACTIVE + ":" + created.get("id"),
        Json.map("user_id", created.get("id"), "at", seenAt));

    maintenance().syncUserDetails();

    assertEquals(seenAt, store.find(Store.USERS, created.get("id")).get("active_at"));
    assertNull(store.find(Store.STATE, Maintenance.LAST_ACTIVE + ":" + created.get("id")),
        "the pending record is cleared, so the next pass has nothing to do");
  }

  @Test
  void recordsFailuresUnderTheSameFourConditionsTheOriginalDoes() {
    var recorded = Oracle.section(RECORDED, "failure_reports");
    var schedule = Oracle.section(RECORDED, "schedule");
    long ceiling = number(schedule.get("max_failure_reports"));

    var service = service();
    var store = service.store();
    var owner = service.createUser("failures@example.com", "Owner", List.of(), null, false);
    var query = store.insert(Store.QUERIES, Json.map(
        "org_id", 1L, "name", "flaky", "query", "SELECT 1",
        "user_id", owner.get("id"), "schedule_failures", 0L, "created_at", Service.now()));
    if (store.find(Store.ORGANIZATIONS, 1L) == null) {
      service.setup("Probe", "Admin", "failure-admin@example.com", "probe-password-1");
    }
    var pendingKey = "failures:" + owner.get("id");
    subscribe(store, true);

    var jobs = maintenance();
    // The same three the probe recorded: one message twice and another once.
    for (String message : List.of("first kind of trouble", "first kind of trouble",
        "second kind of trouble")) {
      jobs.trackFailure(store.find(Store.QUERIES, query.get("id")), message);
    }
    assertEquals(number(recorded.get("failures_after_three")),
        Service.number(store.find(Store.QUERIES, query.get("id")).get("schedule_failures")),
        "the counter rises once per failure");
    assertEquals(number(recorded.get("recorded")),
        Json.asList(store.find(Store.STATE, pendingKey).get("failures")).size(),
        "every one of them is collected, repeats included");
    assertEquals(Json.asList(recorded.get("messages")).stream().map(String::valueOf).sorted()
            .toList(),
        Json.asList(store.find(Store.STATE, pendingKey).get("failures")).stream()
            .map(entry -> String.valueOf(Json.asMap(entry).get("message"))).sorted().toList(),
        "with the messages the original kept");
    assertEquals(Json.asList(recorded.get("fields")).stream().map(String::valueOf).sorted()
            .toList(),
        Json.asMap(Json.asList(store.find(Store.STATE, pendingKey).get("failures")).get(0))
            .keySet().stream().sorted().toList(),
        "and the fields the original wrote down for each");

    // Past the ceiling, nothing more is collected.
    long held = Json.asList(store.find(Store.STATE, pendingKey).get("failures")).size();
    store.update(Store.QUERIES, query.get("id"), Json.map("schedule_failures", ceiling));
    jobs.trackFailure(store.find(Store.QUERIES, query.get("id")), "one too many");
    assertEquals(number(recorded.get("recorded_past_the_ceiling")),
        Json.asList(store.find(Store.STATE, pendingKey).get("failures")).size() - held,
        "a query already at the ceiling is counted and not collected");

    // Unsubscribed, nothing is collected at all.
    subscribe(store, false);
    store.update(Store.QUERIES, query.get("id"), Json.map("schedule_failures", 0L));
    held = Json.asList(store.find(Store.STATE, pendingKey).get("failures")).size();
    jobs.trackFailure(store.find(Store.QUERIES, query.get("id")), "while unsubscribed");
    assertEquals(number(recorded.get("recorded_while_unsubscribed")),
        Json.asList(store.find(Store.STATE, pendingKey).get("failures")).size() - held,
        "an organisation that has not asked for the mail collects nothing");

    subscribe(store, true);
    jobs.sendAggregatedErrors();
    var left = store.find(Store.STATE, pendingKey);
    assertEquals(number(recorded.get("left_after_the_report")),
        left == null ? 0 : Json.asList(left.get("failures")).size(),
        "the collection is cleared once the report is sent");
  }

  private static void subscribe(Store store, boolean wanted) {
    store.update(Store.ORGANIZATIONS, 1L, Json.map("settings", Json.map("settings",
        Json.map("send_email_on_failed_scheduled_queries", wanted))));
  }

  @Test
  void writesDownWhatTheRefreshSweepEnqueued() {
    var service = service();
    var store = service.store();

    maintenance().refreshQueries();

    var status = store.find(Store.STATE, Maintenance.REFRESH_STATUS);
    assertNotNull(status, "the sweep records what it did for the admin screen");
    assertTrue(status.containsKey("started_at"));
    assertTrue(status.containsKey("outdated_queries_count"));
    assertTrue(status.containsKey("last_refresh_at"));
    assertTrue(status.containsKey("query_ids"));
  }

  @Test
  void refusesAJobNameItDoesNotHave() {
    var thrown = org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
        () -> SchedulerAction.dispatch(maintenance(), "not-a-job"));
    assertTrue(thrown.getMessage().contains("not-a-job"));
  }

  private static long number(Object value) {
    return value instanceof Number n ? n.longValue() : 0;
  }
}

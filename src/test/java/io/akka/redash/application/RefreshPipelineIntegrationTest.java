package io.akka.redash.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import akka.javasdk.testkit.TestKitSupport;
import io.akka.redash.api.RedashEndpoint;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;

/**
 * SPEC-001 R16 and R24 through a real runtime and a real database.
 *
 * <p>Names end in {@code startsARuntime} so the split between test phases stays visible — a
 * class that starts a runtime and one that does not are not interchangeable, and a rename
 * that moves a class between phases is how a test silently stops running.
 *
 * <p>Two of these go through the HTTP layer rather than through the component client. A
 * method's non-path parameters are not bound to the query string on their own, and a method
 * that forgot to read one compiles clean and passes every component-client test, because
 * those never go through the HTTP layer at all.
 */
class RefreshPipelineIntegrationTest extends TestKitSupport {

  private static final String JDBC = "jdbc:postgresql://localhost:55432/redash_test";
  private static final Instant T0 = Instant.parse("2026-08-23T12:00:00Z");
  private static final AtomicInteger NEXT = new AtomicInteger();

  private static String id(String prefix) {
    return prefix + "-" + NEXT.incrementAndGet();
  }

  @BeforeEach
  void requireTheDatabase() {
    try (Connection c = DriverManager.getConnection(JDBC, "postgres", "postgres");
        Statement s = c.createStatement()) {
      s.execute("SELECT 1");
    } catch (Exception e) {
      Assumptions.abort(
          "the port's PostgreSQL is not up: docker run -d --name redash-akka-pg -p 55432:5432 "
              + "-e POSTGRES_PASSWORD=postgres -e POSTGRES_USER=postgres -e POSTGRES_DB=redash_test "
              + "postgres:16-alpine (" + e.getMessage() + ")");
    }
  }

  private RefreshPipeline pipeline() {
    return new RefreshPipeline(componentClient, DataSourceRegistry.all());
  }

  private String saveQuery(String queryId, String sql, Long intervalSeconds) {
    componentClient
        .forEventSourcedEntity(queryId)
        .method(QueryEntity::save)
        .invoke(
            new QueryEntity.Save(
                sql,
                "ds-1",
                intervalSeconds == null ? null : io.akka.redash.domain.Schedule.every(intervalSeconds)));
    return queryId;
  }

  private String createAlert(String alertId, String queryId, String operator, String threshold) {
    componentClient
        .forEventSourcedEntity(alertId)
        .method(AlertEntity::create)
        .invoke(new AlertEntity.Create("An alert", queryId, "value", operator, threshold, true, null, null, false));
    componentClient.forEventSourcedEntity(alertId).method(AlertEntity::subscribe).invoke("someone");
    return alertId;
  }

  /** The view catches up on its own schedule, so a read that depends on it is retried. */
  private void awaitAlertRow(String queryId) {
    await(
        () ->
            !componentClient.forView().method(AlertsView::forQuery).invoke(queryId).items().isEmpty(),
        "an alert row for " + queryId);
  }

  private void awaitQueryRow(String queryId) {
    await(
        () ->
            componentClient.forView().method(QueriesView::all).invoke().items().stream()
                .anyMatch(e -> e.queryId().equals(queryId)),
        "a query row for " + queryId);
  }

  private void await(java.util.function.BooleanSupplier condition, String what) {
    var deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
    while (System.nanoTime() < deadline) {
      if (condition.getAsBoolean()) {
        return;
      }
      try {
        Thread.sleep(100);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException(e);
      }
    }
    throw new AssertionError("timed out waiting for " + what);
  }

  // ---- R16: store, fan out, then check alerts, in that order ----

  @Test
  void aRefreshStoresFansOutAndChecksAlertsInThatOrder_startsARuntime() {
    var queryId = saveQuery(id("q"), "SELECT 10 AS value", 3600L);
    var alertId = createAlert(id("a"), queryId, ">", "5");
    awaitQueryRow(queryId);
    awaitAlertRow(queryId);

    var run = pipeline().execute(queryId, T0);

    assertTrue(run.succeeded(), run.error());
    assertEquals(1, run.rowCount());
    assertEquals(List.of(queryId), run.fannedOutTo());
    assertEquals(1, run.alerts().size());
    assertEquals("TRIGGERED", run.alerts().get(0).state());
    assertEquals(1, run.alerts().get(0).notified());

    assertEquals(
        List.of(
            "execute:tracker-written",
            "execute:ran",
            "execute:result-stored",
            "execute:fanned-out(1)",
            "execute:alerts-checked(1)",
            "execute:finished"),
        run.trace());

    var query = componentClient.forEventSourcedEntity(queryId).method(QueryEntity::get).invoke();
    assertEquals(run.resultId(), query.latestResultId());
    assertEquals(0, query.scheduleFailures());
    assertEquals(alertId, run.alerts().get(0).alertId());
  }

  @Test
  void oneResultAnswersEveryQuerySharingItsCacheKeyAndNoOthers_startsARuntime() {
    var sql = "SELECT 7 AS value";
    var target = saveQuery(id("q"), sql, 3600L);
    var sameHash = saveQuery(id("q"), "/* a note */SELECT   7 AS value", 3600L);
    var archived = saveQuery(id("q"), sql, 3600L);
    var different = saveQuery(id("q"), "SELECT 8 AS value", 3600L);
    componentClient.forEventSourcedEntity(archived).method(QueryEntity::archive).invoke();
    for (String q : List.of(target, sameHash, archived, different)) {
      awaitQueryRow(q);
    }
    // the archive has to have reached the view before the fan-out reads it
    await(
        () ->
            componentClient.forView().method(QueriesView::all).invoke().items().stream()
                .anyMatch(e -> e.queryId().equals(archived) && e.archived() == 1),
        "the archived flag in the view");

    var run = pipeline().execute(target, T0);

    assertTrue(run.fannedOutTo().contains(target));
    assertTrue(run.fannedOutTo().contains(sameHash), "a comment-only difference hashes the same");
    assertFalse(run.fannedOutTo().contains(archived), "an archived query is not a fan-out target");
    assertFalse(run.fannedOutTo().contains(different));

    var untouched = componentClient.forEventSourcedEntity(different).method(QueryEntity::get).invoke();
    assertEquals(null, untouched.latestResultId());
  }

  @Test
  void aFailedRunRaisesTheCounterAndStoresNothing_startsARuntime() {
    var queryId = saveQuery(id("q"), "SELECT * FROM a_table_that_is_not_there", 3600L);
    awaitQueryRow(queryId);

    var run = pipeline().execute(queryId, T0);

    assertFalse(run.succeeded());
    assertNotNull(run.error());
    assertTrue(run.fannedOutTo().isEmpty());
    assertEquals(
        1, componentClient.forEventSourcedEntity(queryId).method(QueryEntity::get).invoke().scheduleFailures());
  }

  // ---- R24: a result the store will not hold ----

  @Test
  void aResultTooLargeToStoreIsRefusedAndCountedAsAFailure_startsARuntime() {
    // 1,048,475 bytes is the ceiling the target probe found; 40,000 rows of a 64-character
    // cell is comfortably past it and comfortably inside what PostgreSQL will generate.
    var sql = "SELECT repeat('x', 64) AS value FROM generate_series(1, 40000)";
    var queryId = saveQuery(id("q"), sql, 3600L);
    awaitQueryRow(queryId);

    var run = pipeline().execute(queryId, T0);

    assertFalse(run.succeeded());
    assertTrue(run.error().contains("past the " + RefreshPipeline.PAYLOAD_CEILING_BYTES));
    assertEquals(40000, run.rowCount(), "the row count that would not fit is reported");
    assertEquals("execute:too-large", run.trace().get(run.trace().size() - 1));

    var query = componentClient.forEventSourcedEntity(queryId).method(QueryEntity::get).invoke();
    assertEquals(1, query.scheduleFailures(), "a refusal is a failed refresh");
    assertEquals(null, query.latestResultId());
  }

  @Test
  void aRefusalLeavesWhatWasAlreadyCachedAlone_startsARuntime() {
    var smallSql = "SELECT 3 AS value";
    var queryId = saveQuery(id("q"), smallSql, 3600L);
    awaitQueryRow(queryId);
    var first = pipeline().execute(queryId, T0);
    assertTrue(first.succeeded());
    var cacheKey = first.cacheKey();

    // the same cache key, now asked for something that will not fit
    componentClient
        .forEventSourcedEntity(queryId)
        .method(QueryEntity::save)
        .invoke(
            new QueryEntity.Save(
                "SELECT repeat('x', 64) AS value FROM generate_series(1, 40000)",
                "ds-1",
                io.akka.redash.domain.Schedule.every(3600)));
    var second = pipeline().execute(queryId, T0.plusSeconds(60));
    assertFalse(second.succeeded());

    var cached = componentClient.forKeyValueEntity(cacheKey).method(ResultEntity::get).invoke();
    assertEquals(1, cached.storedCount(), "nothing was written over the earlier answer");
    assertEquals(1, cached.rows().size());
  }

  // ---- R15 through the HTTP layer, where a query parameter is not bound on its own ----

  @Test
  void theFreshnessWindowIsReadFromTheQueryString_startsARuntime() {
    var queryId = saveQuery(id("q"), "SELECT 5 AS value", 3600L);
    awaitQueryRow(queryId);
    var run = pipeline().execute(queryId, T0);
    var cacheKey = run.cacheKey();

    // asked two minutes after the result was retrieved
    var asAt = T0.plusSeconds(120);

    var zero = getCached(cacheKey, 0, asAt);
    assertFalse(zero.freshEnough(), "a maxAge of zero reuses nothing");
    assertEquals(0, zero.maxAgeSeconds());

    assertFalse(getCached(cacheKey, 60, asAt).freshEnough());
    assertTrue(getCached(cacheKey, 300, asAt).freshEnough());
    assertTrue(getCached(cacheKey, -1, asAt).freshEnough(), "-1 accepts any age");
  }

  private RedashEndpoint.CachedResult getCached(String cacheKey, long maxAge, Instant asAt) {
    return httpClient
        .GET("/api/results/" + cacheKey + "?maxAge=" + maxAge + "&at=" + asAt)
        .responseBodyAs(RedashEndpoint.CachedResult.class)
        .invoke()
        .body();
  }

  // ---- R23: the pass that erases a schedule rather than passing over it ----

  /**
   * The three texts here are distinct from every other test's. A cache key is the query
   * hash and the data source, so two tests using the same SQL share one result entity, and
   * the one that counts how many times it has been written sees the other's write.
   */
  @Test
  void aScheduleWhoseUntilHasBeenReachedIsErasedRatherThanSkipped_startsARuntime() {
    var reached = id("q");
    var ahead = id("q");
    var noUntil = id("q");
    componentClient
        .forEventSourcedEntity(reached)
        .method(QueryEntity::save)
        .invoke(
            new QueryEntity.Save(
                "SELECT 21 AS value", "ds-1", io.akka.redash.domain.Schedule.every(3600).withUntil("2026-08-22")));
    componentClient
        .forEventSourcedEntity(ahead)
        .method(QueryEntity::save)
        .invoke(
            new QueryEntity.Save(
                "SELECT 22 AS value", "ds-1", io.akka.redash.domain.Schedule.every(3600).withUntil("2026-08-24")));
    componentClient
        .forEventSourcedEntity(noUntil)
        .method(QueryEntity::save)
        .invoke(new QueryEntity.Save("SELECT 23 AS value", "ds-1", io.akka.redash.domain.Schedule.every(3600)));
    for (String q : List.of(reached, ahead, noUntil)) {
      awaitQueryRow(q);
    }

    var erased =
        httpClient
            .POST("/api/schedules/erase-past-until?at=" + T0)
            .responseBodyAsListOf(String.class)
            .invoke()
            .body();

    assertTrue(erased.contains(reached));
    assertFalse(erased.contains(ahead), "an until still ahead is left alone");
    assertFalse(erased.contains(noUntil), "a schedule with no until is left alone");

    assertEquals(
        null,
        componentClient.forEventSourcedEntity(reached).method(QueryEntity::get).invoke().schedule(),
        "the schedule is erased, not disabled");
    assertNotNull(
        componentClient.forEventSourcedEntity(ahead).method(QueryEntity::get).invoke().schedule());
  }

  @Test
  void aSweepDrivenOverHttpEnqueuesTheDueQueriesAndReportsTheOthers_startsARuntime() {
    var due = saveQuery(id("q"), "SELECT 11 AS value", 3600L);
    var unscheduled = saveQuery(id("q"), "SELECT 12 AS value", null);
    awaitQueryRow(due);
    awaitQueryRow(unscheduled);

    var outcome =
        httpClient
            .POST("/api/sweep?at=" + T0)
            .responseBodyAs(RefreshPipeline.SweepOutcome.class)
            .invoke()
            .body();

    assertTrue(outcome.enqueued().contains(due), "a query that has never run is due");
    assertFalse(outcome.enqueued().contains(unscheduled), "a query with no schedule is not swept");
    assertTrue(outcome.disabledByError().isEmpty());
  }
}

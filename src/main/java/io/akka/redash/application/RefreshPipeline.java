package io.akka.redash.application;

import akka.javasdk.client.ComponentClient;
import io.akka.redash.domain.EnqueueLock;
import io.akka.redash.domain.RefreshSelection;
import io.akka.redash.domain.StoredResult;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The sweep, the run, and the alert check — the whole path a scheduled query takes.
 *
 * <p>The order is the argument: the result is stored, then every non-archived query sharing
 * its cache key is pointed at it, then the alerts of exactly those queries are checked
 * (SPEC-001 R16). Getting that wrong shows up as an alert answering a question with the
 * previous run's data, which no table of returned values would catch.
 *
 * <p>The stored result travels into the alert check <em>by value</em>. A view read straight
 * after the write that feeds it is not guaranteed to see it (question-log row 5), and the
 * original's own comment where its alert check is enqueued — "make sure that alert sees the
 * latest query result" — says this is a place where seeing the newest one is the point.
 */
public final class RefreshPipeline {

  /** How large a command payload the target will hold, from the target probe (row 1). */
  public static final int PAYLOAD_CEILING_BYTES = 1_048_475;

  /** What one refresh did, in the order it did it. */
  public record RunOutcome(
      String queryId,
      String cacheKey,
      boolean succeeded,
      String error,
      String resultId,
      int rowCount,
      List<String> fannedOutTo,
      List<AlertOutcome> alerts,
      List<String> trace) {}

  public record AlertOutcome(String alertId, String state, boolean stateChanged, int notified, int notificationFailures) {}

  /** What one sweep did. */
  public record SweepOutcome(
      List<String> enqueued, List<String> disabledByError, List<RunOutcome> runs, List<String> trace) {}

  private final ComponentClient componentClient;
  private final Map<String, QueryRunner.DataSource> dataSources;
  private final Map<String, EnqueueLock.JobState> lockJobStates = new LinkedHashMap<>();
  private final Map<String, Boolean> lockHeld = new LinkedHashMap<>();

  public RefreshPipeline(ComponentClient componentClient, Map<String, QueryRunner.DataSource> dataSources) {
    this.componentClient = componentClient;
    this.dataSources = dataSources;
  }

  /**
   * The state the job named by a lock is in. In the original this is read from the job
   * store; here it is set by whatever put the job there, and a cache key with no entry has
   * no job in flight.
   */
  public void setJobState(String cacheKey, EnqueueLock.JobState state) {
    lockJobStates.put(cacheKey, state);
  }

  public boolean lockHeldFor(String cacheKey) {
    return Boolean.TRUE.equals(lockHeld.get(cacheKey));
  }

  /** R17: whether this request enqueues anything, and what it leaves the lock as. */
  public EnqueueLock.Outcome requestRefresh(String cacheKey) {
    var held = lockHeldFor(cacheKey);
    var outcome = EnqueueLock.decide(held ? lockJobStates.getOrDefault(cacheKey, EnqueueLock.JobState.QUEUED) : null);
    if (outcome.clearedStaleLock()) {
      lockHeld.remove(cacheKey);
    }
    if (outcome.writeLock()) {
      lockHeld.put(cacheKey, true);
      lockJobStates.put(cacheKey, EnqueueLock.JobState.QUEUED);
    }
    return outcome;
  }

  public SweepOutcome sweep(Instant now) {
    var trace = new ArrayList<String>();

    var scheduled = componentClient.forView().method(QueriesView::scheduled).invoke();
    trace.add("sweep:read-scheduled(" + scheduled.items().size() + ")");

    var tracker = componentClient.forKeyValueEntity(ExecutionTrackerEntity.ID)
        .method(ExecutionTrackerEntity::get).invoke();
    trace.add("sweep:read-tracker(" + tracker.startedAtMillis().size() + ")");

    var candidates = new ArrayList<RefreshSelection.Candidate>(scheduled.items().size());
    for (QueriesView.Entry entry : scheduled.items()) {
      var dataSource = dataSources.get(entry.dataSourceId());
      candidates.add(
          new RefreshSelection.Candidate(
              entry.queryId(),
              entry.cacheKey(),
              entry.schedule(),
              entry.scheduleFailures(),
              entry.latestResultAtMillis() == 0 ? null : Instant.ofEpochMilli(entry.latestResultAtMillis()),
              dataSource != null && !dataSource.paused()));
    }

    var plan = RefreshSelection.plan(candidates, tracker.startedAtMillis(), now);
    trace.add("sweep:planned(enqueue=" + plan.enqueue().size() + ",disable=" + plan.disableByError().size() + ")");

    for (String queryId : plan.disableByError()) {
      componentClient
          .forEventSourcedEntity(queryId)
          .method(QueryEntity::disableScheduleByError)
          .invoke("the schedule could not be read");
      trace.add("sweep:disabled-by-error:" + queryId);
    }

    var runs = new ArrayList<RunOutcome>(plan.enqueue().size());
    for (String queryId : plan.enqueue()) {
      trace.add("sweep:enqueued:" + queryId);
      runs.add(execute(queryId, now));
    }

    return new SweepOutcome(plan.enqueue(), plan.disableByError(), List.copyOf(runs), List.copyOf(trace));
  }

  /** One refresh, from the tracker entry that starts it to the alerts that end it. */
  public RunOutcome execute(String queryId, Instant now) {
    var trace = new ArrayList<String>();

    var query = componentClient.forEventSourcedEntity(queryId).method(QueryEntity::get).invoke();

    // The tracker entry is written before the query runs, not after it finishes, so a
    // sweep while this is still going sees the query as having run (R14).
    componentClient
        .forKeyValueEntity(ExecutionTrackerEntity.ID)
        .method(ExecutionTrackerEntity::started)
        .invoke(new ExecutionTrackerEntity.Started(queryId, now));
    trace.add("execute:tracker-written");

    var dataSource = dataSources.get(query.dataSourceId());
    var result =
        dataSource == null
            ? new QueryRunner.Result(null, null, "no data source: " + query.dataSourceId())
            : QueryRunner.run(dataSource, query.queryText());
    trace.add("execute:ran");

    if (result.failed()) {
      componentClient.forEventSourcedEntity(queryId).method(QueryEntity::recordFailure).invoke(result.error());
      trace.add("execute:failure-recorded");
      return new RunOutcome(queryId, query.cacheKey(), false, result.error(), null, 0, List.of(), List.of(),
          List.copyOf(trace));
    }

    long size = approximatePayloadBytes(result.rows());
    if (size > PAYLOAD_CEILING_BYTES) {
      // R24. The store would refuse this loudly and nothing would be written; refusing
      // here first makes it a failed refresh, which the backoff already has a rule for,
      // and leaves whatever was cached for this key exactly as it was.
      var message =
          "result of %d row(s) is about %d bytes, past the %d the store will hold"
              .formatted(result.rows().size(), size, PAYLOAD_CEILING_BYTES);
      componentClient.forEventSourcedEntity(queryId).method(QueryEntity::recordFailure).invoke(message);
      trace.add("execute:too-large");
      return new RunOutcome(queryId, query.cacheKey(), false, message, null, result.rows().size(), List.of(),
          List.of(), List.copyOf(trace));
    }

    var stored =
        componentClient
            .forKeyValueEntity(query.cacheKey())
            .method(ResultEntity::store)
            .invoke(
                new ResultEntity.Store(
                    query.queryHash(),
                    query.dataSourceId(),
                    query.queryText(),
                    result.rows(),
                    result.columns(),
                    0,
                    now));
    trace.add("execute:result-stored");

    var storedResult = stored.asStoredResult();
    var fannedOutTo = fanOut(query.cacheKey(), stored.cacheKey(), now, trace);
    var alerts = checkAlerts(fannedOutTo, storedResult, now, trace);
    trace.add("execute:finished");

    return new RunOutcome(
        queryId, query.cacheKey(), true, null, stored.cacheKey(), result.rows().size(),
        fannedOutTo, alerts, List.copyOf(trace));
  }

  /** R16: every non-archived query sharing the cache key is pointed at the new result. */
  private List<String> fanOut(String cacheKey, String resultId, Instant now, List<String> trace) {
    var sharing = componentClient.forView().method(QueriesView::sharingCacheKey).invoke(cacheKey);
    var ids = new ArrayList<String>(sharing.items().size());
    for (QueriesView.Entry entry : sharing.items()) {
      componentClient
          .forEventSourcedEntity(entry.queryId())
          .method(QueryEntity::recordSuccess)
          .invoke(new QueryEntity.RefreshSucceeded(resultId, now));
      ids.add(entry.queryId());
    }
    trace.add("execute:fanned-out(" + ids.size() + ")");
    return List.copyOf(ids);
  }

  /** R16: the alerts of exactly the queries the result was fanned out to, and no others. */
  private List<AlertOutcome> checkAlerts(
      List<String> queryIds, StoredResult result, Instant now, List<String> trace) {
    var outcomes = new ArrayList<AlertOutcome>();
    for (String queryId : queryIds) {
      var alerts = componentClient.forView().method(AlertsView::forQuery).invoke(queryId);
      for (AlertsView.Entry alert : alerts.items()) {
        var verdict =
            componentClient
                .forEventSourcedEntity(alert.alertId())
                .method(AlertEntity::evaluate)
                .invoke(new AlertEntity.Evaluate(result, now, List.of()));
        outcomes.add(
            new AlertOutcome(
                alert.alertId(), verdict.state(), verdict.stateChanged(), verdict.notified(),
                verdict.notificationFailures()));
      }
    }
    trace.add("execute:alerts-checked(" + outcomes.size() + ")");
    return List.copyOf(outcomes);
  }

  /**
   * How large the rows will be once serialised, near enough to decide whether to try. The
   * store's own ceiling is on the encoded command; this counts the cell text plus a little
   * for the JSON around each one, which errs high rather than low — the failure mode of
   * erring low is discovering the ceiling from the store instead.
   */
  static long approximatePayloadBytes(List<Map<String, Object>> rows) {
    long total = 0;
    for (Map<String, Object> row : rows) {
      for (Map.Entry<String, Object> cell : row.entrySet()) {
        total += cell.getKey().getBytes(StandardCharsets.UTF_8).length + 6;
        total += String.valueOf(cell.getValue()).getBytes(StandardCharsets.UTF_8).length + 2;
      }
      total += 2;
    }
    return total;
  }
}

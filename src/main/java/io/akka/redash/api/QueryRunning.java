package io.akka.redash.api;

import io.akka.redash.application.QueryExecutionWorkflow;
import io.akka.redash.application.Store;
import io.akka.redash.domain.EnqueueLock;
import io.akka.redash.domain.Json;
import io.akka.redash.domain.Parameters;
import io.akka.redash.domain.QueryHash;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Running a query, from a caller's request to a job or a cached answer
 * (SPEC-001 R76 to R83, R109, R111).
 *
 * <p>Five refusals come first and each has its own wording, because the front end shows
 * them. Then the parameters are applied, then the automatic limit — and it is that text,
 * not the stored text, that is hashed, cached and executed. A cached answer short-circuits
 * everything after it.
 */
public final class QueryRunning {

  /** The five refusals, with the status each carries. */
  public record Refusal(int status, String message) {}

  public static final Refusal UNSAFE_WHEN_SHARED = new Refusal(403,
      "This query contains potentially unsafe parameters and cannot be executed on a shared"
          + " dashboard or an embedded visualization.");
  public static final Refusal UNSAFE_ON_VIEW_ONLY = new Refusal(403,
      "This query contains potentially unsafe parameters and cannot be executed with read-only"
          + " access to this data source.");
  public static final Refusal NO_PERMISSION = new Refusal(403,
      "You do not have permission to run queries with this data source.");
  public static final Refusal SELECT_DATA_SOURCE = new Refusal(401,
      "Please select data source to run this query.");
  public static final Refusal NO_DATA_SOURCE = new Refusal(401,
      "Target data source not available.");

  private QueryRunning() {}

  /** The body a refusal answers: a job document carrying status 4 and the message. */
  public static Map<String, Object> asBody(Refusal refusal) {
    return Json.map("job", Json.map("status", 4L, "error", refusal.message()));
  }

  public static Http.Refused refuse(Refusal refusal) {
    return new Http.Refused(refusal.status(), asBody(refusal));
  }

  /**
   * The whole of the source's `run_query`.
   *
   * @param query the parameterised query, already built from the text and its schema
   * @param values the parameter values the caller supplied
   * @param dataSource the data source, or null
   * @param queryId the saved query this run belongs to, or {@code "adhoc"}
   * @param shouldApplyAutoLimit whether the query asks for the automatic row limit
   * @param maxAge seconds of grace on a cached answer; -1 accepts any age, 0 accepts nothing
   */
  public static Map<String, Object> run(Service service, Caller caller, Parameters query,
      Map<String, Object> values, Map<String, Object> dataSource, Object queryId,
      boolean shouldApplyAutoLimit, long maxAge, java.util.function.BiConsumer<Caller,
      Map<String, Object>> recordEvent) {
    if (dataSource == null) {
      throw refuse(NO_DATA_SOURCE);
    }
    if (dataSource.get("pause_reason") != null || Boolean.TRUE.equals(dataSource.get("paused"))) {
      var reason = dataSource.get("pause_reason");
      var message = reason == null || String.valueOf(reason).isEmpty()
          ? dataSource.get("name") + " is paused. Please try later."
          : dataSource.get("name") + " is paused (" + reason + "). Please try later.";
      throw new Http.Refused(400, asBody(new Refusal(400, message)).get("job") == null
          ? Json.map("message", message)
          : Json.map("job", Json.map("status", 4L, "error", message)));
    }

    var applied = query.apply(values);
    if (applied instanceof Parameters.Applied.Invalid invalid) {
      throw Http.badRequest(invalid.message());
    }
    if (applied instanceof Parameters.Applied.Detached detached) {
      throw Http.badRequest(detached.message());
    }
    var ok = (Parameters.Applied.Ok) applied;

    var runner = service.runnerFor(dataSource);
    var text = runner == null ? ok.text() : runner.applyAutoLimit(ok.text(), shouldApplyAutoLimit);

    if (!ok.missing().isEmpty()) {
      var message = "Missing parameter value for: " + String.join(", ", ok.missing());
      throw new Http.Refused(400, Json.map("job", Json.map("status", 4L, "error", message)));
    }

    var hash = QueryHash.of(text);
    Map<String, Object> cached = maxAge == 0
        ? null
        : service.latestResultFor(hash, dataSource.get("id"), maxAge);

    recordEvent.accept(caller, Json.map(
        "action", "execute_query",
        "cache", cached != null ? "hit" : "miss",
        "object_id", dataSource.get("id"),
        "object_type", "data_source",
        "query", text,
        "query_id", queryId,
        "parameters", values));

    if (cached != null) {
      return Json.map("query_result",
          Serializers.queryResult(cached, caller.isApiUser()));
    }
    return enqueue(service, caller, dataSource, text, hash, queryId, false);
  }

  /**
   * Put the run on its way, unless the same cache key already has one in flight
   * (SPEC-001 R111).
   *
   * <p>The lock's own surprise is reproduced: when the job a lock names is no longer in
   * flight, the lock is cleared and the replacement is enqueued **without a lock being
   * written for it**, so the request after that enqueues a second replacement. It is the
   * source's settled behaviour rather than an absence, and a repair here would disagree with
   * the original on the third call of every such sequence (SPEC-001 D-3).
   */
  public static Map<String, Object> enqueue(Service service, Caller caller,
      Map<String, Object> dataSource, String text, String hash, Object queryId,
      boolean scheduled) {
    var store = service.store();
    var cacheKey = QueryHash.cacheKey(hash, String.valueOf(dataSource.get("id")));
    var lock = store.find(Store.LOCKS, cacheKey);

    EnqueueLock.JobState heldState = null;
    Map<String, Object> heldJob = null;
    if (lock != null) {
      heldJob = jobDocument(service, String.valueOf(lock.get("job_id")));
      heldState = stateOf(heldJob);
    }

    var outcome = EnqueueLock.decide(heldState);
    if (outcome.clearedStaleLock()) {
      store.delete(Store.LOCKS, cacheKey);
    }
    if (!outcome.enqueue()) {
      return Serializers.job(heldJob == null ? Json.map("id", lock.get("job_id")) : heldJob);
    }

    var jobId = java.util.UUID.randomUUID().toString();
    var metadata = new LinkedHashMap<String, Object>();
    metadata.put("Username", caller.actualUser());
    metadata.put("query_id", queryId);
    metadata.put("Job ID", jobId);
    metadata.put("Query Hash", hash);
    metadata.put("Scheduled", scheduled);
    metadata.put("Queue", scheduled
        ? dataSource.getOrDefault("scheduled_queue_name", "scheduled_queries")
        : dataSource.getOrDefault("queue_name", "queries"));

    Long numericQueryId = queryId instanceof Number n ? n.longValue() : null;
    var request = new QueryExecutionWorkflow.Request(
        Service.number(dataSource.get("id")), text, numericQueryId, caller.id(),
        caller.isApiUser(), metadata, cacheKey, scheduled);

    if (outcome.writeLock()) {
      store.put(Store.LOCKS, cacheKey, Json.map("job_id", jobId, "created_at", Service.now()));
    }
    var job = store.client()
        .forWorkflow(jobId)
        .method(QueryExecutionWorkflow::start)
        .invoke(request);
    return Serializers.job(job);
  }

  /** The job a lock names, or null when it has gone from the store. */
  static Map<String, Object> jobDocument(Service service, String jobId) {
    try {
      var job = service.store().client()
          .forWorkflow(jobId).method(QueryExecutionWorkflow::get).invoke();
      return job == null || job.isEmpty() ? null : job;
    } catch (RuntimeException e) {
      return null;
    }
  }

  static EnqueueLock.JobState stateOf(Map<String, Object> job) {
    if (job == null) {
      return null;
    }
    if (Boolean.TRUE.equals(job.get("cancelled"))) {
      return EnqueueLock.JobState.CANCELLED;
    }
    return switch (String.valueOf(job.get("status"))) {
      case "started" -> EnqueueLock.JobState.STARTED;
      case "finished" -> EnqueueLock.JobState.FINISHED;
      case "failed" -> EnqueueLock.JobState.FAILED;
      case "canceled" -> EnqueueLock.JobState.CANCELLED;
      default -> EnqueueLock.JobState.QUEUED;
    };
  }

  /** The five refusal bodies, named so a caller can list them. */
  public static List<Refusal> refusals() {
    return List.of(UNSAFE_WHEN_SHARED, UNSAFE_ON_VIEW_ONLY, NO_PERMISSION, SELECT_DATA_SOURCE,
        NO_DATA_SOURCE);
  }
}

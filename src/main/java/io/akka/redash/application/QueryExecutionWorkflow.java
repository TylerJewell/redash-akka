package io.akka.redash.application;

import akka.javasdk.annotations.Component;
import akka.javasdk.annotations.StepName;
import akka.javasdk.client.ComponentClient;
import akka.javasdk.workflow.Workflow;
import java.time.Duration;
import io.akka.redash.destinations.Mail;
import io.akka.redash.domain.Json;
import io.akka.redash.domain.Settings;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * One query execution, as a job a caller can watch and cancel (SPEC-001 R83, R84).
 *
 * <p>redash puts the work on a queue and hands back a job identifier the client polls until
 * the status leaves queued-or-started. That is what a workflow is: the state is the job, the
 * step is the run, and the identifier the caller polls is the workflow's own. It also gives
 * the thing an inline execution cannot — a job that is genuinely in flight, and therefore a
 * cancellation that can arrive while it is.
 */
@Component(id = "query-execution")
public class QueryExecutionWorkflow extends Workflow<QueryExecutionWorkflow.Job> {

  /**
   * @param status one of `queued`, `started`, `finished`, `failed`, `canceled`
   * @param cacheKey the query hash and data source the in-flight lock is held under
   * @param resultId the stored result, once there is one
   */
  public record Job(
      String jobId,
      long dataSourceId,
      String queryText,
      Long queryId,
      Long userId,
      boolean isApiKey,
      Map<String, Object> metadata,
      String cacheKey,
      boolean scheduled,
      String status,
      String error,
      Long resultId,
      String startedAt,
      boolean cancelled) {

    public Job withStatus(String next) {
      return new Job(jobId, dataSourceId, queryText, queryId, userId, isApiKey, metadata,
          cacheKey, scheduled, next, error, resultId, startedAt, cancelled);
    }

    public Job started(String at) {
      return new Job(jobId, dataSourceId, queryText, queryId, userId, isApiKey, metadata,
          cacheKey, scheduled, "started", error, resultId, at, cancelled);
    }

    public Job finished(Long result) {
      return new Job(jobId, dataSourceId, queryText, queryId, userId, isApiKey, metadata,
          cacheKey, scheduled, "finished", null, result, startedAt, cancelled);
    }

    public Job failed(String message) {
      return new Job(jobId, dataSourceId, queryText, queryId, userId, isApiKey, metadata,
          cacheKey, scheduled, "failed", message, null, startedAt, cancelled);
    }

    public Job cancel() {
      return new Job(jobId, dataSourceId, queryText, queryId, userId, isApiKey, metadata,
          cacheKey, scheduled, "canceled", error, resultId, startedAt, true);
    }

    /** The document `/api/jobs/<id>` answers. */
    public Map<String, Object> asDocument() {
      return Json.map(
          "id", jobId,
          "status", status,
          "error", error,
          "result", resultId,
          "started_at", startedAt,
          "cancelled", cancelled);
    }
  }

  /** What a caller asks for when it wants a query run. */
  public record Request(
      long dataSourceId,
      String queryText,
      Long queryId,
      Long userId,
      boolean isApiKey,
      Map<String, Object> metadata,
      String cacheKey,
      boolean scheduled) {}

  private final ComponentClient componentClient;

  public QueryExecutionWorkflow(ComponentClient componentClient) {
    this.componentClient = componentClient;
  }

  /**
   * How long a query is allowed to take.
   *
   * <p>The source's own limit, and its own default: `REDASH_ADHOC_QUERY_TIME_LIMIT` is -1,
   * meaning a query runs until the database answers. A step here has a default timeout of
   * five seconds, so without this every query slower than that is cut off and retried -
   * which is not a limit redash has, and is invisible until somebody writes a slow query.
   */
  @Override
  public WorkflowSettings settings() {
    return WorkflowSettings.builder().defaultStepTimeout(Duration.ofSeconds(timeLimitSeconds())).build();
  }

  public Effect<Map<String, Object>> start(Request request) {
    if (currentState() != null) {
      return effects().reply(currentState().asDocument());
    }
    var job = new Job(commandContext().workflowId(), request.dataSourceId(), request.queryText(),
        request.queryId(), request.userId(), request.isApiKey(), request.metadata(),
        request.cacheKey(), request.scheduled(), "queued", null, null, null, false);
    return effects()
        .updateState(job)
        .transitionTo(QueryExecutionWorkflow::runStep)
        .withInput(request)
        .thenReply(job.asDocument());
  }

  /**
   * Run it. The whole of the source's execution path is behind this one call: the runner,
   * the stored result, the fan-out to every query sharing the cache key, and the alerts of
   * exactly those queries.
   */
  @StepName("run")
  private StepEffect runStep(Request request) {
    if (currentState().cancelled()) {
      return stepEffects().updateState(currentState().cancel()).thenEnd();
    }
    var store = new Store(componentClient);
    var settings = Settings.fromEnvironment();
    var execution = new Execution(store, mailServer(settings), settings.host(),
        settings.alertsDefaultMailSubjectTemplate(), AlertTemplates.defaultBodyTemplate(settings));

    var running = currentState().started(Json.instant(Instant.now()));
    var outcome = execution.run(request.dataSourceId(), request.queryText(), request.metadata());
    releaseLock(store, request.cacheKey());

    if (!outcome.succeeded()) {
      if (request.scheduled() && request.queryId() != null) {
        trackFailure(store, request.queryId(), outcome.error());
      }
      return stepEffects().updateState(running.failed(outcome.error())).thenEnd();
    }
    return stepEffects().updateState(running.finished(outcome.resultId())).thenEnd();
  }

  /** Cancel a job that is still on its way. A finished one is left as it is. */
  public Effect<Map<String, Object>> cancel() {
    if (currentState() == null) {
      return effects().error("not found");
    }
    if (List.of("finished", "failed", "canceled").contains(currentState().status())) {
      return effects().reply(currentState().asDocument());
    }
    var cancelled = currentState().cancel();
    return effects().updateState(cancelled).pause().thenReply(cancelled.asDocument());
  }

  /**
   * The limit the deployment sets, or the largest a step timeout can usefully be.
   *
   * <p>redash's own default for both is -1, which means no limit at all; a step here has to
   * be given a number, so an absent limit becomes a day. A scheduled refresh reads its own
   * setting rather than the ad-hoc one, and the two are separate on the source too.
   */
  private static long timeLimitSeconds() {
    var settings = Settings.fromEnvironment();
    long limit = settings.adhocQueryTimeLimit();
    return limit > 0 ? limit : Duration.ofDays(1).toSeconds();
  }

  public ReadOnlyEffect<Map<String, Object>> get() {
    if (currentState() == null) {
      return effects().reply(Map.of());
    }
    return effects().reply(currentState().asDocument());
  }

  /**
   * Clear the in-flight lock (SPEC-001 R111). It is released when the run finishes, whether
   * or not the run succeeded, because the lock names a job rather than an outcome.
   */
  private static void releaseLock(Store store, String cacheKey) {
    if (cacheKey != null) {
      store.delete(Store.LOCKS, cacheKey);
    }
  }

  /** A failed scheduled refresh raises the query's counter, which pushes its next boundary. */
  private static void trackFailure(Store store, long queryId, String message) {
    var query = store.find(Store.QUERIES, queryId);
    if (query == null) {
      return;
    }
    long failures = query.get("schedule_failures") instanceof Number n ? n.longValue() : 0;
    store.update(Store.QUERIES, queryId, Json.map(
        "schedule_failures", failures + 1, "last_failure", message));
  }

  public static Mail.Server mailServer(Settings settings) {
    return new Mail.Server(
        settings.mailServer(),
        settings.mailPort(),
        settings.mailUseTls(),
        settings.mailUseSsl(),
        settings.mailUsername(),
        settings.mailPassword(),
        settings.mailDefaultSender(),
        settings.mailMaxEmails(),
        settings.mailAsciiAttachments());
  }
}

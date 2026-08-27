package io.akka.redash.application;

import akka.javasdk.annotations.Component;
import akka.javasdk.annotations.StepName;
import akka.javasdk.client.ComponentClient;
import akka.javasdk.workflow.Workflow;
import java.time.Duration;
import io.akka.redash.domain.Json;
import io.akka.redash.queryrunner.Registry;
import io.akka.redash.queryrunner.Transport;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * One data source's schema, fetched as a job a caller can watch (SPEC-001 R48, R49).
 *
 * <p>The source puts the fetch on a queue and answers a job document; the schema appears in
 * the cache when the worker gets to it, and a later request for the same source answers the
 * cache. Doing the fetch inline instead would answer the finished schema on the first
 * request, which is a different answer to the same question — the walk asks for a schema
 * three times in a row and the source answers a queued job every time.
 *
 * <p>A schema is cached for `SCHEMAS_REFRESH_SCHEDULE` minutes plus seven days, which is
 * what `refreshed_at` is compared against; `refresh` in the query string skips the cache and
 * starts a fetch regardless.
 */
@Component(id = "schema-fetch")
public class SchemaFetchWorkflow extends Workflow<SchemaFetchWorkflow.Job> {

  /**
   * @param status one of `queued`, `started`, `finished`, `failed`
   * @param code the source's own error code: 1 for a type that cannot report a schema, 2 for
   *     a fetch that failed
   */
  public record Job(
      String jobId,
      long dataSourceId,
      String status,
      String error,
      Long code,
      String startedAt) {

    public Job started(String at) {
      return new Job(jobId, dataSourceId, "started", error, code, at);
    }

    public Job finished() {
      return new Job(jobId, dataSourceId, "finished", "", null, startedAt);
    }

    public Job failed(String message, long errorCode) {
      return new Job(jobId, dataSourceId, "failed", message, errorCode, startedAt);
    }

    /** The document `/api/jobs/<id>` answers for this job. */
    public Map<String, Object> asDocument() {
      return Json.map(
          "id", jobId,
          "status", status,
          "error", error,
          "result", null,
          "started_at", startedAt,
          "cancelled", false);
    }
  }

  /** What a caller asks for when it wants a schema. */
  public record Request(long dataSourceId) {}

  private final ComponentClient componentClient;

  public SchemaFetchWorkflow(ComponentClient componentClient) {
    this.componentClient = componentClient;
  }

  /**
   * How long a schema fetch is allowed to take.
   *
   * <p>`REDASH_SCHEMAS_REFRESH_TIMEOUT`, which is the source's own limit for this and
   * defaults to five minutes. The step opens a connection and reads a catalogue, and the
   * default five seconds is shorter than that takes against anything remote.
   */
  @Override
  public WorkflowSettings settings() {
    return WorkflowSettings.builder().defaultStepTimeout(Duration.ofSeconds(
        io.akka.redash.domain.Settings.fromEnvironment().schemasRefreshTimeout())).build();
  }

  public Effect<Map<String, Object>> start(Request request) {
    if (currentState() != null) {
      return effects().reply(currentState().asDocument());
    }
    var job = new Job(commandContext().workflowId(), request.dataSourceId(),
        "queued", null, null, null);
    return effects()
        .updateState(job)
        .transitionTo(SchemaFetchWorkflow::fetchStep)
        .withInput(request)
        .thenReply(job.asDocument());
  }

  @StepName("fetch")
  private StepEffect fetchStep(Request request) {
    var store = new Store(componentClient);
    var running = currentState().started(Json.instant(Instant.now()));
    var dataSource = store.find(Store.DATA_SOURCES, request.dataSourceId());
    if (dataSource == null) {
      return stepEffects().updateState(running.failed("No data source found", 2L)).thenEnd();
    }
    var runner = Registry.get(String.valueOf(dataSource.get("type")));
    if (runner == null) {
      return stepEffects()
          .updateState(running.failed(Transport.NOT_SUPPORTED, 1L)).thenEnd();
    }
    var result = runner.schema(Json.asMap(dataSource.get("options")));
    if (result.isFailure()) {
      long code = Transport.NOT_SUPPORTED.equals(result.error()) ? 1L : 2L;
      return stepEffects().updateState(running.failed(result.error(), code)).thenEnd();
    }
    store.put(Store.STATE, "schema:" + request.dataSourceId(),
        Json.map("schema", sorted(result.rows()), "refreshed_at",
            Json.instant(Instant.now())));
    return stepEffects().updateState(running.finished()).thenEnd();
  }

  public ReadOnlyEffect<Map<String, Object>> get() {
    if (currentState() == null) {
      return effects().reply(Map.of());
    }
    return effects().reply(currentState().asDocument());
  }

  /** Tables by name, and each table's columns by name (SPEC-001 R49). */
  private static List<Map<String, Object>> sorted(List<Map<String, Object>> tables) {
    return io.akka.redash.api.Service.sortedSchema(tables);
  }
}

package io.akka.redash.application;

import akka.javasdk.annotations.Component;
import akka.javasdk.annotations.StepName;
import akka.javasdk.client.ComponentClient;
import akka.javasdk.workflow.Workflow;
import java.time.Duration;
import io.akka.redash.domain.Json;
import io.akka.redash.queryrunner.Registry;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The three background fetches the Databricks browser makes (`redash/tasks/databricks.py`).
 *
 * <p>Databricks is the one data source type with a schema browser of its own: its screens
 * ask for the databases, then a database's tables, then a table's columns, and each of the
 * three is a job rather than an answer. The endpoint above it caches what comes back, which
 * is why the fetch writes into the store rather than only replying — a second visit to the
 * same database answers out of the cache with no job at all.
 *
 * <p>The failure wording is the source's own: each of its three tasks catches everything and
 * answers `{"error": {"code": 2, "message": "…"}}` with a different message, so a caller
 * cannot tell a missing database from a broken connection, and neither can this.
 */
@Component(id = "databricks-fetch")
public class DatabricksFetchWorkflow extends Workflow<DatabricksFetchWorkflow.Job> {

  /** Which of the three fetches this job is. */
  public enum Kind {
    /** Every database the data source can see. */
    DATABASES,
    /** Every table of one database, with its columns. */
    TABLES,
    /** The columns of one table. */
    COLUMNS
  }

  public record Job(
      String jobId,
      long dataSourceId,
      String kind,
      String databaseName,
      String tableName,
      String status,
      String error,
      Long code,
      String startedAt) {

    public Job started(String at) {
      return new Job(jobId, dataSourceId, kind, databaseName, tableName, "started",
          error, code, at);
    }

    public Job finished() {
      return new Job(jobId, dataSourceId, kind, databaseName, tableName, "finished",
          "", null, startedAt);
    }

    public Job failed(String message) {
      return new Job(jobId, dataSourceId, kind, databaseName, tableName, "failed",
          message, 2L, startedAt);
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

  public record Request(long dataSourceId, String kind, String databaseName, String tableName) {}

  private final ComponentClient componentClient;

  public DatabricksFetchWorkflow(ComponentClient componentClient) {
    this.componentClient = componentClient;
  }

  /** Where the endpoint reads a finished fetch back from. The source's redis keys. */
  public static String databasesKey(long dataSourceId) {
    return "databricks:databases:" + dataSourceId;
  }

  public static String tablesKey(long dataSourceId, String databaseName) {
    return "databricks:database_tables:" + dataSourceId + ":" + databaseName;
  }

  /**
   * The same limit the schema fetch uses, for the same reason: this is a catalogue read
   * against something remote, and the default five seconds is shorter than that takes.
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
    var job = new Job(commandContext().workflowId(), request.dataSourceId(), request.kind(),
        request.databaseName(), request.tableName(), "queued", null, null, null);
    return effects()
        .updateState(job)
        .transitionTo(DatabricksFetchWorkflow::fetchStep)
        .withInput(request)
        .thenReply(job.asDocument());
  }

  @StepName("fetch")
  private StepEffect fetchStep(Request request) {
    var store = new Store(componentClient);
    var running = currentState().started(Json.instant(Instant.now()));
    var dataSource = store.find(Store.DATA_SOURCES, request.dataSourceId());
    if (dataSource == null) {
      return stepEffects().updateState(running.failed("Error retrieving database list."))
          .thenEnd();
    }
    var runner = Registry.get(String.valueOf(dataSource.get("type")));
    if (runner == null) {
      return stepEffects().updateState(running.failed("Error retrieving database list."))
          .thenEnd();
    }
    var options = Json.asMap(dataSource.get("options"));
    var kind = Kind.valueOf(request.kind());

    var result = switch (kind) {
      case DATABASES -> runner.run("SHOW DATABASES", options);
      case TABLES, COLUMNS -> runner.schema(options);
    };
    if (result.isFailure()) {
      return stepEffects().updateState(running.failed(switch (kind) {
        case DATABASES -> "Error retrieving database list.";
        case TABLES -> "Error retrieving schema.";
        case COLUMNS -> "Error retrieving table columns.";
      })).thenEnd();
    }

    switch (kind) {
      case DATABASES -> store.put(Store.STATE, databasesKey(request.dataSourceId()),
          Json.map("databases", databaseNames(result.rows()),
              "refreshed_at", Json.instant(Instant.now())));
      case TABLES -> store.put(Store.STATE,
          tablesKey(request.dataSourceId(), request.databaseName()),
          Json.map("schema", result.rows(), "refreshed_at", Json.instant(Instant.now())));
      case COLUMNS -> {
        // The source's column fetch answers the caller and caches nothing.
      }
    }
    return stepEffects().updateState(running.finished()).thenEnd();
  }

  public ReadOnlyEffect<Map<String, Object>> get() {
    if (currentState() == null) {
      return effects().reply(Map.of());
    }
    return effects().reply(currentState().asDocument());
  }

  /** The first column of every row, which is what `SHOW DATABASES` answers with. */
  private static List<Object> databaseNames(List<Map<String, Object>> rows) {
    var out = new ArrayList<Object>();
    for (Map<String, Object> row : rows) {
      for (Object value : row.values()) {
        out.add(value);
        break;
      }
    }
    return out;
  }
}

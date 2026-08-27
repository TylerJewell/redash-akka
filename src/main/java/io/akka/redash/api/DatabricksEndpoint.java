package io.akka.redash.api;

import akka.http.javadsl.model.HttpResponse;
import akka.javasdk.annotations.Acl;
import akka.javasdk.annotations.http.Get;
import akka.javasdk.annotations.http.HttpEndpoint;
import akka.javasdk.client.ComponentClient;
import io.akka.redash.application.DatabricksFetchWorkflow;
import io.akka.redash.application.Store;
import io.akka.redash.domain.Access;
import io.akka.redash.domain.Json;
import java.util.Map;

/**
 * `/api/databricks` — the schema browser Databricks has and no other type does
 * (`redash/handlers/databricks.py`).
 *
 * <p>Three reads, each of them a cache lookup and then a job: the databases a data source
 * can see, the tables of one database, and the columns of one table. The cache is the
 * source's own — keyed `databricks:databases:<id>` and
 * `databricks:database_tables:<id>:<database>` — and `refresh` in the query string skips it.
 *
 * <p>The one refusal worth noticing is the type check: a data source of any other type
 * answers 400 with `Resource only available for the Databricks query runner.`, after the
 * access check rather than before it, so a caller who cannot see the data source is told it
 * does not exist rather than what type it is.
 */
@Acl(allow = @Acl.Matcher(principal = Acl.Principal.ALL))
@HttpEndpoint("/api/databricks")
public class DatabricksEndpoint extends ApiBase {

  public DatabricksEndpoint(ComponentClient client) {
    super(new Service(client));
  }

  @Get("/databases/{dataSourceId}")
  public HttpResponse databases(String dataSourceId) {
    return answer(() -> {
      long id = databricksDataSource(dataSourceId);
      if (!hasQueryParam("refresh")) {
        var cached = store().find(Store.STATE, DatabricksFetchWorkflow.databasesKey(id));
        if (cached != null && cached.get("databases") != null) {
          return cached.get("databases");
        }
      }
      return job(id, DatabricksFetchWorkflow.Kind.DATABASES, null, null);
    });
  }

  /**
   * The tables of one database.
   *
   * <p>Three answers rather than two: the cache, a job that fetches the tables alone, or —
   * when `refresh` is asked for — a job that fetches them with their columns and writes the
   * cache. Only the third writes, which is why asking twice without `refresh` starts two
   * jobs and never fills the cache; that is the source's own shape.
   */
  @Get("/databases/{dataSourceId}/{databaseName}/tables")
  public HttpResponse tables(String dataSourceId, String databaseName) {
    return answer(() -> {
      long id = databricksDataSource(dataSourceId);
      if (!hasQueryParam("refresh")) {
        var cached = store().find(Store.STATE,
            DatabricksFetchWorkflow.tablesKey(id, databaseName));
        if (cached != null && cached.get("schema") != null) {
          return Json.map("schema", cached.get("schema"), "has_columns", true);
        }
      }
      return job(id, DatabricksFetchWorkflow.Kind.TABLES, databaseName, null);
    });
  }

  @Get("/databases/{dataSourceId}/{databaseName}/columns/{tableName}")
  public HttpResponse columns(String dataSourceId, String databaseName, String tableName) {
    return answer(() ->
        job(databricksDataSource(dataSourceId), DatabricksFetchWorkflow.Kind.COLUMNS,
            databaseName, tableName));
  }

  /** The data source, if the caller may see it and it is of the one type this serves. */
  private long databricksDataSource(String dataSourceId) {
    var caller = caller();
    long id = identifier(dataSourceId);
    var dataSource = service.dataSourceById(id);
    if (dataSource == null) {
      throw Http.notFound();
    }
    if (!caller.has("admin")
        && !Access.hasAccessToGroups(service.groupsOf(dataSource), caller.permissions(),
            caller.groupIds(), Access.VIEW_ONLY)) {
      throw Http.forbidden();
    }
    if (!"databricks".equals(dataSource.get("type"))) {
      throw Http.badRequest("Resource only available for the Databricks query runner.");
    }
    return id;
  }

  private Map<String, Object> job(long dataSourceId, DatabricksFetchWorkflow.Kind kind,
      String databaseName, String tableName) {
    var jobId = java.util.UUID.randomUUID().toString();
    var started = store().client()
        .forWorkflow(jobId)
        .method(DatabricksFetchWorkflow::start)
        .invoke(new DatabricksFetchWorkflow.Request(dataSourceId, kind.name(),
            databaseName, tableName));
    return Serializers.job(started);
  }
}

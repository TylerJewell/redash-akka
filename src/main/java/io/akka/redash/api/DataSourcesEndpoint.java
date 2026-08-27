package io.akka.redash.api;

import akka.http.javadsl.model.HttpEntity;
import akka.http.javadsl.model.HttpResponse;
import akka.javasdk.annotations.Acl;
import akka.javasdk.annotations.http.Delete;
import akka.javasdk.annotations.http.Get;
import akka.javasdk.annotations.http.HttpEndpoint;
import akka.javasdk.annotations.http.Post;
import akka.javasdk.client.ComponentClient;
import io.akka.redash.application.Store;
import io.akka.redash.domain.Access;
import io.akka.redash.domain.Configuration;
import io.akka.redash.domain.Json;
import io.akka.redash.queryrunner.Registry;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * `/api/data_sources` — the connections queries run against (SPEC-001 R39 to R50).
 *
 * <p>Which fields a caller sees is decided in three different places by the source, and the
 * differences are observable: creating and editing answer the administrator's full document
 * with no `view_only`; reading answers the full document **with** `view_only`; and pausing
 * or resuming answers the reduced document, because the source's pause handler serialises
 * without `all=True`.
 */
@Acl(allow = @Acl.Matcher(principal = Acl.Principal.ALL))
@HttpEndpoint("/api/data_sources")
public class DataSourcesEndpoint extends ApiBase {

  public DataSourcesEndpoint(ComponentClient client) {
    super(new Service(client));
  }

  @Get("/types")
  public HttpResponse types() {
    return answer(() -> {
      caller().require("admin");
      return Registry.asDocuments(service.settings());
    });
  }

  @Get("")
  public HttpResponse list() {
    return answer(() -> {
      var caller = caller();
      caller.require("list_data_sources");
      var out = new ArrayList<Map<String, Object>>();
      for (Map<String, Object> dataSource : service.allDataSources()) {
        if (!caller.has("admin")
            && !Access.hasAccessToGroups(service.groupsOf(dataSource), caller.permissions(),
                caller.groupIds(), Access.VIEW_ONLY)) {
          continue;
        }
        out.add(Serializers.dataSource(dataSource, service.runnerFor(dataSource), false,
            viewOnlyFor(caller, dataSource)));
      }
      out.sort(Comparator.comparing(row -> String.valueOf(row.get("name")).toLowerCase(Locale.ROOT)));
      record(caller, Json.map("action", "list", "object_id", "admin/data_sources",
          "object_type", "datasource"));
      return out;
    });
  }

  @Post("")
  public HttpResponse create(HttpEntity.Strict requestBody) {
    return answer(() -> {
      guardCsrf(requestBody);
      var caller = caller();
      caller.require("admin");
      var request = body(requestBody);
      requireFields(request, "options", "name", "type");
      var type = String.valueOf(request.get("type"));
      var runner = Registry.registered(service.settings()).get(type);
      if (runner == null) {
        throw Http.badRequest();
      }
      var options = Configuration.withoutNulls(Json.asMap(request.get("options")));
      if (!Configuration.isValid(options, runner.configurationSchema())) {
        throw Http.badRequest();
      }
      var defaultGroup = service.builtinGroup("default");
      var groups = new LinkedHashMap<String, Object>();
      if (defaultGroup != null) {
        groups.put(String.valueOf(defaultGroup), false);
      }
      var dataSource = store().insert(Store.DATA_SOURCES, Json.map(
          "org_id", 1L,
          "name", request.get("name"),
          "type", type,
          "options", options,
          "groups", groups,
          "queue_name", "queries",
          "scheduled_queue_name", "scheduled_queries",
          "pause_reason", null,
          "created_at", Service.now()));
      record(caller, Json.map("action", "create", "object_id", dataSource.get("id"),
          "object_type", "datasource"));
      return Serializers.dataSource(dataSource, runner, true, null);
    });
  }

  @Get("/{dataSourceId}")
  public HttpResponse get(String dataSourceId) {
    return answer(() -> {
      var caller = caller();
      var dataSource = requireDataSource(identifier(dataSourceId));
      if (!canView(caller, dataSource)) {
        throw Http.forbidden();
      }
      Map<String, Object> document = new LinkedHashMap<>();
      if (caller.has("list_data_sources")) {
        document = Serializers.dataSource(dataSource, service.runnerFor(dataSource),
            caller.has("admin"), null);
      }
      document.put("view_only", viewOnlyFor(caller, dataSource));
      record(caller, Json.map("action", "view", "object_id", dataSourceId,
          "object_type", "datasource"));
      return document;
    });
  }

  @Post("/{dataSourceId}")
  public HttpResponse update(String dataSourceId, HttpEntity.Strict requestBody) {
    return answer(() -> {
      guardCsrf(requestBody);
      var caller = caller();
      caller.require("admin");
      var dataSource = requireDataSource(identifier(dataSourceId));
      var request = body(requestBody);
      var type = String.valueOf(request.get("type"));
      var runner = Registry.registered(service.settings()).get(type);
      if (runner == null) {
        throw Http.badRequest();
      }
      var merged = Configuration.merge(Json.asMap(dataSource.get("options")),
          Configuration.withoutNulls(Json.asMap(request.get("options"))),
          runner.configurationSchema());
      if (!Configuration.isValid(merged, runner.configurationSchema())) {
        throw Http.badRequest();
      }
      var updated = store().update(Store.DATA_SOURCES, dataSource.get("id"), Json.map(
          "type", type, "name", request.get("name"), "options", merged));
      record(caller, Json.map("action", "edit", "object_id", updated.get("id"),
          "object_type", "datasource"));
      return Serializers.dataSource(updated, runner, true, null);
    });
  }

  @Delete("/{dataSourceId}")
  public HttpResponse remove(String dataSourceId) {
    return answer(() -> {
      guardCsrf(null);
      var caller = caller();
      caller.require("admin");
      long id = identifier(dataSourceId);
      requireDataSource(id);
      for (Map<String, Object> query : service.allQueries()) {
        if (Service.number(query.get("data_source_id")) == id) {
          store().update(Store.QUERIES, query.get("id"),
              Json.map("data_source_id", null, "latest_query_data_id", null));
        }
      }
      for (Map<String, Object> result : store().byOrg(Store.QUERY_RESULTS, 1L)) {
        if (Service.number(result.get("data_source_id")) == id) {
          store().delete(Store.QUERY_RESULTS, result.get("id"));
        }
      }
      store().delete(Store.STATE, "schema:" + id);
      store().delete(Store.DATA_SOURCES, id);
      record(caller, Json.map("action", "delete", "object_id", dataSourceId,
          "object_type", "datasource"));
      return Http.noContent();
    });
  }

  @Post("/{dataSourceId}/pause")
  public HttpResponse pause(String dataSourceId, HttpEntity.Strict requestBody) {
    return answer(() -> {
      guardCsrf(requestBody);
      var caller = caller();
      caller.require("admin");
      var dataSource = requireDataSource(identifier(dataSourceId));
      var request = body(requestBody);
      Object reason = request.containsKey("reason") ? request.get("reason") : queryParam("reason");
      var updated = store().update(Store.DATA_SOURCES, dataSource.get("id"),
          Json.map("pause_reason", reason == null ? "" : reason, "paused", true));
      record(caller, Json.map("action", "pause", "object_id", updated.get("id"),
          "object_type", "datasource"));
      return Serializers.dataSource(updated, service.runnerFor(updated), false, null);
    });
  }

  @Delete("/{dataSourceId}/pause")
  public HttpResponse resume(String dataSourceId) {
    return answer(() -> {
      guardCsrf(null);
      var caller = caller();
      caller.require("admin");
      var dataSource = requireDataSource(identifier(dataSourceId));
      var updated = store().update(Store.DATA_SOURCES, dataSource.get("id"),
          Json.map("pause_reason", null, "paused", false));
      record(caller, Json.map("action", "resume", "object_id", updated.get("id"),
          "object_type", "datasource"));
      return Serializers.dataSource(updated, service.runnerFor(updated), false, null);
    });
  }

  @Post("/{dataSourceId}/test")
  public HttpResponse test(String dataSourceId) {
    return answer(() -> {
      guardCsrf(null);
      var caller = caller();
      caller.require("admin");
      var dataSource = requireDataSource(identifier(dataSourceId));
      var runner = service.runnerFor(dataSource);
      Map<String, Object> response;
      if (runner == null) {
        response = Json.map("message", "Unknown data source type.", "ok", false);
      } else {
        var failure = runner.testConnection(Json.asMap(dataSource.get("options")));
        response = failure == null
            ? Json.map("message", "success", "ok", true)
            : Json.map("message", failure, "ok", false);
      }
      record(caller, Json.map("action", "test", "object_id", dataSourceId,
          "object_type", "datasource", "result", response));
      return response;
    });
  }

  /**
   * Whether every group the caller is in that holds this data source holds it view-only.
   *
   * <p>One group with full access is enough to make the answer false, which is how the
   * source reports a caller whose access arrives through two groups at different levels.
   */
  private boolean viewOnlyFor(Caller caller, Map<String, Object> dataSource) {
    var groups = service.groupsOf(dataSource);
    boolean all = true;
    for (Long groupId : caller.groupIds()) {
      if (groups.containsKey(groupId) && !Boolean.TRUE.equals(groups.get(groupId))) {
        all = false;
        break;
      }
    }
    return all;
  }


  /**
   * The schema of a data source (SPEC-001 R48, R49).
   *
   * <p>A cached schema answers straight away; anything else starts a fetch and answers the
   * job, exactly as the source does. Answering the finished schema on the first request
   * instead would be a different answer to the same question: asked three times in a row,
   * the source answers a queued job three times, because the fetch is still on its queue.
   */
  @Get("/{dataSourceId}/schema")
  public HttpResponse schema(String dataSourceId) {
    return answer(() -> {
      var caller = caller();
      long id = identifier(dataSourceId);
      var dataSource = requireDataSource(id);
      if (!canView(caller, dataSource)) {
        throw Http.forbidden();
      }
      if (!hasQueryParam("refresh")) {
        var cached = store().find(Store.STATE, "schema:" + id);
        if (cached != null && cached.get("schema") != null) {
          return Json.map("schema", cached.get("schema"));
        }
      }
      var jobId = java.util.UUID.randomUUID().toString();
      var job = store().client()
          .forWorkflow(jobId)
          .method(io.akka.redash.application.SchemaFetchWorkflow::start)
          .invoke(new io.akka.redash.application.SchemaFetchWorkflow.Request(id));
      return Serializers.job(job);
    });
  }

  private Map<String, Object> requireDataSource(long id) {
    var dataSource = service.dataSourceById(id);
    if (dataSource == null) {
      throw Http.notFound();
    }
    return dataSource;
  }
}

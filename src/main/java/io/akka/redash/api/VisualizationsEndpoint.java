package io.akka.redash.api;

import akka.http.javadsl.model.HttpEntity;
import akka.http.javadsl.model.HttpResponse;
import akka.javasdk.annotations.Acl;
import akka.javasdk.annotations.http.Delete;
import akka.javasdk.annotations.http.HttpEndpoint;
import akka.javasdk.annotations.http.Post;
import akka.javasdk.client.ComponentClient;
import io.akka.redash.application.Store;
import io.akka.redash.domain.Json;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * `/api/visualizations` — how a query's result is drawn (SPEC-001 R135).
 *
 * <p>Deleting one takes every widget showing it with it, which is what makes a dashboard's
 * widget list shrink when a chart is removed from the query behind it.
 */
@Acl(allow = @Acl.Matcher(principal = Acl.Principal.ALL))
@HttpEndpoint("/api/visualizations")
public class VisualizationsEndpoint extends ApiBase {

  public VisualizationsEndpoint(ComponentClient client) {
    super(new Service(client));
  }

  @Post("")
  public HttpResponse create(HttpEntity.Strict requestBody) {
    return answer(() -> {
      guardCsrf(requestBody);
      var caller = caller();
      caller.require("edit_query");
      var request = new LinkedHashMap<>(body(requestBody));
      var query = service.queryById(request.remove("query_id"));
      if (query == null) {
        throw Http.notFound();
      }
      if (!service.canModify(caller, QueriesEndpoint.TABLE, query)) {
        throw Http.forbidden();
      }
      long queryId = Service.number(query.get("id"));
      var now = Service.now();
      var visualization = store().insert(Store.VISUALIZATIONS, Json.map(
          "org_id", 1L,
          "parent_id", queryId,
          "query_id", queryId,
          "type", request.get("type"),
          "name", request.get("name"),
          "description", request.get("description"),
          "options", request.getOrDefault("options", Map.of()),
          "created_at", now,
          "updated_at", now));
      return Serializers.visualization(visualization, null);
    });
  }

  @Post("/{visualizationId}")
  public HttpResponse update(String visualizationId, HttpEntity.Strict requestBody) {
    return answer(() -> {
      guardCsrf(requestBody);
      var caller = caller();
      caller.require("edit_query");
      var visualization = requireVisualization(identifier(visualizationId));
      var query = service.queryById(visualization.get("query_id"));
      if (!service.canModify(caller, QueriesEndpoint.TABLE, query)) {
        throw Http.forbidden();
      }
      var request = new LinkedHashMap<>(body(requestBody));
      request.remove("id");
      request.remove("query_id");
      request.put("updated_at", Service.now());
      var updated = store().update(Store.VISUALIZATIONS, visualization.get("id"), request);
      return Serializers.visualization(updated, null);
    });
  }

  @Delete("/{visualizationId}")
  public HttpResponse remove(String visualizationId) {
    return answer(() -> {
      guardCsrf(null);
      var caller = caller();
      caller.require("edit_query");
      long id = identifier(visualizationId);
      var visualization = requireVisualization(id);
      var query = service.queryById(visualization.get("query_id"));
      if (!service.canModify(caller, QueriesEndpoint.TABLE, query)) {
        throw Http.forbidden();
      }
      record(caller, Json.map("action", "delete", "object_id", id,
          "object_type", "Visualization"));
      for (Map<String, Object> widget : store().byGroupKey(Store.WIDGETS, "visualization:" + id)) {
        store().delete(Store.WIDGETS, widget.get("id"));
      }
      store().delete(Store.VISUALIZATIONS, id);
      return null;
    });
  }

  private Map<String, Object> requireVisualization(long id) {
    var visualization = store().find(Store.VISUALIZATIONS, id);
    if (visualization == null) {
      throw Http.notFound();
    }
    return visualization;
  }
}

package io.akka.redash.api;

import akka.http.javadsl.model.HttpEntity;
import akka.http.javadsl.model.HttpResponse;
import akka.javasdk.annotations.Acl;
import akka.javasdk.annotations.http.Delete;
import akka.javasdk.annotations.http.HttpEndpoint;
import akka.javasdk.annotations.http.Post;
import akka.javasdk.client.ComponentClient;
import io.akka.redash.application.Store;
import io.akka.redash.domain.Access;
import io.akka.redash.domain.Json;
import java.util.Map;

/**
 * `/api/widgets` — one box on a dashboard (SPEC-001 R133, R134).
 *
 * <p>A widget with no visualisation is a text box. Updating one writes only its text and
 * its options, whatever else the request carries, because the source's handler assigns
 * exactly those two.
 */
@Acl(allow = @Acl.Matcher(principal = Acl.Principal.ALL))
@HttpEndpoint("/api/widgets")
public class WidgetsEndpoint extends ApiBase {

  public WidgetsEndpoint(ComponentClient client) {
    super(new Service(client));
  }

  @Post("")
  public HttpResponse create(HttpEntity.Strict requestBody) {
    return answer(() -> {
      guardCsrf(requestBody);
      var caller = caller();
      caller.require("edit_dashboard");
      var request = body(requestBody);
      var dashboard = store().find(Store.DASHBOARDS, request.get("dashboard_id"));
      if (dashboard == null) {
        throw Http.notFound();
      }
      if (!service.canModify(caller, DashboardsEndpoint.TABLE, dashboard)) {
        throw Http.forbidden();
      }
      Map<String, Object> visualization = null;
      if (request.get("visualization_id") != null) {
        visualization = store().find(Store.VISUALIZATIONS, request.get("visualization_id"));
        if (visualization == null) {
          throw Http.notFound();
        }
        var query = service.queryById(visualization.get("query_id"));
        service.requireAccessToQuery(caller, query, Access.VIEW_ONLY);
      }
      long dashboardId = Service.number(dashboard.get("id"));
      var now = Service.now();
      var widget = store().insert(Store.WIDGETS, Json.map(
          "org_id", 1L,
          "parent_id", dashboardId,
          "dashboard_id", dashboardId,
          "visualization_id", visualization == null
              ? null : Service.number(visualization.get("id")),
          "group_key", visualization == null
              ? "" : "visualization:" + visualization.get("id"),
          "text", request.get("text"),
          "width", request.getOrDefault("width", 1L),
          "options", request.getOrDefault("options", Map.of()),
          "created_at", now,
          "updated_at", now));
      return serialize(caller, widget, visualization);
    });
  }

  @Post("/{widgetId}")
  public HttpResponse update(String widgetId, HttpEntity.Strict requestBody) {
    return answer(() -> {
      guardCsrf(requestBody);
      var caller = caller();
      caller.require("edit_dashboard");
      var widget = requireWidget(identifier(widgetId));
      var dashboard = store().find(Store.DASHBOARDS, widget.get("dashboard_id"));
      if (!service.canModify(caller, DashboardsEndpoint.TABLE, dashboard)) {
        throw Http.forbidden();
      }
      var request = body(requestBody);
      requireFields(request, "text", "options");
      var updated = store().update(Store.WIDGETS, widget.get("id"), Json.map(
          "text", request.get("text"), "options", request.get("options"),
          "updated_at", Service.now()));
      var visualization = store().find(Store.VISUALIZATIONS, updated.get("visualization_id"));
      return serialize(caller, updated, visualization);
    });
  }

  @Delete("/{widgetId}")
  public HttpResponse remove(String widgetId) {
    return answer(() -> {
      guardCsrf(null);
      var caller = caller();
      caller.require("edit_dashboard");
      long id = identifier(widgetId);
      var widget = requireWidget(id);
      var dashboard = store().find(Store.DASHBOARDS, widget.get("dashboard_id"));
      if (!service.canModify(caller, DashboardsEndpoint.TABLE, dashboard)) {
        throw Http.forbidden();
      }
      record(caller, Json.map("action", "delete", "object_id", id, "object_type", "widget"));
      store().delete(Store.WIDGETS, id);
      return null;
    });
  }

  private Map<String, Object> serialize(Caller caller, Map<String, Object> widget,
      Map<String, Object> visualization) {
    if (visualization == null) {
      return Serializers.widget(widget, null);
    }
    var query = service.queryById(visualization.get("query_id"));
    var author = query == null ? null : service.userById(query.get("user_id"));
    var lastModifiedBy = query == null
        ? null : service.userById(query.get("last_modified_by_id"));
    var queryDocument = query == null ? null : Serializers.query(query, author, lastModifiedBy,
        service.parameterized(query).isSafe(), false, null, null);
    return Serializers.widget(widget, Serializers.visualization(visualization, queryDocument));
  }

  private Map<String, Object> requireWidget(long id) {
    var widget = store().find(Store.WIDGETS, id);
    if (widget == null) {
      throw Http.notFound();
    }
    return widget;
  }
}

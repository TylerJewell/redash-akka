package io.akka.redash.api;

import akka.http.javadsl.model.HttpEntity;
import akka.http.javadsl.model.HttpResponse;
import akka.javasdk.annotations.Acl;
import akka.javasdk.annotations.http.Delete;
import akka.javasdk.annotations.http.Get;
import akka.javasdk.annotations.http.HttpEndpoint;
import akka.javasdk.annotations.http.Post;
import akka.javasdk.client.ComponentClient;
import io.akka.redash.application.AlertTemplates;
import io.akka.redash.application.Execution;
import io.akka.redash.application.QueryExecutionWorkflow;
import io.akka.redash.application.Store;
import io.akka.redash.destinations.Destinations;
import io.akka.redash.domain.Access;
import io.akka.redash.domain.Json;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * `/api/alerts` — a condition over a query's newest result, and who hears about it
 * (SPEC-001 R136 to R142).
 *
 * <p>Evaluating by hand differs from the scheduled path in one way that matters: the state
 * and the trigger instant move only when the notification rule says a message is due, and
 * the subscribers are then told **regardless** of that rule (SPEC-001 R139). So a manual
 * evaluation of a muted alert sends nothing through the scheduled path and everything
 * through this one.
 */
@Acl(allow = @Acl.Matcher(principal = Acl.Principal.ALL))
@HttpEndpoint("/api/alerts")
public class AlertsEndpoint extends ApiBase {

  public AlertsEndpoint(ComponentClient client) {
    super(new Service(client));
  }

  @Get("")
  public HttpResponse list() {
    return answer(() -> {
      var caller = caller();
      caller.require("list_alerts");
      record(caller, Json.map("action", "list", "object_type", "alert"));
      var out = new ArrayList<Map<String, Object>>();
      for (Map<String, Object> alert : store().byOrg(Store.ALERTS, 1L)) {
        var query = service.queryById(alert.get("query_id"));
        if (service.hasAccessToQuery(caller, query, Access.VIEW_ONLY)) {
          out.add(serialize(alert));
        }
      }
      return out;
    });
  }

  @Post("")
  public HttpResponse create(HttpEntity.Strict requestBody) {
    return answer(() -> {
      guardCsrf(requestBody);
      var caller = caller();
      var request = body(requestBody);
      requireFields(request, "options", "name", "query_id");
      var query = service.queryById(request.get("query_id"));
      if (query == null) {
        throw Http.notFound();
      }
      service.requireAccessToQuery(caller, query, Access.VIEW_ONLY);
      long queryId = Service.number(query.get("id"));
      var now = Service.now();
      var alert = store().insert(Store.ALERTS, Json.map(
          "org_id", 1L,
          "parent_id", queryId,
          "query_id", queryId,
          "name", request.get("name"),
          "options", request.get("options"),
          "state", "unknown",
          "last_triggered_at", null,
          "rearm", request.get("rearm"),
          "user_id", caller.id(),
          "created_at", now,
          "updated_at", now));
      record(caller, Json.map("action", "create", "object_id", alert.get("id"),
          "object_type", "alert"));
      return serialize(alert);
    });
  }

  @Get("/{alertId}")
  public HttpResponse get(String alertId) {
    return answer(() -> {
      var caller = caller();
      var alert = requireAlert(identifier(alertId));
      var query = service.queryById(alert.get("query_id"));
      if (!service.hasAccessToQuery(caller, query, Access.VIEW_ONLY)) {
        throw Http.forbidden();
      }
      record(caller, Json.map("action", "view", "object_id", alert.get("id"),
          "object_type", "alert"));
      return serialize(alert);
    });
  }

  @Post("/{alertId}")
  public HttpResponse update(String alertId, HttpEntity.Strict requestBody) {
    return answer(() -> {
      guardCsrf(requestBody);
      var caller = caller();
      var alert = requireAlert(identifier(alertId));
      caller.requireAdminOrOwner(alert.get("user_id"));
      var updates = only(body(requestBody), "options", "name", "query_id", "rearm");
      updates.put("updated_at", Service.now());
      var updated = store().update(Store.ALERTS, alert.get("id"), updates);
      record(caller, Json.map("action", "edit", "object_id", updated.get("id"),
          "object_type", "alert"));
      return serialize(updated);
    });
  }

  @Delete("/{alertId}")
  public HttpResponse remove(String alertId) {
    return answer(() -> {
      guardCsrf(null);
      var caller = caller();
      long id = identifier(alertId);
      var alert = requireAlert(id);
      caller.requireAdminOrOwner(alert.get("user_id"));
      for (Map<String, Object> subscription : store().byParent(Store.ALERT_SUBSCRIPTIONS, id)) {
        store().delete(Store.ALERT_SUBSCRIPTIONS, subscription.get("id"));
      }
      store().delete(Store.ALERTS, id);
      return null;
    });
  }

  @Post("/{alertId}/mute")
  public HttpResponse mute(String alertId) {
    return answer(() -> {
      guardCsrf(null);
      var caller = caller();
      var alert = requireAlert(identifier(alertId));
      caller.requireAdminOrOwner(alert.get("user_id"));
      var options = new LinkedHashMap<String, Object>(Json.asMap(alert.get("options")));
      options.put("muted", true);
      store().update(Store.ALERTS, alert.get("id"), Json.map("options", options));
      record(caller, Json.map("action", "mute", "object_id", alert.get("id"),
          "object_type", "alert"));
      return null;
    });
  }

  @Delete("/{alertId}/mute")
  public HttpResponse unmute(String alertId) {
    return answer(() -> {
      guardCsrf(null);
      var caller = caller();
      var alert = requireAlert(identifier(alertId));
      caller.requireAdminOrOwner(alert.get("user_id"));
      var options = new LinkedHashMap<String, Object>(Json.asMap(alert.get("options")));
      options.put("muted", false);
      store().update(Store.ALERTS, alert.get("id"), Json.map("options", options));
      record(caller, Json.map("action", "unmute", "object_id", alert.get("id"),
          "object_type", "alert"));
      return null;
    });
  }

  @Post("/{alertId}/eval")
  public HttpResponse evaluate(String alertId) {
    return answer(() -> {
      guardCsrf(null);
      var caller = caller();
      var alert = requireAlert(identifier(alertId));
      caller.requireAdminOrOwner(alert.get("user_id"));
      var query = service.queryById(alert.get("query_id"));
      var result = query == null
          ? null : store().find(Store.QUERY_RESULTS, query.get("latest_query_data_id"));
      var settings = service.settings();
      var execution = new Execution(store(), QueryExecutionWorkflow.mailServer(settings),
          settings.host(), settings.alertsDefaultMailSubjectTemplate(),
          AlertTemplates.defaultBodyTemplate());
      execution.evaluate(alert, result, true, new ArrayList<>());
      record(caller, Json.map("action", "evaluate", "object_id", alert.get("id"),
          "object_type", "alert"));
      return null;
    });
  }

  // ------------------------------------------------------------------ subscriptions

  @Get("/{alertId}/subscriptions")
  public HttpResponse subscriptions(String alertId) {
    return answer(() -> {
      var caller = caller();
      long id = identifier(alertId);
      var alert = requireAlert(id);
      var query = service.queryById(alert.get("query_id"));
      if (!service.hasAccessToQuery(caller, query, Access.VIEW_ONLY)) {
        throw Http.forbidden();
      }
      var out = new ArrayList<Map<String, Object>>();
      for (Map<String, Object> subscription : store().byParent(Store.ALERT_SUBSCRIPTIONS, id)) {
        out.add(serializeSubscription(subscription));
      }
      return out;
    });
  }

  @Post("/{alertId}/subscriptions")
  public HttpResponse subscribe(String alertId, HttpEntity.Strict requestBody) {
    return answer(() -> {
      guardCsrf(requestBody);
      var caller = caller();
      long id = identifier(alertId);
      var alert = requireAlert(id);
      var query = service.queryById(alert.get("query_id"));
      if (!service.hasAccessToQuery(caller, query, Access.VIEW_ONLY)) {
        throw Http.forbidden();
      }
      var request = body(requestBody);
      Object destinationId = null;
      if (request.containsKey("destination_id")) {
        var destination = store().find(Store.DESTINATIONS, request.get("destination_id"));
        if (destination == null) {
          throw Http.notFound();
        }
        destinationId = Service.number(destination.get("id"));
      }
      // The source's unique index is on (alert, user, destination); a second subscription to
      // the same three is refused there rather than silently merged.
      for (Map<String, Object> existing : store().byParent(Store.ALERT_SUBSCRIPTIONS, id)) {
        boolean sameUser = java.util.Objects.equals(
            Service.numberOrNull(existing.get("user_id")), caller.id());
        boolean sameDestination = java.util.Objects.equals(
            existing.get("destination_id"), destinationId);
        if (sameUser && sameDestination) {
          throw Http.badRequest();
        }
      }
      var subscription = store().insert(Store.ALERT_SUBSCRIPTIONS, Json.map(
          "org_id", 1L,
          "parent_id", id,
          "alert_id", id,
          "user_id", caller.id(),
          "destination_id", destinationId,
          "created_at", Service.now()));
      record(caller, Json.map("action", "subscribe", "object_id", id, "object_type", "alert",
          "destination", request.get("destination_id")));
      return serializeSubscription(subscription);
    });
  }

  @Delete("/{alertId}/subscriptions/{subscriberId}")
  public HttpResponse unsubscribe(String alertId, String subscriberId) {
    return answer(() -> {
      guardCsrf(null);
      var caller = caller();
      long id = identifier(alertId);
      var subscription = store().find(Store.ALERT_SUBSCRIPTIONS, identifier(subscriberId));
      if (subscription == null) {
        throw Http.notFound();
      }
      caller.requireAdminOrOwner(subscription.get("user_id"));
      store().delete(Store.ALERT_SUBSCRIPTIONS, subscription.get("id"));
      record(caller, Json.map("action", "unsubscribe", "object_id", id, "object_type", "alert"));
      return null;
    });
  }

  // ------------------------------------------------------------------ shared

  private Map<String, Object> serialize(Map<String, Object> alert) {
    var query = service.queryById(alert.get("query_id"));
    Map<String, Object> queryDocument = null;
    if (query != null) {
      queryDocument = Serializers.query(query, service.userById(query.get("user_id")),
          service.userById(query.get("last_modified_by_id")),
          service.parameterized(query).isSafe(), false, null, null);
    }
    return Serializers.alert(alert, queryDocument, service.userById(alert.get("user_id")));
  }

  private Map<String, Object> serializeSubscription(Map<String, Object> subscription) {
    var destination = store().find(Store.DESTINATIONS, subscription.get("destination_id"));
    Map<String, Object> destinationDocument = null;
    if (destination != null) {
      destinationDocument = Serializers.destination(destination,
          Destinations.get(String.valueOf(destination.get("type"))), false);
    }
    return Serializers.alertSubscription(subscription,
        service.userById(subscription.get("user_id")), destinationDocument);
  }

  private Map<String, Object> requireAlert(long id) {
    var alert = store().find(Store.ALERTS, id);
    if (alert == null) {
      throw Http.notFound();
    }
    return alert;
  }

  /** Kept so a caller can see the list of access types an alert route accepts. */
  static List<String> accessTypes() {
    return Access.ACCESS_TYPES;
  }
}

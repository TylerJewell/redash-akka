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
import io.akka.redash.destinations.Destinations;
import io.akka.redash.domain.Configuration;
import io.akka.redash.domain.Json;
import java.util.ArrayList;
import java.util.Map;
import java.util.Objects;

/**
 * `/api/destinations` — where an alert's notification is sent (SPEC-001 R141).
 *
 * <p>All twelve of the source's types are listed and configurable. Seven of them post into
 * somebody else's chat product and are outside this run's scope, so creating one and
 * subscribing an alert to it both succeed and the delivery does nothing (SPEC-001 D-5).
 * The list itself is a wire format the front end draws its forms from, and a shorter list
 * would be a different answer to a question that is in scope.
 */
@Acl(allow = @Acl.Matcher(principal = Acl.Principal.ALL))
@HttpEndpoint("/api/destinations")
public class DestinationsEndpoint extends ApiBase {

  public DestinationsEndpoint(ComponentClient client) {
    super(new Service(client));
  }

  @Get("/types")
  public HttpResponse types() {
    return answer(() -> {
      caller().require("admin");
      return Destinations.asDocuments(service.settings());
    });
  }

  @Get("")
  public HttpResponse list() {
    return answer(() -> {
      var caller = caller();
      var out = new ArrayList<Map<String, Object>>();
      for (Map<String, Object> destination : store().byOrg(Store.DESTINATIONS, 1L)) {
        out.add(Serializers.destination(destination,
            Destinations.get(String.valueOf(destination.get("type"))), false));
      }
      record(caller, Json.map("action", "list", "object_id", "admin/destinations",
          "object_type", "destination"));
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
      var type = Destinations.registered(service.settings())
          .get(String.valueOf(request.get("type")));
      if (type == null) {
        throw Http.badRequest();
      }
      var options = Json.asMap(request.get("options"));
      if (!type.accepts(options)) {
        throw Http.badRequest();
      }
      var name = String.valueOf(request.get("name"));
      if (byName(name) != null) {
        throw Http.badRequest("Alert Destination with the name " + name + " already exists.");
      }
      var destination = store().insert(Store.DESTINATIONS, Json.map(
          "org_id", 1L,
          "name", name,
          "type", type.type(),
          "options", options,
          "user_id", caller.id(),
          "created_at", Service.now()));
      return Serializers.destination(destination, type, true);
    });
  }

  @Get("/{destinationId}")
  public HttpResponse get(String destinationId) {
    return answer(() -> {
      var caller = caller();
      caller.require("admin");
      var destination = requireDestination(identifier(destinationId));
      record(caller, Json.map("action", "view", "object_id", destinationId,
          "object_type", "destination"));
      return Serializers.destination(destination,
          Destinations.get(String.valueOf(destination.get("type"))), true);
    });
  }

  @Post("/{destinationId}")
  public HttpResponse update(String destinationId, HttpEntity.Strict requestBody) {
    return answer(() -> {
      guardCsrf(requestBody);
      var caller = caller();
      caller.require("admin");
      var destination = requireDestination(identifier(destinationId));
      var request = body(requestBody);
      var type = Destinations.registered(service.settings())
          .get(String.valueOf(request.get("type")));
      if (type == null) {
        throw Http.badRequest();
      }
      var merged = Configuration.merge(Json.asMap(destination.get("options")),
          Json.asMap(request.get("options")), type.configurationSchema());
      if (!type.accepts(merged)) {
        throw Http.badRequest();
      }
      var name = String.valueOf(request.get("name"));
      var clash = byName(name);
      if (clash != null && !Objects.equals(clash.get("id"), destination.get("id"))) {
        throw Http.badRequest("Alert Destination with the name " + name + " already exists.");
      }
      var updated = store().update(Store.DESTINATIONS, destination.get("id"),
          Json.map("type", type.type(), "name", name, "options", merged));
      return Serializers.destination(updated, type, true);
    });
  }

  @Delete("/{destinationId}")
  public HttpResponse remove(String destinationId) {
    return answer(() -> {
      guardCsrf(null);
      var caller = caller();
      caller.require("admin");
      long id = identifier(destinationId);
      requireDestination(id);
      store().delete(Store.DESTINATIONS, id);
      record(caller, Json.map("action", "delete", "object_id", destinationId,
          "object_type", "destination"));
      return Http.noContent();
    });
  }

  private Map<String, Object> byName(String name) {
    for (Map<String, Object> destination : store().byOrg(Store.DESTINATIONS, 1L)) {
      if (name.equals(destination.get("name"))) {
        return destination;
      }
    }
    return null;
  }

  private Map<String, Object> requireDestination(long id) {
    var destination = store().find(Store.DESTINATIONS, id);
    if (destination == null) {
      throw Http.notFound();
    }
    return destination;
  }
}

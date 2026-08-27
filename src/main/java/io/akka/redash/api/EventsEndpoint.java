package io.akka.redash.api;

import akka.http.javadsl.model.HttpEntity;
import akka.http.javadsl.model.HttpResponse;
import akka.javasdk.annotations.Acl;
import akka.javasdk.annotations.http.Get;
import akka.javasdk.annotations.http.HttpEndpoint;
import akka.javasdk.annotations.http.Post;
import akka.javasdk.client.ComponentClient;
import io.akka.redash.application.Store;
import io.akka.redash.domain.Json;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * `/api/events` — the audit trail (SPEC-001 R145, R146).
 *
 * <p>Posting is how the front end records a page view; every element of the posted list is
 * recorded exactly as a handler's own event is, so a page view carries the same actor,
 * client and address as a query edit does.
 */
@Acl(allow = @Acl.Matcher(principal = Acl.Principal.ALL))
@HttpEndpoint("/api/events")
public class EventsEndpoint extends ApiBase {

  public EventsEndpoint(ComponentClient client) {
    super(new Service(client));
  }

  @Post("")
  public HttpResponse post(HttpEntity.Strict requestBody) {
    return answer(() -> {
      guardCsrf(requestBody);
      var caller = caller();
      for (Object element : bodyList(requestBody)) {
        record(caller, Json.asMap(element));
      }
      return null;
    });
  }

  @Get("")
  public HttpResponse list() {
    return answer(() -> {
      var caller = caller();
      caller.require("admin");
      int page = intParam("page", 1);
      int pageSize = intParam("page_size", 25);
      var events = new ArrayList<Map<String, Object>>(store().byOrg(Store.EVENTS, 1L));
      events.sort(Comparator
          .<Map<String, Object>, String>comparing(event ->
              String.valueOf(event.getOrDefault("created_at", "")))
          .thenComparing(event -> Service.number(event.get("id")))
          .reversed());
      return Listing.paginate(events, page, pageSize, Serializers::event);
    });
  }

  /** Kept so the list ordering can be checked without a runtime. */
  static List<Map<String, Object>> newestFirst(List<Map<String, Object>> events) {
    var out = new ArrayList<>(events);
    out.sort(Comparator
        .<Map<String, Object>, String>comparing(event ->
            String.valueOf(event.getOrDefault("created_at", "")))
        .thenComparing(event -> Service.number(event.get("id")))
        .reversed());
    return out;
  }
}

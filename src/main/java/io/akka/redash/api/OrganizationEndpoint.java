package io.akka.redash.api;

import akka.http.javadsl.model.HttpResponse;
import akka.javasdk.annotations.Acl;
import akka.javasdk.annotations.http.Get;
import akka.javasdk.annotations.http.HttpEndpoint;
import akka.javasdk.client.ComponentClient;
import io.akka.redash.application.Store;
import io.akka.redash.domain.Access;
import io.akka.redash.domain.Json;

/**
 * `/api/organization/status` — the five counters the shell draws before anything else, each
 * counted as the caller sees it rather than as the organisation holds it.
 */
@Acl(allow = @Acl.Matcher(principal = Acl.Principal.ALL))
@HttpEndpoint("/api/organization")
public class OrganizationEndpoint extends ApiBase {

  public OrganizationEndpoint(ComponentClient client) {
    super(new Service(client));
  }

  @Get("/status")
  public HttpResponse status() {
    return answer(() -> {
      var caller = caller();
      long users = 0;
      for (var user : service.allUsers()) {
        if (user.get("disabled_at") == null) {
          users++;
        }
      }
      long alerts = 0;
      for (var alert : store().byOrg(Store.ALERTS, 1L)) {
        var query = service.queryById(alert.get("query_id"));
        if (service.hasAccessToQuery(caller, query, Access.VIEW_ONLY)) {
          alerts++;
        }
      }
      long dataSources = 0;
      for (var dataSource : service.allDataSources()) {
        if (service.hasAccessToDataSource(caller, dataSource, Access.VIEW_ONLY)) {
          dataSources++;
        }
      }
      long queries = service.visibleQueries(caller, true, false).size();
      long dashboards = 0;
      for (var dashboard : store().byOrg(Store.DASHBOARDS, 1L)) {
        if (!Boolean.TRUE.equals(dashboard.get("is_archived"))) {
          dashboards++;
        }
      }
      return Json.map("object_counters", Json.map(
          "users", users,
          "alerts", alerts,
          "data_sources", dataSources,
          "queries", queries,
          "dashboards", dashboards));
    });
  }
}

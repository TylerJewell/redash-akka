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
import java.util.LinkedHashMap;
import java.util.List;

/**
 * `/api/settings/organization` — the toggles an administrator can move (SPEC-001 R6, R7).
 *
 * <p>`auth_google_apps_domains` is kept outside the settings map, in the organisation's own
 * `google_apps_domains` key, and is read and written from there. Every other name goes
 * through the setting validator, which refuses one that is neither stored nor defaulted.
 */
@Acl(allow = @Acl.Matcher(principal = Acl.Principal.ALL))
@HttpEndpoint("/api/settings")
public class SettingsEndpoint extends ApiBase {

  static final String GOOGLE_APPS_DOMAINS = "google_apps_domains";

  public SettingsEndpoint(ComponentClient client) {
    super(new Service(client));
  }

  @Get("/organization")
  public HttpResponse get() {
    return answer(() -> {
      var caller = caller();
      caller.require("admin");
      return ClientConfig.asSettingsDocument(service.settings(), service.currentOrg());
    });
  }

  @Post("/organization")
  public HttpResponse update(HttpEntity.Strict requestBody) {
    return answer(() -> {
      guardCsrf(requestBody);
      var caller = caller();
      caller.require("admin");
      var org = service.requireOrg();
      var request = body(requestBody);

      var container = new LinkedHashMap<String, Object>(Json.asMap(org.get("settings")));
      var inner = new LinkedHashMap<String, Object>(Json.asMap(container.get("settings")));
      var previous = new LinkedHashMap<String, Object>();

      for (var entry : request.entrySet()) {
        if ("auth_google_apps_domains".equals(entry.getKey())) {
          previous.put(entry.getKey(), container.getOrDefault(GOOGLE_APPS_DOMAINS, List.of()));
          container.put(GOOGLE_APPS_DOMAINS, entry.getValue());
        } else {
          previous.put(entry.getKey(), inner.get(entry.getKey()));
          if (!service.settings().organizationDefaults().containsKey(entry.getKey())) {
            throw Http.badRequest();
          }
          inner.put(entry.getKey(), entry.getValue());
        }
      }
      container.put("settings", inner);
      var updated = store().update(Store.ORGANIZATIONS, org.get("id"),
          Json.map("settings", container));

      record(caller, Json.map("action", "edit", "object_id", org.get("id"),
          "object_type", "settings", "new_values", request, "previous_values", previous));
      return ClientConfig.asSettingsDocument(service.settings(), updated);
    });
  }
}

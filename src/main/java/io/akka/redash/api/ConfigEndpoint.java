package io.akka.redash.api;

import akka.http.javadsl.model.HttpResponse;
import akka.javasdk.annotations.Acl;
import akka.javasdk.annotations.http.Get;
import akka.javasdk.annotations.http.HttpEndpoint;
import akka.javasdk.client.ComponentClient;
import io.akka.redash.domain.Json;

/**
 * `/api/config` — the same client configuration `/api/session` carries, without needing a
 * session. It is what the front end reads before it knows whether anybody is logged in.
 */
@Acl(allow = @Acl.Matcher(principal = Acl.Principal.ALL))
@HttpEndpoint("/api/config")
public class ConfigEndpoint extends ApiBase {

  public ConfigEndpoint(ComponentClient client) {
    super(new Service(client));
  }

  @Get("")
  public HttpResponse config() {
    return answer(() -> {
      var caller = Sessions.resolve(service, header("Cookie"), header("Authorization"),
          queryParam("api_key"), null);
      var org = service.currentOrg();
      return Json.map(
          "org_slug", org == null ? "default" : org.get("slug"),
          "client_config", ClientConfig.of(service.settings(), org, caller, basePath(), false));
    });
  }

  String basePath() {
    var configured = service.settings().host();
    if (!configured.isEmpty()) {
      return configured + "/";
    }
    var host = header("Host");
    var scheme = header("X-Forwarded-Proto");
    return (scheme == null ? "http" : scheme) + "://" + (host == null ? "localhost" : host) + "/";
  }
}

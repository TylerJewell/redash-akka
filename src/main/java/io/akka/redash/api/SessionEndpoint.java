package io.akka.redash.api;

import akka.javasdk.annotations.Acl;
import akka.javasdk.annotations.http.Get;
import akka.javasdk.annotations.http.HttpEndpoint;
import akka.javasdk.client.ComponentClient;
import akka.http.javadsl.model.HttpResponse;
import io.akka.redash.domain.Json;
import java.util.ArrayList;
import java.util.List;

/** `/api/session` — who the caller is, and everything the front end needs to draw. */
@Acl(allow = @Acl.Matcher(principal = Acl.Principal.ALL))
@HttpEndpoint("/api/session")
public class SessionEndpoint extends ApiBase {

  public SessionEndpoint(ComponentClient client) {
    super(new Service(client));
  }

  @Get("")
  public HttpResponse session() {
    return answer(() -> {
      var caller = caller();
      var org = service.currentOrg();
      Object user;
      if (caller.isApiUser()) {
        user = Json.map("permissions", List.of(), "apiKey", caller.apiKey());
      } else {
        var person = caller.user();
        user = Json.map(
            "profile_image_url", Serializers.profileImageUrl(person),
            "id", person.get("id"),
            "name", person.get("name"),
            "email", person.get("email"),
            "groups", person.get("groups"),
            "permissions", caller.permissions());
      }
      return Json.map(
          "user", user,
          "messages", messages(caller),
          "org_slug", org == null ? "default" : org.get("slug"),
          "client_config", ClientConfig.of(service.settings(), org, caller, basePath(), false));
    });
  }

  /**
   * The two notices the shell shows above everything else. An unverified address is one;
   * the deprecated parameters-in-embeds toggle is the other.
   */
  private List<String> messages(Caller caller) {
    var out = new ArrayList<String>();
    if (caller.user() != null && Boolean.FALSE.equals(caller.user().get("is_email_verified"))) {
      out.add("email-not-verified");
    }
    if (service.settings().allowParametersInEmbeds()) {
      out.add("using-deprecated-embed-feature");
    }
    return out;
  }

  /** Where the front end thinks it is served from, which it prefixes every link with. */
  String basePath() {
    var host = header("Host");
    var scheme = header("X-Forwarded-Proto");
    var configured = service.settings().host();
    if (!configured.isEmpty()) {
      return configured + "/";
    }
    return (scheme == null ? "http" : scheme) + "://" + (host == null ? "localhost" : host) + "/";
  }
}

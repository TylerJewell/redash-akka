package io.akka.redash.api;

import akka.http.javadsl.model.HttpResponse;
import akka.http.javadsl.model.headers.RawHeader;
import akka.javasdk.annotations.Acl;
import akka.javasdk.annotations.http.Get;
import akka.javasdk.annotations.http.HttpEndpoint;
import akka.javasdk.client.ComponentClient;
import io.akka.redash.domain.Json;
import java.nio.charset.StandardCharsets;

/**
 * Everything the browser asks for that is not the API: the application shell, the two
 * embeddable routes, and the paths the front end's own router owns.
 *
 * <p>The shell is one file and every application path answers with it, because the routing
 * happens in the browser. The three embeddable routes append `frame-ancestors *;` to the
 * content security policy and drop the frame-options header, which is what lets a
 * visualisation be put in somebody else's page (SPEC-001 R152).
 *
 * <p>The runtime's own namespace is deliberately outside the catch-all: a route that
 * answers everything answers the health check too, and a health check that gets an HTML
 * page reports a runtime that never started.
 */
@Acl(allow = @Acl.Matcher(principal = Acl.Principal.ALL))
@HttpEndpoint("")
public class StaticEndpoint extends ApiBase {

  public StaticEndpoint(ComponentClient client) {
    super(new Service(client));
  }

  @Get("/")
  public HttpResponse root() {
    return answer(() -> {
      if (!service.isSetUp()) {
        return Http.redirect("/setup");
      }
      return shell();
    });
  }

  @Get("/dashboard/{slug}")
  public HttpResponse dashboard(String slug) {
    return answer(() -> embeddable(shell()));
  }

  @Get("/embed/query/{queryId}/visualization/{visualizationId}")
  public HttpResponse embed(String queryId, String visualizationId) {
    return answer(() -> {
      // The query's own key is what an embedded visualisation is addressed with, and it is
      // recognised only against that query — so the identifier has to travel with it.
      var caller = pageCaller("/embed/query/" + queryId + "/visualization/"
          + visualizationId, queryParam("api_key"), identifier(queryId));
      record(caller, Json.map("action", "view", "object_id", visualizationId,
          "object_type", "visualization", "query_id", queryId, "embed", true,
          "referer", header("Referer")));
      return embeddable(shell());
    });
  }

  @Get("/public/dashboards/{token}")
  public HttpResponse publicDashboard(String token) {
    return answer(() -> {
      // The token in the path is the credential: this is the address somebody is given who
      // has no account at all, so resolving the caller without it sends them to the sign-in
      // page and the shared dashboard is not shared (SPEC-001 R11).
      var caller = pageCaller("/public/dashboards/" + token, token);
      Object dashboardId = null;
      if (caller.isApiUser() && caller.apiObject() != null) {
        dashboardId = caller.apiObject().get("id");
      } else {
        var apiKey = service.apiKeyByValue(token);
        if (apiKey == null) {
          throw Http.notFound();
        }
        dashboardId = apiKey.get("object_id");
      }
      record(caller, Json.map("action", "view", "object_id", dashboardId,
          "object_type", "dashboard", "public", true,
          "headless", hasQueryParam("embed"), "referer", header("Referer")));
      return embeddable(shell());
    });
  }

  /**
   * The built front end: the bundles, the fonts, the images and the map data.
   *
   * <p>It is declared rather than left to the runtime because the catch-all below claims
   * every two-segment path, and `static` is on its reserved list — so without this the
   * bundle answers redash's own not-found, the shell renders a blank page, and nothing
   * about it looks like a routing problem.
   */
  @Get("/static/**")
  public HttpResponse asset(akka.http.javadsl.model.HttpRequest request) {
    // Only the leading slash comes off: the build writes its output into a `static`
    // directory of its own, so `/static/app.js` is `static-resources/static/app.js` and
    // stripping the prefix as well would look for the file one directory too high.
    return akka.javasdk.http.HttpResponses.staticResource(request, "/");
  }

  // The application's own paths, at the three depths its router uses. A page opened
  // directly — /queries/5/source — has to answer the shell rather than a not-found.
  @Get("/{first}")
  public HttpResponse oneDeep(String first) {
    return answer(() -> application(first, "/" + first));
  }

  @Get("/{first}/{second}")
  public HttpResponse twoDeep(String first, String second) {
    return answer(() -> application(first, "/" + first + "/" + second));
  }

  @Get("/{first}/{second}/{third}")
  public HttpResponse threeDeep(String first, String second, String third) {
    // `/<org_slug>/oauth/google` is a route of the source's, not a page: a multi-organisation
    // deployment sends somebody to their own organisation's sign-in through it. It arrives
    // here because this endpoint owns every three-segment path.
    if ("oauth".equals(second) && "google".equals(third)) {
      return answer(() -> AuthEndpoint.googleAuthorization(service, queryParam("next")));
    }
    return answer(() -> application(first, "/" + first + "/" + second + "/" + third));
  }

  @Get("/{first}/{second}/{third}/{fourth}")
  public HttpResponse fourDeep(String first, String second, String third, String fourth) {
    return answer(() -> application(first,
        "/" + first + "/" + second + "/" + third + "/" + fourth));
  }

  /**
   * A fifth depth, because the source's own catch-all has no depth at all.
   *
   * <p>redash registers `/<path:path>`, which matches however many segments a caller sends;
   * a path parameter here matches one segment, so a depth is a route. Five is what the
   * front end's own router reaches — `/dashboards/1-name/…` is four — and a path longer than
   * that answers not-found where the source would answer the shell. Listed in the README as
   * a difference.
   */
  @Get("/{first}/{second}/{third}/{fourth}/{fifth}")
  public HttpResponse fiveDeep(String first, String second, String third, String fourth,
      String fifth) {
    return answer(() -> application(first,
        "/" + first + "/" + second + "/" + third + "/" + fourth + "/" + fifth));
  }

  private Object application(String first, String path) {
    if (RESERVED.contains(first)) {
      throw Http.notFound();
    }
    pageCaller(path);
    return shell();
  }

  /**
   * The caller, or a redirect to the login page carrying where they were going.
   *
   * <p>This is the one place a missing session is not a 404. The source answers 404 for a
   * path under `/api/` or a request marked as coming from a script, and sends everything
   * else to the login page — so that opening a bookmarked dashboard in a fresh browser
   * lands on a login form and then on the dashboard, rather than on a JSON refusal
   * (SPEC-001 R16). The address it returns to is absolute, built from the request itself.
   */
  private Caller pageCaller(String path) {
    return pageCaller(path, null);
  }

  /**
   * @param pathToken a key carried in the path rather than in the query string, which is
   *     the third place the source looks for one and the only one a public dashboard's
   *     address has
   */
  private Caller pageCaller(String path, String pathToken) {
    return pageCaller(path, pathToken, null);
  }

  private Caller pageCaller(String path, String pathToken, Object queryId) {
    var presented = queryParam("api_key");
    if (presented == null || presented.isBlank()) {
      presented = pathToken;
    }
    var caller = Sessions.resolve(service, header("Cookie"), header("Authorization"),
        presented, queryId);
    if (!caller.isAuthenticated()) {
      var host = header("Host");
      var where = (host == null ? service.baseUrl() : "http://" + host) + path;
      throw Http.Refused.redirectTo("/login?next=" + where);
    }
    return caller;
  }

  /** The namespaces this catch-all must not answer for. */
  private static final java.util.Set<String> RESERVED = java.util.Set.of(
      "api", "static", "akka", "health", "ready", "ping", "status.json", "favicon.ico");

  private HttpResponse shell() {
    var html = INDEX.get();
    if (html == null) {
      throw Http.notFound();
    }
    // The source sends this file rather than rendering it, and its file-sending helper
    // names the file and forbids caching. A browser that cached the shell would keep
    // serving yesterday's asset names after a deployment.
    return Http.html(html)
        .addHeader(RawHeader.create("Content-Disposition", "inline; filename=index.html"))
        .addHeader(RawHeader.create("Cache-Control", "no-cache, max-age=0"));
  }

  /** The two header changes an embeddable page makes (SPEC-001 R152). */
  private HttpResponse embeddable(HttpResponse response) {
    var policy = service.settings().contentSecurityPolicy() + " frame-ancestors *;";
    var out = HttpResponse.create()
        .withStatus(response.status())
        .withEntity(response.entity());
    for (var header : response.getHeaders()) {
      var name = header.name();
      if (name.equalsIgnoreCase("X-Frame-Options")
          || name.equalsIgnoreCase("Content-Security-Policy")
          || name.equalsIgnoreCase("X-Content-Security-Policy")) {
        continue;
      }
      out = out.addHeader(header);
    }
    return out
        .addHeader(RawHeader.create("Content-Security-Policy", policy))
        .addHeader(RawHeader.create("X-Content-Security-Policy", policy));
  }

  /** redash's own `index.html`, read once out of the shipped front end. */
  private static final java.util.function.Supplier<String> INDEX =
      new java.util.function.Supplier<>() {
        private volatile String cached;

        @Override
        public String get() {
          var held = cached;
          if (held != null) {
            return held;
          }
          try (var stream = StaticEndpoint.class.getClassLoader()
              .getResourceAsStream("static-resources/index.html")) {
            if (stream == null) {
              return null;
            }
            cached = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            return cached;
          } catch (java.io.IOException e) {
            return null;
          }
        }
      };
}

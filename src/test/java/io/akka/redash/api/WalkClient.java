package io.akka.redash.api;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * A browser-shaped client for the integration tests: it keeps cookies, follows nothing, and
 * sends the cross-site token back the way the front end does.
 *
 * <p>The tests need this rather than the test kit's own client because what is under test
 * *is* the session — a client that does not keep the cookie cannot tell a working sign-in
 * from a broken one.
 */
final class WalkClient {

  record Answer(int status, String body, Map<String, List<String>> headers) {}

  private static final Pattern CSRF_INPUT =
      Pattern.compile("name=\"csrf_token\"[^>]*value=\"([^\"]*)\"");

  private final String base;
  private final HttpClient client;
  private final Map<String, String> cookies = new LinkedHashMap<>();

  WalkClient(String base) {
    this.base = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    this.client = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NEVER)
        .connectTimeout(Duration.ofSeconds(20))
        .build();
  }

  Answer get(String path) {
    return send(HttpRequest.newBuilder(URI.create(base + path)).GET(), null);
  }

  /** A read carrying one header, for a route whose whole input is a header. */
  Answer getWithHeader(String path, String name, String value) {
    return send(HttpRequest.newBuilder(URI.create(base + path)).GET()
        .header(name, value), null);
  }

  Answer getJson(String path) {
    return send(HttpRequest.newBuilder(URI.create(base + path)).GET()
        .header("X-Requested-With", "XMLHttpRequest"), null);
  }

  Answer json(String method, String path, String body) {
    return send(HttpRequest.newBuilder(URI.create(base + path))
        .method(method, HttpRequest.BodyPublishers.ofString(body == null ? "" : body))
        .header("Content-Type", "application/json"), null);
  }

  Answer form(String path, String body) {
    return send(HttpRequest.newBuilder(URI.create(base + path))
        .POST(HttpRequest.BodyPublishers.ofString(body))
        .header("Content-Type", "application/x-www-form-urlencoded"), null);
  }

  /**
   * Read a page, then post its form back with the token it carried.
   *
   * <p>The single-request form is right for a caller that is already signed in, where the
   * guard never fires. An unauthenticated post is checked, and the token it is checked
   * against comes off the page.
   */
  Answer getAndPost(String path, String body) {
    var page = get(path);
    var matcher = CSRF_INPUT.matcher(page.body());
    var token = matcher.find() ? matcher.group(1) : cookies.getOrDefault("csrf_token", "");
    return form(path, body + "&csrf_token=" + token);
  }

  /** Sign in the way the login page does: read the token out of the form, then post it. */
  void login(String email, String password) {
    var page = get("/login");
    var matcher = CSRF_INPUT.matcher(page.body());
    var token = matcher.find() ? matcher.group(1) : "";
    form("/login", "email=" + java.net.URLEncoder.encode(email,
        java.nio.charset.StandardCharsets.UTF_8)
        + "&password=" + java.net.URLEncoder.encode(password,
            java.nio.charset.StandardCharsets.UTF_8)
        + "&csrf_token=" + token);
  }

  private Answer send(HttpRequest.Builder builder, String ignored) {
    if (!cookies.isEmpty()) {
      var out = new ArrayList<String>();
      cookies.forEach((name, value) -> out.add(name + "=" + value));
      builder.header("Cookie", String.join("; ", out));
    }
    var csrf = cookies.get("csrf_token");
    if (csrf != null) {
      builder.header("X-CSRF-TOKEN", csrf);
    }
    try {
      // One retry on an end-of-stream. The server closes an idle connection and the client
      // pools it, so the first request after a pause can fail on a socket that was already
      // gone. That is the connection, not the answer.
      HttpResponse<String> response;
      try {
        response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
      } catch (java.io.IOException retryable) {
        response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
      }
      for (String header : response.headers().allValues("set-cookie")) {
        int semicolon = header.indexOf(';');
        var pair = semicolon < 0 ? header : header.substring(0, semicolon);
        int equals = pair.indexOf('=');
        if (equals > 0) {
          cookies.put(pair.substring(0, equals).strip(), pair.substring(equals + 1));
        }
      }
      return new Answer(response.statusCode(), response.body(), response.headers().map());
    } catch (Exception e) {
      throw new IllegalStateException("the request could not be made", e);
    }
  }
}

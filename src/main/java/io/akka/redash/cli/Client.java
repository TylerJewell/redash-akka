package io.akka.redash.cli;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/**
 * How the command line reaches the service.
 *
 * <p>It authenticates with the instance secret rather than with a session, because a
 * command line has no browser to sign in with and the person running it is already on the
 * host. That is the same trust the source's command line has, arrived at differently: it
 * opens the database directly.
 */
public final class Client {

  /** A refusal from the service, carried out to the exit code. */
  public static final class Failure extends RuntimeException {
    public Failure(String message) {
      super(message, null, false, false);
    }
  }

  public static final String HEADER = "X-Redash-Cli-Secret";

  private final String base;
  private final java.net.http.HttpClient http;
  private final String secret;

  public Client(String base) {
    this(base, io.akka.redash.domain.Settings.fromEnvironment().secretKey());
  }

  public Client(String base, String secret) {
    this.base = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    this.secret = secret;
    this.http = java.net.http.HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(20))
        .followRedirects(java.net.http.HttpClient.Redirect.NEVER)
        .build();
  }

  public Object get(String path) {
    return send(HttpRequest.newBuilder(URI.create(base + path)).GET());
  }

  public Object post(String path, Map<String, Object> body) {
    return send(HttpRequest.newBuilder(URI.create(base + path))
        .POST(HttpRequest.BodyPublishers.ofString(
            io.akka.redash.domain.Json.dumps(body), StandardCharsets.UTF_8))
        .header("Content-Type", "application/json"));
  }

  private Object send(HttpRequest.Builder builder) {
    try {
      var request = builder.header(HEADER, secret).build();
      HttpResponse<String> response;
      try {
        response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      } catch (java.io.IOException retryable) {
        // One retry, and only for a read. A connection that ends before the answer does
        // says nothing about whether the request was carried out, so re-sending a command
        // that changes something could carry it out twice — the caller is told instead.
        if (!"GET".equals(request.method())) {
          throw retryable;
        }
        response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      }
      if (response.statusCode() == 404) {
        throw new Failure("No such command on this instance.");
      }
      if (response.statusCode() >= 400) {
        throw new Failure(response.body());
      }
      return io.akka.redash.domain.Json.loads(response.body());
    } catch (java.io.IOException e) {
      throw new Failure("Could not reach " + base + ": " + e.getMessage());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new Failure("Interrupted");
    }
  }
}

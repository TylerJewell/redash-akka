package io.akka.redash.api;

import akka.NotUsed;
import akka.http.javadsl.model.HttpRequest;
import akka.http.javadsl.model.HttpResponse;
import akka.javasdk.annotations.Acl;
import akka.javasdk.annotations.http.Get;
import akka.javasdk.annotations.http.HttpEndpoint;
import akka.javasdk.client.ComponentClient;
import akka.javasdk.http.HttpResponses;
import akka.stream.javadsl.Source;
import io.akka.redash.application.AlertsView;
import io.akka.redash.application.QueriesView;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * redash's own alerts screen, served by this port.
 *
 * <p>The front end under {@code frontend/} is redash's, built from its own source, with one
 * change: {@code AlertsList} subscribes to {@link #alertsStream} instead of fetching
 * {@code /api/alerts} once. Components, styling, routes, layout and assets are left alone,
 * which is what makes comparing the two screens mean anything (RENDERING.md R3, R4) — delete
 * the subscription and the list has no other route to its data.
 *
 * <p>{@code /api/session} is served from a fixed document rather than from a login. Accounts
 * are redash's, not this slice's, and a login form would be a second capability built to
 * satisfy a screenshot.
 */
@Acl(allow = @Acl.Matcher(principal = Acl.Principal.ALL))
@HttpEndpoint
public class AlertsUiEndpoint {

  /** How often the stream looks for a change. Nothing on the page asks; this pushes. */
  private static final Duration TICK = Duration.ofMillis(500);

  private final ComponentClient componentClient;

  public AlertsUiEndpoint(ComponentClient componentClient) {
    this.componentClient = componentClient;
  }

  @Get("/")
  public HttpResponse index() {
    return HttpResponses.staticResource("index.html");
  }

  /**
   * The front end's own routes get the shell, so {@code /alerts} is openable directly
   * rather than only reachable by clicking. They are listed rather than caught by a
   * wildcard: a wildcard at the root overlaps the {@code /api} endpoint's prefix and the
   * runtime refuses to start both.
   */
  @Get("/alerts")
  public HttpResponse alertsPage() {
    return HttpResponses.staticResource("index.html");
  }

  @Get("/queries")
  public HttpResponse queriesPage() {
    return HttpResponses.staticResource("index.html");
  }

  @Get("/dashboards")
  public HttpResponse dashboardsPage() {
    return HttpResponses.staticResource("index.html");
  }

  /** The built bundle, at the same paths the original serves it from. */
  @Get("/static/**")
  public HttpResponse asset(HttpRequest request) {
    return HttpResponses.staticResource(request, "/");
  }

  @Get("/api/session")
  public HttpResponse session() {
    return HttpResponses.staticResource("session.json");
  }

  /**
   * The counters the shell asks for before it renders anything. It is the only other call
   * the alerts route makes, and a 404 here leaves the page on its loading indicator
   * forever rather than failing visibly.
   */
  @Get("/api/organization/status")
  public Map<String, Object> organizationStatus() {
    var alerts = componentClient.forView().method(AlertsView::all).invoke().items().size();
    var queries = componentClient.forView().method(QueriesView::all).invoke().items().size();
    return Map.of(
        "object_counters",
        Map.of("users", 1, "alerts", alerts, "data_sources", 1, "queries", queries, "dashboards", 0));
  }

  /**
   * SPEC-001 R25, and RENDERING.md R1: one subscription, whose **first frame carries the
   * current state** so the first render needs no separate round trip, and whose first frame
   * after a reconnect is current state again rather than a resumed position. A watcher that
   * dropped out is therefore never left holding a superseded state; the cost is one
   * redundant frame per connection, which is the trade this port chose because the original
   * — a front end that fetches once — has no answer to copy.
   *
   * <p>Under {@code /api/streams/} rather than {@code /api/alerts/stream} because the latter
   * is indistinguishable from an alert whose identifier is the word "stream".
   */
  @Get("/api/streams/alerts")
  public HttpResponse alertsStream() {
    Source<List<Map<String, Object>>, NotUsed> frames =
        Source.tick(Duration.ZERO, TICK, "tick")
            .map(ignored -> currentAlerts())
            .statefulMapConcat(
                () -> {
                  var previous = new String[] {null};
                  return alerts -> {
                    var rendered = alerts.toString();
                    // The first frame always goes out; after that only changes do, so a page
                    // left open on unchanged state receives nothing.
                    if (rendered.equals(previous[0])) {
                      return List.of();
                    }
                    previous[0] = rendered;
                    return List.<List<Map<String, Object>>>of(alerts);
                  };
                })
            .mapMaterializedValue(ignored -> NotUsed.getInstance());
    return HttpResponses.serverSentEvents(frames);
  }

  private List<Map<String, Object>> currentAlerts() {
    var alerts = componentClient.forView().method(AlertsView::all).invoke();
    var out = new ArrayList<Map<String, Object>>(alerts.items().size());
    for (AlertsView.Entry alert : alerts.items()) {
      out.add(asRedashAlert(alert));
    }
    return List.copyOf(out);
  }

  /**
   * One alert in the shape `AlertsList` renders: a name, a state, who made it, and the two
   * timestamps its last two columns show.
   */
  private static Map<String, Object> asRedashAlert(AlertsView.Entry alert) {
    var options = new LinkedHashMap<String, Object>();
    options.put("column", "value");
    options.put("muted", alert.muted() == 1);

    var out = new LinkedHashMap<String, Object>();
    out.put("id", alert.alertId());
    out.put("name", alert.name());
    out.put("options", options);
    out.put("state", alert.state().toLowerCase(Locale.ROOT));
    out.put("last_triggered_at", instantOrNull(alert.lastTriggeredAtMillis()));
    out.put("updated_at", instantOrNull(alert.updatedAtMillis()));
    out.put("created_at", instantOrNull(alert.createdAtMillis()));
    out.put("rearm", null);
    out.put("user", Map.of("id", 1, "name", "Port", "email", "port@example.com"));
    return out;
  }

  private static String instantOrNull(long millis) {
    return millis == 0 ? null : Instant.ofEpochMilli(millis).toString();
  }
}

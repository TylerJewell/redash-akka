package io.akka.redash.api;

import akka.javasdk.annotations.Acl;
import akka.javasdk.annotations.http.Get;
import akka.javasdk.annotations.http.HttpEndpoint;
import akka.javasdk.annotations.http.Post;
import akka.javasdk.client.ComponentClient;
import akka.javasdk.http.AbstractHttpEndpoint;
import akka.javasdk.http.HttpException;
import io.akka.redash.application.AlertEntity;
import io.akka.redash.application.AlertsView;
import io.akka.redash.application.DataSourceRegistry;
import io.akka.redash.application.ExecutionTrackerEntity;
import io.akka.redash.application.QueriesView;
import io.akka.redash.application.RefreshPipeline;
import io.akka.redash.application.ResultEntity;
import io.akka.redash.application.QueryEntity;
import io.akka.redash.domain.Schedule;
import io.akka.redash.domain.StoredResult;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * The capability's own surface: save a query with a schedule, put an alert on it, run the
 * sweep, and read what came back.
 *
 * <p>The sweep is a call rather than a timer that fires on its own. The original's sweep is
 * a periodic job on a thirty-second interval, and a benchmark that has to wait thirty
 * seconds to compare one decision measures the interval rather than the decision. The timer
 * that drives it in ordinary use is {@link io.akka.redash.application.SweepTimer}.
 */
@Acl(allow = @Acl.Matcher(principal = Acl.Principal.ALL))
@HttpEndpoint("/api")
public class RedashEndpoint extends AbstractHttpEndpoint {

  private final ComponentClient componentClient;

  public RedashEndpoint(ComponentClient componentClient) {
    this.componentClient = componentClient;
  }

  public record SaveQuery(
      String queryText,
      String dataSourceId,
      Long intervalSeconds,
      String intervalRaw,
      String timeOfDay,
      String dayOfWeek,
      String until,
      Boolean scheduleDisabled,
      Boolean noSchedule) {}

  public record CreateAlert(
      String name,
      String queryId,
      String column,
      String operator,
      String threshold,
      Boolean thresholdIsNumber,
      String selector,
      Integer rearmSeconds,
      Boolean muted) {}

  @Post("/queries/{queryId}")
  public RedashViews.QueryResponse saveQuery(String queryId, SaveQuery body) {
    Schedule schedule =
        Boolean.TRUE.equals(body.noSchedule())
            ? null
            : new Schedule(
                body.intervalSeconds(),
                body.intervalRaw() != null ? body.intervalRaw()
                    : (body.intervalSeconds() == null ? null : String.valueOf(body.intervalSeconds())),
                body.timeOfDay(),
                body.dayOfWeek(),
                body.until(),
                body.scheduleDisabled());
    return RedashViews.toApi(
        componentClient
            .forEventSourcedEntity(queryId)
            .method(QueryEntity::save)
            .invoke(new QueryEntity.Save(body.queryText(), body.dataSourceId(), schedule)));
  }

  @Get("/queries/{queryId}")
  public RedashViews.QueryResponse query(String queryId) {
    var state = componentClient.forEventSourcedEntity(queryId).method(QueryEntity::get).invoke();
    if (!state.created()) {
      throw HttpException.notFound();
    }
    return RedashViews.toApi(state);
  }

  @Get("/queries")
  public QueriesView.Entries queries() {
    return componentClient.forView().method(QueriesView::all).invoke();
  }

  @Post("/queries/{queryId}/archive")
  public RedashViews.QueryResponse archive(String queryId) {
    return RedashViews.toApi(
        componentClient.forEventSourcedEntity(queryId).method(QueryEntity::archive).invoke());
  }

  @Post("/alerts/{alertId}")
  public RedashViews.AlertResponse createAlert(String alertId, CreateAlert body) {
    return RedashViews.toApi(
        componentClient
            .forEventSourcedEntity(alertId)
            .method(AlertEntity::create)
            .invoke(
                new AlertEntity.Create(
                    body.name() == null ? alertId : body.name(),
                    body.queryId(),
                    body.column(),
                    body.operator(),
                    body.threshold(),
                    Boolean.TRUE.equals(body.thresholdIsNumber()),
                    body.selector(),
                    body.rearmSeconds(),
                    Boolean.TRUE.equals(body.muted()))));
  }

  @Post("/alerts/{alertId}/subscribers/{subscriber}")
  public RedashViews.AlertResponse subscribe(String alertId, String subscriber) {
    return RedashViews.toApi(
        componentClient.forEventSourcedEntity(alertId).method(AlertEntity::subscribe).invoke(subscriber));
  }

  @Get("/alerts/{alertId}")
  public RedashViews.AlertResponse alert(String alertId) {
    var state = componentClient.forEventSourcedEntity(alertId).method(AlertEntity::get).invoke();
    if (!state.created()) {
      throw HttpException.notFound();
    }
    return RedashViews.toApi(state);
  }

  /** Run one sweep. `at` is read from the query string so a caller can name the instant. */
  @Post("/sweep")
  public RefreshPipeline.SweepOutcome sweep() {
    return pipeline().sweep(instantFromQuery());
  }

  @Post("/queries/{queryId}/refresh")
  public RefreshPipeline.RunOutcome refresh(String queryId) {
    return pipeline().execute(queryId, instantFromQuery());
  }

  /**
   * R15: what a caller gets when it asks for the answer rather than for a run. `maxAge` is
   * read from the query string, and a non-path parameter is not bound to it on its own —
   * a method that forgot to read it here would silently see the default for every caller.
   */
  @Get("/results/{cacheKey}")
  public CachedResult cachedResult(String cacheKey) {
    var state = componentClient.forKeyValueEntity(cacheKey).method(ResultEntity::get).invoke();
    if (state.isEmpty()) {
      throw HttpException.notFound();
    }
    long maxAge = longFromQuery("maxAge", 0);
    var stored = state.asStoredResult();
    boolean fresh = stored.isFreshEnough(maxAge, instantFromQuery());
    return new CachedResult(cacheKey, fresh, maxAge, stored);
  }

  public record CachedResult(String cacheKey, boolean freshEnough, long maxAgeSeconds, StoredResult result) {}

  @Get("/tracker")
  public ExecutionTrackerEntity.State tracker() {
    return componentClient.forKeyValueEntity(ExecutionTrackerEntity.ID)
        .method(ExecutionTrackerEntity::get).invoke();
  }

  /** R23: the pass that erases a schedule whose `until` has been reached, rather than skipping it. */
  @Post("/schedules/erase-past-until")
  public List<String> erasePastUntil() {
    var now = instantFromQuery();
    var erased = new java.util.ArrayList<String>();
    for (QueriesView.Entry entry : componentClient.forView().method(QueriesView::scheduled).invoke().items()) {
      var schedule = entry.schedule();
      if (schedule == null || schedule.until() == null) {
        continue;
      }
      Instant untilInstant;
      try {
        untilInstant = java.time.LocalDate.parse(schedule.until()).atStartOfDay(java.time.ZoneOffset.UTC).toInstant();
      } catch (RuntimeException e) {
        continue;
      }
      if (!untilInstant.isAfter(now)) {
        componentClient.forEventSourcedEntity(entry.queryId()).method(QueryEntity::eraseSchedule).invoke();
        erased.add(entry.queryId());
      }
    }
    return List.copyOf(erased);
  }

  private RefreshPipeline pipeline() {
    return new RefreshPipeline(componentClient, DataSourceRegistry.all());
  }

  private Instant instantFromQuery() {
    var at = requestContext().queryParams().getString("at");
    return at.isPresent() ? Instant.parse(at.get()) : Instant.now();
  }

  private long longFromQuery(String name, long fallback) {
    var raw = requestContext().queryParams().getString(name);
    return raw.map(Long::parseLong).orElse(fallback);
  }
}

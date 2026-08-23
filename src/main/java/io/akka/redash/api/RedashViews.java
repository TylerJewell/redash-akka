package io.akka.redash.api;

import io.akka.redash.application.AlertEntity;
import io.akka.redash.application.QueryEntity;
import io.akka.redash.domain.Schedule;
import java.time.Instant;
import java.util.List;

/**
 * What the HTTP surface returns, and how an entity's state becomes it.
 *
 * <p>An entity's state is the rebuild's own shape and changes when the rebuild changes; a
 * caller reads these instead, so the two can move separately. The schedule is flattened
 * here for the same reason it is flattened in the view: what a caller sends and what the
 * decision procedure wants are two different shapes, and the conversion belongs at the
 * boundary rather than in either of them.
 */
public final class RedashViews {

  public record QueryResponse(
      String queryId,
      String queryText,
      String queryHash,
      String dataSourceId,
      String cacheKey,
      ScheduleResponse schedule,
      int scheduleFailures,
      String latestResultId,
      Instant latestResultAt,
      boolean archived) {}

  public record ScheduleResponse(
      Long intervalSeconds,
      String intervalRaw,
      String timeOfDay,
      String dayOfWeek,
      String until,
      Boolean disabled) {}

  public record AlertResponse(
      String alertId,
      String name,
      String queryId,
      String column,
      String operator,
      String threshold,
      String selector,
      Integer rearmSeconds,
      boolean muted,
      String state,
      Instant lastTriggeredAt,
      List<String> subscriptions,
      int notificationsSent,
      int notificationFailures) {}

  private RedashViews() {}

  public static QueryResponse toApi(QueryEntity.State state) {
    return new QueryResponse(
        state.queryId(),
        state.queryText(),
        state.queryHash(),
        state.dataSourceId(),
        state.cacheKey(),
        toApi(state.schedule()),
        state.scheduleFailures(),
        state.latestResultId(),
        state.latestResultAt(),
        state.archived());
  }

  public static ScheduleResponse toApi(Schedule schedule) {
    if (schedule == null) {
      return null;
    }
    return new ScheduleResponse(
        schedule.intervalSeconds(),
        schedule.intervalRaw(),
        schedule.timeOfDay(),
        schedule.dayOfWeek(),
        schedule.until(),
        schedule.disabled());
  }

  public static AlertResponse toApi(AlertEntity.State state) {
    return new AlertResponse(
        state.alertId(),
        state.name(),
        state.queryId(),
        state.column(),
        state.operator(),
        state.threshold(),
        state.selector(),
        state.rearmSeconds(),
        state.muted(),
        state.state(),
        state.lastTriggeredAt(),
        state.subscriptions(),
        state.notificationsSent(),
        state.notificationFailures());
  }
}

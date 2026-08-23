package io.akka.redash.application;

import akka.javasdk.annotations.Component;
import akka.javasdk.annotations.Consume;
import akka.javasdk.annotations.Query;
import akka.javasdk.view.TableUpdater;
import akka.javasdk.view.View;
import java.util.List;

/**
 * Which alerts belong to which query, so a completed run can check exactly the alerts of
 * the queries it was fanned out to and no others (SPEC-001 R16).
 */
@Component(id = "alerts")
public class AlertsView extends View {

  public record Entry(
      String alertId,
      String name,
      String queryId,
      String state,
      long lastTriggeredAtMillis,
      long createdAtMillis,
      /** The alert's own last write, set on every event rather than only on a trigger. */
      long updatedAtMillis,
      int muted,
      int subscriptions,
      int notificationsSent,
      int notificationFailures) {}

  public record Entries(List<Entry> items) {}

  @Consume.FromEventSourcedEntity(AlertEntity.class)
  public static class Alerts extends TableUpdater<Entry> {

    public Effect<Entry> onEvent(AlertEntity.Event event) {
      var id = updateContext().eventSubject().orElseThrow();
      var row = rowState();
      return switch (event) {
        case AlertEntity.Created e ->
            effects()
                .updateRow(
                    new Entry(
                        id, orEmpty(e.name()), orEmpty(e.queryId()), "UNKNOWN", 0, eventTime(), eventTime(),
                        e.muted() ? 1 : 0, 0, 0, 0));
        case AlertEntity.Evaluated e ->
            effects()
                .updateRow(
                    new Entry(
                        id, row.name(), row.queryId(), e.state(),
                        e.lastTriggeredAt() == null ? 0 : e.lastTriggeredAt().toEpochMilli(),
                        row.createdAtMillis(), eventTime(), row.muted(), row.subscriptions(),
                        row.notificationsSent() + e.notified(),
                        row.notificationFailures() + e.failed()));
        case AlertEntity.Unevaluated e -> effects().updateRow(row);
        case AlertEntity.Subscribed e ->
            effects()
                .updateRow(
                    new Entry(
                        id, row.name(), row.queryId(), row.state(), row.lastTriggeredAtMillis(),
                        row.createdAtMillis(), eventTime(), row.muted(), row.subscriptions() + 1,
                        row.notificationsSent(), row.notificationFailures()));
        case AlertEntity.MuteChanged e ->
            effects()
                .updateRow(
                    new Entry(
                        id, row.name(), row.queryId(), row.state(), row.lastTriggeredAtMillis(),
                        row.createdAtMillis(), eventTime(), e.muted() ? 1 : 0, row.subscriptions(),
                        row.notificationsSent(), row.notificationFailures()));
      };
    }

    /** A view row field is never null: a view cannot match or project on one. */
    private static String orEmpty(String value) {
      return value == null ? "" : value;
    }

    /** When the event being applied was written, or 0 when the runtime did not say. */
    private long eventTime() {
      return updateContext()
          .metadata()
          .asCloudEvent()
          .time()
          .map(t -> t.toInstant().toEpochMilli())
          .orElse(0L);
    }
  }

  @Query("SELECT * AS items FROM alerts WHERE queryId = :queryId ORDER BY alertId")
  public QueryEffect<Entries> forQuery(String queryId) {
    return queryResult();
  }

  @Query("SELECT * AS items FROM alerts ORDER BY alertId")
  public QueryEffect<Entries> all() {
    return queryResult();
  }
}

package io.akka.redash.application;

import akka.javasdk.annotations.Component;
import akka.javasdk.annotations.Consume;
import akka.javasdk.annotations.Query;
import akka.javasdk.view.TableUpdater;
import akka.javasdk.view.View;
import io.akka.redash.domain.Schedule;
import java.util.List;

/**
 * The saved queries a sweep walks, and the queries one result belongs to.
 *
 * <p>Two questions, one table. The sweep needs every query carrying a schedule; the fan-out
 * needs every non-archived query sharing a cache key, which is what makes one run answer
 * several saved queries at once (SPEC-001 R13, R16).
 *
 * <p>The schedule is spread across plain columns rather than kept as a record: a view row
 * with a null column is not queryable, and the sweep filters on whether a schedule is there
 * at all before it looks at what the schedule says.
 */
@Component(id = "queries")
public class QueriesView extends View {

  /**
   * @param scheduled 1 when the query carries a schedule, 0 otherwise
   * @param archived 1 once the query has been archived — an archived query is still swept
   *     out of the fan-out here rather than in the caller, because the caller would have to
   *     read every row to do it
   * @param latestResultAtMillis 0 when the query has never produced a result, which is what
   *     makes it due immediately
   */
  public record Entry(
      String queryId,
      String queryText,
      String queryHash,
      String dataSourceId,
      String cacheKey,
      int scheduled,
      int archived,
      int scheduleFailures,
      String latestResultId,
      long latestResultAtMillis,
      long intervalSeconds,
      String intervalRaw,
      String timeOfDay,
      String dayOfWeek,
      String until,
      int scheduleDisabled,
      int scheduleEveryValueAbsent) {

    /** The schedule as the decision procedure wants it, or null when there is none. */
    public Schedule schedule() {
      if (scheduled == 0) {
        return null;
      }
      return new Schedule(
          intervalSeconds < 0 ? null : intervalSeconds,
          intervalRaw.isEmpty() ? null : intervalRaw,
          timeOfDay.isEmpty() ? null : timeOfDay,
          dayOfWeek.isEmpty() ? null : dayOfWeek,
          until.isEmpty() ? null : until,
          scheduleEveryValueAbsent == 1 ? null : scheduleDisabled == 1);
    }
  }

  public record Entries(List<Entry> items) {}

  @Consume.FromEventSourcedEntity(QueryEntity.class)
  public static class Queries extends TableUpdater<Entry> {

    public Effect<Entry> onEvent(QueryEntity.Event event) {
      var id = updateContext().eventSubject().orElseThrow();
      var row = rowState();
      return switch (event) {
        case QueryEntity.Saved e -> {
          // A view row field is never null: a query saved without a data source would put
          // one in the column the fan-out queries on, and a view cannot match on null.
          var dataSourceId = orEmpty(e.dataSourceId());
          yield effects()
              .updateRow(
                  withSchedule(
                      new Entry(
                          id, orEmpty(e.queryText()), orEmpty(e.queryHash()), dataSourceId,
                          orEmpty(e.queryHash()) + ":" + dataSourceId,
                          0, 0, 0, "", 0, -1, "", "", "", "", 0, 0),
                      e.schedule()));
        }
        case QueryEntity.ScheduleSet e -> effects().updateRow(withSchedule(row, e.schedule()));
        case QueryEntity.ScheduleDisabledByError e ->
            effects()
                .updateRow(
                    withSchedule(
                        row, row.schedule() == null ? null : row.schedule().withDisabled(true)));
        case QueryEntity.ScheduleErased e -> effects().updateRow(withSchedule(row, null));
        case QueryEntity.RefreshFailed e -> effects().updateRow(withFailures(row, row.scheduleFailures() + 1));
        case QueryEntity.RefreshSucceeded e ->
            effects()
                .updateRow(
                    new Entry(
                        row.queryId(), row.queryText(), row.queryHash(), row.dataSourceId(), row.cacheKey(),
                        row.scheduled(), row.archived(), 0, orEmpty(e.resultId()), e.retrievedAt().toEpochMilli(),
                        row.intervalSeconds(), row.intervalRaw(), row.timeOfDay(), row.dayOfWeek(),
                        row.until(), row.scheduleDisabled(), row.scheduleEveryValueAbsent()));
        case QueryEntity.Archived e ->
            effects()
                .updateRow(
                    new Entry(
                        row.queryId(), row.queryText(), row.queryHash(), row.dataSourceId(), row.cacheKey(),
                        row.scheduled(), 1, row.scheduleFailures(), row.latestResultId(),
                        row.latestResultAtMillis(), row.intervalSeconds(), row.intervalRaw(),
                        row.timeOfDay(), row.dayOfWeek(), row.until(), row.scheduleDisabled(),
                        row.scheduleEveryValueAbsent()));
      };
    }

    private static Entry withFailures(Entry row, int failures) {
      return new Entry(
          row.queryId(), row.queryText(), row.queryHash(), row.dataSourceId(), row.cacheKey(),
          row.scheduled(), row.archived(), failures, row.latestResultId(), row.latestResultAtMillis(),
          row.intervalSeconds(), row.intervalRaw(), row.timeOfDay(), row.dayOfWeek(), row.until(),
          row.scheduleDisabled(), row.scheduleEveryValueAbsent());
    }

    private static Entry withSchedule(Entry row, Schedule schedule) {
      if (schedule == null) {
        return new Entry(
            row.queryId(), row.queryText(), row.queryHash(), row.dataSourceId(), row.cacheKey(),
            0, row.archived(), row.scheduleFailures(), row.latestResultId(), row.latestResultAtMillis(),
            -1, "", "", "", "", 0, 0);
      }
      return new Entry(
          row.queryId(), row.queryText(), row.queryHash(), row.dataSourceId(), row.cacheKey(),
          1, row.archived(), row.scheduleFailures(), row.latestResultId(), row.latestResultAtMillis(),
          schedule.intervalSeconds() == null ? -1 : schedule.intervalSeconds(),
          orEmpty(schedule.intervalRaw()),
          orEmpty(schedule.timeOfDay()),
          orEmpty(schedule.dayOfWeek()),
          orEmpty(schedule.until()),
          Boolean.TRUE.equals(schedule.disabled()) ? 1 : 0,
          schedule.everyValueAbsent() ? 1 : 0);
    }

    private static String orEmpty(String value) {
      return value == null ? "" : value;
    }
  }

  @Query("SELECT * AS items FROM queries WHERE scheduled = 1 ORDER BY queryId")
  public QueryEffect<Entries> scheduled() {
    return queryResult();
  }

  @Query("SELECT * AS items FROM queries WHERE cacheKey = :cacheKey AND archived = 0 ORDER BY queryId")
  public QueryEffect<Entries> sharingCacheKey(String cacheKey) {
    return queryResult();
  }

  @Query("SELECT * AS items FROM queries ORDER BY queryId")
  public QueryEffect<Entries> all() {
    return queryResult();
  }
}

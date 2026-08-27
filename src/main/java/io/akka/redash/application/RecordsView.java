package io.akka.redash.application;

import akka.javasdk.annotations.Component;
import akka.javasdk.annotations.Consume;
import akka.javasdk.annotations.Query;
import akka.javasdk.view.TableUpdater;
import akka.javasdk.view.View;
import io.akka.redash.domain.Json;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Every stored row, indexed by the handful of columns anything actually filters on.
 *
 * <p>The row carries the whole document as a string alongside those columns. That is a
 * decision worth stating: redash's read side is a relational database, and its list
 * endpoints join across three tables, rank a full-text vector, order by a related table's
 * column and count the whole set for a pager. None of that is expressible in a view query,
 * so the view narrows by the columns it *can* index — the table, the organisation, the
 * owner, the parent — and the endpoint does the joining, ranking, ordering and paging over
 * what comes back. The answers are the source's; the cost is that a list endpoint reads
 * more rows than a SQL query would, which is recorded in the README as a difference in
 * scaling rather than in behaviour.
 *
 * <p>Every column is non-null, with an empty string or a zero where a document has nothing:
 * a view row with a null in it stops the whole view's update stream, and a stopped stream
 * makes every query against it answer nothing at all rather than failing.
 */
@Component(id = "records")
public class RecordsView extends View {

  /**
   * @param recordKey the entity's own namespaced identity, {@code <table>:<id>}
   * @param tableName which of redash's tables this row belongs to
   * @param recordId the identity within that table, or 0 where the identity is composite
   * @param orgId the organisation, or 0 for a row that is not scoped to one
   * @param ownerId whoever the row's {@code user_id} names, or 0
   * @param parentId what the row hangs off — a widget's dashboard, a visualisation's query,
   *     an alert's query, a subscription's alert
   * @param groupKey a second grouping column: a result's cache key, a favourite's object,
   *     an api key's object, a permission's object
   * @param name the row's name, lower-cased, so ordering by name needs no second read
   * @param createdAt millisecond stamp, 0 when the row carries none
   * @param updatedAt millisecond stamp, 0 when the row carries none
   * @param archived whether the row is archived, as 1 or 0
   * @param draft whether the row is a draft, as 1 or 0
   * @param deleted whether the row is gone; a deleted row stays in the view so a query for
   *     it answers "not found" rather than "not yet"
   * @param document the whole row, as it would be written to a caller
   */
  public record Row(
      String recordKey,
      String tableName,
      long recordId,
      long orgId,
      long ownerId,
      long parentId,
      String groupKey,
      String name,
      long createdAt,
      long updatedAt,
      int archived,
      int draft,
      int deleted,
      String document) {}

  public record Rows(List<Row> items) {}

  public record ByOrg(String tableName, long orgId) {}

  public record ByParent(String tableName, long parentId) {}

  public record ByGroupKey(String tableName, String groupKey) {}

  public record ByOwner(String tableName, long ownerId) {}

  @Consume.FromEventSourcedEntity(RecordEntity.class)
  public static class Records extends TableUpdater<Row> {

    public Effect<Row> onEvent(Doc.Event event) {
      var key = updateContext().eventSubject().orElseThrow();
      var previous = rowState();
      return switch (event) {
        case Doc.Created e -> effects().updateRow(row(key, Doc.normalise(e.fields()), false));
        case Doc.Updated e -> {
          var merged = new LinkedHashMap<String, Object>(
              previous == null ? Map.of() : Json.asMap(Json.loads(previous.document())));
          merged.putAll(Doc.normalise(e.fields()));
          yield effects().updateRow(row(key, merged, previous != null && previous.deleted() == 1));
        }
        case Doc.Deleted ignored -> {
          if (previous == null) {
            yield effects().ignore();
          }
          yield effects().updateRow(new Row(previous.recordKey(), previous.tableName(),
              previous.recordId(), previous.orgId(), previous.ownerId(), previous.parentId(),
              previous.groupKey(), previous.name(), previous.createdAt(), previous.updatedAt(),
              previous.archived(), previous.draft(), 1, previous.document()));
        }
      };
    }

    private static Row row(String key, Map<String, Object> fields, boolean deleted) {
      int separator = key.indexOf(':');
      var table = key.substring(0, separator);
      var identity = key.substring(separator + 1);
      return new Row(
          key,
          table,
          number(identity),
          longOf(fields, "org_id"),
          longOf(fields, "user_id"),
          longOf(fields, "parent_id"),
          textOf(fields, "group_key"),
          textOf(fields, "name").toLowerCase(Locale.ROOT),
          millis(fields, "created_at"),
          millis(fields, "updated_at"),
          Boolean.TRUE.equals(fields.get("is_archived")) ? 1 : 0,
          Boolean.TRUE.equals(fields.get("is_draft")) ? 1 : 0,
          deleted ? 1 : 0,
          Json.dumps(fields));
    }

    private static long number(String identity) {
      try {
        return Long.parseLong(identity);
      } catch (NumberFormatException e) {
        // A favourite, a lock or a cached schema is keyed by a composite rather than by a
        // number; those are addressed by key and never ordered by identity.
        return 0;
      }
    }

    private static long longOf(Map<String, Object> fields, String name) {
      return fields.get(name) instanceof Number n ? n.longValue() : 0;
    }

    private static String textOf(Map<String, Object> fields, String name) {
      var value = fields.get(name);
      return value == null ? "" : String.valueOf(value);
    }

    private static long millis(Map<String, Object> fields, String name) {
      var value = fields.get(name);
      if (value instanceof Number n) {
        return n.longValue();
      }
      if (value instanceof CharSequence text) {
        try {
          return java.time.Instant.parse(text.toString()).toEpochMilli();
        } catch (RuntimeException e) {
          return 0;
        }
      }
      return 0;
    }
  }

  @Query("SELECT * AS items FROM records"
      + " WHERE tableName = :tableName AND deleted = 0 ORDER BY recordId")
  public QueryEffect<Rows> byTable(String tableName) {
    return queryResult();
  }

  @Query("SELECT * AS items FROM records"
      + " WHERE tableName = :tableName AND orgId = :orgId AND deleted = 0 ORDER BY recordId")
  public QueryEffect<Rows> byOrg(ByOrg params) {
    return queryResult();
  }

  @Query("SELECT * AS items FROM records"
      + " WHERE tableName = :tableName AND parentId = :parentId AND deleted = 0 ORDER BY recordId")
  public QueryEffect<Rows> byParent(ByParent params) {
    return queryResult();
  }

  @Query("SELECT * AS items FROM records"
      + " WHERE tableName = :tableName AND groupKey = :groupKey AND deleted = 0 ORDER BY recordId")
  public QueryEffect<Rows> byGroupKey(ByGroupKey params) {
    return queryResult();
  }

  @Query("SELECT * AS items FROM records"
      + " WHERE tableName = :tableName AND ownerId = :ownerId AND deleted = 0 ORDER BY recordId")
  public QueryEffect<Rows> byOwner(ByOwner params) {
    return queryResult();
  }
}

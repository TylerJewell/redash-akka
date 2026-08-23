package io.akka.redash.application;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Runs a query against a data source and returns rows and columns, or the message that
 * came back instead.
 *
 * <p>Only PostgreSQL. The original ships more than forty runners; a runner is a driver for
 * somebody else's database and not part of deciding when to run a query, so this port has
 * the one its benchmark and the original's own test factory both default to
 * (SPEC-001 §1).
 */
public final class QueryRunner {

  /**
   * @param error null when the run succeeded; the failure's own message otherwise. Rows and
   *     an error are not both present: the caller distinguishes a failed refresh from an
   *     empty one, and the two lead to different counters.
   */
  public record Result(List<Map<String, Object>> rows, List<String> columns, String error) {

    public boolean failed() {
      return error != null;
    }
  }

  /** Where a query runs. Held as plain strings so it can travel in a command. */
  public record DataSource(String dataSourceId, String jdbcUrl, String user, String password, boolean paused) {}

  private QueryRunner() {}

  public static Result run(DataSource dataSource, String sql) {
    try (Connection connection =
            DriverManager.getConnection(dataSource.jdbcUrl(), dataSource.user(), dataSource.password());
        Statement statement = connection.createStatement();
        ResultSet resultSet = statement.executeQuery(sql)) {

      var meta = resultSet.getMetaData();
      var columns = new ArrayList<String>(meta.getColumnCount());
      for (int i = 1; i <= meta.getColumnCount(); i++) {
        columns.add(meta.getColumnLabel(i));
      }

      var rows = new ArrayList<Map<String, Object>>();
      while (resultSet.next()) {
        var row = new LinkedHashMap<String, Object>();
        for (int i = 1; i <= meta.getColumnCount(); i++) {
          row.put(columns.get(i - 1), normalise(resultSet.getObject(i)));
        }
        rows.add(row);
      }
      return new Result(List.copyOf(rows), List.copyOf(columns), null);
    } catch (Exception e) {
      return new Result(null, null, e.getMessage());
    }
  }

  /**
   * JDBC hands back driver-specific numeric and temporal types. An alert compares against a
   * configured threshold, so what reaches it has to be the same handful of shapes whichever
   * column type produced it.
   */
  private static Object normalise(Object value) {
    if (value == null || value instanceof String || value instanceof Boolean) {
      return value;
    }
    if (value instanceof java.math.BigDecimal d) {
      return d.doubleValue();
    }
    if (value instanceof Number n) {
      return n;
    }
    return value.toString();
  }
}

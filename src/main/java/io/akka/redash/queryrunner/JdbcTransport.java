package io.akka.redash.queryrunner;

import io.akka.redash.domain.Numbers;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.function.Function;

/**
 * Every data source type whose protocol is JDBC, driven through one engine.
 *
 * <p>What differs between them is the URL, the driver class, and the query that lists the
 * columns of every table — and all three are data, so they are a table here rather than
 * seventy-five classes. What does not differ is the shape of the answer: names, types
 * mapped from JDBC's own type codes onto redash's six, and a row per record keyed by
 * column name.
 *
 * <p>A driver that is not on the classpath is reported as such rather than hidden. That is
 * a deliberate difference from the original, which drops the whole type from its registry
 * when its Python driver will not import; see SPEC-001 D-4.
 */
public final class JdbcTransport implements Transport {

  /**
   * How a dialect reports its schema: the query, which schema's prefix is dropped from a
   * table's name, and what a column looks like.
   *
   * <p>The three move together, so they are one record. redash's PostgreSQL runner names a
   * column {@code {name, type}} and drops the `public.` prefix unless the bare name
   * collides with a qualified one; its MySQL runner adds a description and drops the
   * connected database's prefix unconditionally; its SQL Server and SQLite runners answer
   * bare column names. A schema is compared against the original's whole, so the shape is
   * part of the answer rather than a detail.
   *
   * @param defaultSchema the schema whose prefix is left off a table's name, given the
   *     data source's options; null when the dialect always qualifies
   * @param guardAgainstCollisions whether a bare name is qualified anyway when some other
   *     schema already holds a table of that qualified name
   */
  private record Shape(String query, Function<Map<String, Object>, String> defaultSchema,
      Column column, boolean guardAgainstCollisions) {}

  private enum Column { NAME_ONLY, NAME_AND_TYPE, NAME_TYPE_AND_DESCRIPTION }

  /**
   * The source's own PostgreSQL query, materialised views and all. They are not in
   * `information_schema.columns`, so they arrive through the union with a null type — and a
   * null type is what makes their columns bare names where every other column is a pair.
   */
  private static final Shape POSTGRES_SCHEMA = new Shape(
      "SELECT s.nspname AS table_schema, c.relname AS table_name, a.attname AS column_name,"
          + " NULL AS data_type"
          + " FROM pg_class c JOIN pg_namespace s ON c.relnamespace = s.oid"
          + " AND s.nspname NOT IN ('pg_catalog', 'information_schema')"
          + " JOIN pg_attribute a ON a.attrelid = c.oid AND a.attnum > 0"
          + " AND NOT a.attisdropped WHERE c.relkind = 'm'"
          + " AND has_table_privilege(quote_ident(s.nspname) || '.' || quote_ident(c.relname),"
          + " 'select') AND has_schema_privilege(s.nspname, 'usage')"
          + " UNION"
          + " SELECT table_schema, table_name, column_name, data_type"
          + " FROM information_schema.columns"
          + " WHERE table_schema NOT IN ('pg_catalog', 'information_schema')",
      options -> "public", Column.NAME_AND_TYPE, true);

  private static final Shape MYSQL_SCHEMA = new Shape(
      "SELECT col.table_schema AS table_schema, col.table_name AS table_name,"
          + " col.column_name AS column_name, col.data_type AS data_type,"
          + " col.column_comment AS column_comment FROM information_schema.columns col"
          + " WHERE LOWER(col.table_schema) NOT IN"
          + " ('information_schema', 'performance_schema', 'mysql', 'sys')",
      options -> string(options, "db", null),
      Column.NAME_TYPE_AND_DESCRIPTION, false);

  private static final Shape ANSI_SCHEMA = new Shape(
      "SELECT table_schema, table_name, column_name FROM information_schema.columns"
          + " WHERE table_schema NOT IN ('pg_catalog', 'information_schema',"
          + " 'performance_schema', 'sys', 'mysql')",
      options -> null, Column.NAME_ONLY, false);

  private static final Shape SQLITE_SCHEMA = new Shape(
      "SELECT '' AS table_schema, m.name AS table_name, p.name AS column_name"
          + " FROM sqlite_master m JOIN pragma_table_info(m.name) p"
          + " WHERE m.type = 'table'",
      options -> null, Column.NAME_ONLY, false);

  private final String driverClass;
  private final Function<Map<String, Object>, String> url;
  private final Function<Map<String, Object>, Properties> properties;
  private final Shape shape;

  private JdbcTransport(String driverClass, Function<Map<String, Object>, String> url,
      Function<Map<String, Object>, Properties> properties, Shape shape) {
    this.driverClass = driverClass;
    this.url = url;
    this.properties = properties;
    this.shape = shape;
  }

  /** The type table. A type absent from it is not a JDBC type as far as this port goes. */
  static JdbcTransport forType(String type) {
    return switch (type) {
      case "pg", "redshift", "redshift_iam", "cockroach", "risingwave" ->
          new JdbcTransport("org.postgresql.Driver", JdbcTransport::postgresUrl,
              JdbcTransport::userAndPassword, POSTGRES_SCHEMA);
      case "mysql", "rds_mysql", "memsql" ->
          new JdbcTransport("com.mysql.cj.jdbc.Driver", JdbcTransport::mysqlUrl,
              JdbcTransport::userAndPassword, MYSQL_SCHEMA);
      case "mssql", "mssql_odbc" ->
          new JdbcTransport("com.microsoft.sqlserver.jdbc.SQLServerDriver",
              JdbcTransport::sqlServerUrl, JdbcTransport::userAndPassword, ANSI_SCHEMA);
      case "sqlite" ->
          new JdbcTransport("org.sqlite.JDBC", JdbcTransport::sqliteUrl,
              options -> new Properties(), SQLITE_SCHEMA);
      // The rest name a driver that is not shipped here. Each still builds its URL, so a
      // deployment that adds the jar gets a working data source with no code change.
      case "clickhouse", "tinybird" ->
          new JdbcTransport("com.clickhouse.jdbc.ClickHouseDriver", JdbcTransport::clickhouseUrl,
              JdbcTransport::userAndPassword, ANSI_SCHEMA);
      case "trino" ->
          new JdbcTransport("io.trino.jdbc.TrinoDriver", JdbcTransport::trinoUrl,
              JdbcTransport::userAndPassword, ANSI_SCHEMA);
      case "presto" ->
          new JdbcTransport("com.facebook.presto.jdbc.PrestoDriver", JdbcTransport::prestoUrl,
              JdbcTransport::userAndPassword, ANSI_SCHEMA);
      case "drill" ->
          new JdbcTransport("org.apache.drill.jdbc.Driver", JdbcTransport::drillUrl,
              JdbcTransport::userAndPassword, ANSI_SCHEMA);
      case "databend" ->
          new JdbcTransport("com.databend.jdbc.DatabendDriver", JdbcTransport::databendUrl,
              JdbcTransport::userAndPassword, ANSI_SCHEMA);
      case "duckdb" ->
          new JdbcTransport("org.duckdb.DuckDBDriver",
              options -> "jdbc:duckdb:" + string(options, "dbpath", ""),
              options -> new Properties(), ANSI_SCHEMA);
      default -> null;
    };
  }

  @Override
  public RunResult run(RunnerType type, String query, Map<String, Object> options) {
    try {
      Class.forName(driverClass);
    } catch (ClassNotFoundException e) {
      return RunResult.failed(
          "No suitable driver: " + driverClass + " is not on this deployment's classpath.");
    }
    try (Connection connection = DriverManager.getConnection(url.apply(options),
        properties.apply(options))) {
      return execute(connection, query);
    } catch (SQLException e) {
      return RunResult.failed(e.getMessage());
    } catch (RuntimeException e) {
      return RunResult.failed(String.valueOf(e.getMessage()));
    }
  }

  @Override
  public RunResult schema(RunnerType type, Map<String, Object> options) {
    var result = run(type, shape.query(), options);
    if (result.isFailure()) {
      return result;
    }
    var defaultSchema = shape.defaultSchema().apply(options);

    // A table is named by itself unless it lives outside the default schema. redash
    // qualifies a default-schema table anyway when some other schema already holds a table
    // whose qualified name is that same string — one schema called `main` with a `users`
    // table, and a table actually called `main.users`, would otherwise be one entry.
    var qualified = new java.util.HashSet<String>();
    for (Map<String, Object> row : result.rows()) {
      qualified.add(qualify(String.valueOf(row.getOrDefault("table_schema", "")),
          String.valueOf(row.getOrDefault("table_name", ""))));
    }

    var tables = new LinkedHashMap<String, List<Object>>();
    for (Map<String, Object> row : result.rows()) {
      var schema = String.valueOf(row.getOrDefault("table_schema", ""));
      var table = String.valueOf(row.getOrDefault("table_name", ""));
      var name = qualify(schema, table);
      if (defaultSchema != null && defaultSchema.equals(schema)
          && !(shape.guardAgainstCollisions() && qualified.contains(table))) {
        name = table;
      }
      tables.computeIfAbsent(name, ignored -> new ArrayList<>()).add(column(row));
    }
    var out = new ArrayList<Map<String, Object>>(tables.size());
    for (var entry : tables.entrySet()) {
      out.add(io.akka.redash.domain.Json.map("name", entry.getKey(),
          "columns", entry.getValue()));
    }
    return RunResult.of(List.of(), out);
  }

  private static String qualify(String schema, String table) {
    return schema == null || schema.isEmpty() ? table : schema + "." + table;
  }

  /**
   * One column, in the shape its dialect reports. A row whose type came back null is a bare
   * name even where the dialect usually pairs the two — which is how a materialised view's
   * columns arrive from PostgreSQL.
   */
  private Object column(Map<String, Object> row) {
    var name = String.valueOf(row.getOrDefault("column_name", ""));
    var type = row.get("data_type");
    if (shape.column() == Column.NAME_ONLY || type == null) {
      return name;
    }
    if (shape.column() == Column.NAME_AND_TYPE) {
      return io.akka.redash.domain.Json.map("name", name, "type", type);
    }
    return io.akka.redash.domain.Json.map("name", name, "type", type,
        "description", row.get("column_comment"));
  }

  /**
   * Run the statement and shape whatever came back.
   *
   * <p>A statement that returns no result set — an insert, a create — answers an empty
   * table rather than an error, which is what the original does and what makes a script
   * ending in a write succeed.
   */
  static RunResult execute(Connection connection, String query) throws SQLException {
    try (Statement statement = connection.createStatement()) {
      boolean hasResultSet = statement.execute(query);
      if (!hasResultSet) {
        return RunResult.of(List.of(), List.of());
      }
      try (ResultSet resultSet = statement.getResultSet()) {
        var metadata = resultSet.getMetaData();
        int count = metadata.getColumnCount();
        var names = new ArrayList<String>(count);
        var types = new ArrayList<String>(count);
        for (int i = 1; i <= count; i++) {
          names.add(metadata.getColumnLabel(i));
          types.add(redashType(metadata.getColumnType(i)));
        }
        var columns = RunResult.fetchColumns(names, types);
        var rows = new ArrayList<Map<String, Object>>();
        while (resultSet.next()) {
          var row = new LinkedHashMap<String, Object>();
          for (int i = 1; i <= count; i++) {
            row.put(String.valueOf(columns.get(i - 1).get("name")), value(resultSet, i));
          }
          rows.add(row);
        }
        return RunResult.of(columns, rows);
      }
    }
  }

  private static Object value(ResultSet resultSet, int index) throws SQLException {
    Object value = resultSet.getObject(index);
    return switch (value) {
      case null -> null;
      case java.sql.Timestamp timestamp -> timestamp.toInstant();
      case java.sql.Date date -> date.toLocalDate();
      case java.sql.Time time -> time.toLocalTime();
      case BigDecimal decimal -> decimal;
      case byte[] bytes -> bytes;
      case java.sql.Array array -> String.valueOf(array);
      default -> value;
    };
  }

  /** JDBC's type codes onto the six redash names, everything unrecognised being a string. */
  static String redashType(int sqlType) {
    return switch (sqlType) {
      case Types.BIT, Types.BOOLEAN -> Numbers.TYPE_BOOLEAN;
      case Types.TINYINT, Types.SMALLINT, Types.INTEGER, Types.BIGINT -> Numbers.TYPE_INTEGER;
      case Types.FLOAT, Types.REAL, Types.DOUBLE, Types.NUMERIC, Types.DECIMAL ->
          Numbers.TYPE_FLOAT;
      case Types.DATE -> Numbers.TYPE_DATE;
      case Types.TIMESTAMP, Types.TIMESTAMP_WITH_TIMEZONE -> Numbers.TYPE_DATETIME;
      default -> Numbers.TYPE_STRING;
    };
  }

  // ------------------------------------------------------------------ URLs

  static String string(Map<String, Object> options, String key, String fallback) {
    var value = options.get(key);
    return value == null || String.valueOf(value).isEmpty() ? fallback : String.valueOf(value);
  }

  static int number(Map<String, Object> options, String key, int fallback) {
    var value = options.get(key);
    if (value instanceof Number n) {
      return n.intValue();
    }
    if (value instanceof CharSequence text && Numbers.isPythonInteger(text.toString())) {
      return Integer.parseInt(text.toString().strip());
    }
    return fallback;
  }

  static Properties userAndPassword(Map<String, Object> options) {
    var properties = new Properties();
    var user = string(options, "user", null);
    if (user != null) {
      properties.setProperty("user", user);
    }
    var password = string(options, "password", null);
    if (password != null) {
      properties.setProperty("password", password);
    }
    return properties;
  }

  static String postgresUrl(Map<String, Object> options) {
    var out = new StringBuilder("jdbc:postgresql://")
        .append(string(options, "host", "127.0.0.1"))
        .append(':')
        .append(number(options, "port", 5432))
        .append('/')
        .append(string(options, "dbname", "postgres"));
    var sslmode = string(options, "sslmode", null);
    if (sslmode != null) {
      out.append("?sslmode=").append(sslmode);
    }
    return out.toString();
  }

  static String mysqlUrl(Map<String, Object> options) {
    return "jdbc:mysql://" + string(options, "host", "127.0.0.1") + ":"
        + number(options, "port", 3306) + "/" + string(options, "db", "")
        + "?useSSL=" + (options.containsKey("ssl_mode") ? "true" : "false");
  }

  static String sqlServerUrl(Map<String, Object> options) {
    return "jdbc:sqlserver://" + string(options, "host", "127.0.0.1") + ":"
        + number(options, "port", 1433) + ";databaseName=" + string(options, "db", "")
        + ";encrypt=false;trustServerCertificate=true";
  }

  static String sqliteUrl(Map<String, Object> options) {
    return "jdbc:sqlite:" + string(options, "dbpath", "");
  }

  static String clickhouseUrl(Map<String, Object> options) {
    return "jdbc:clickhouse:" + string(options, "url", "http://127.0.0.1:8123")
        + "/" + string(options, "dbname", "default");
  }

  static String trinoUrl(Map<String, Object> options) {
    return "jdbc:trino://" + string(options, "host", "127.0.0.1") + ":"
        + number(options, "port", 8080) + "/" + string(options, "catalog", "hive")
        + "/" + string(options, "schema", "default");
  }

  static String prestoUrl(Map<String, Object> options) {
    return "jdbc:presto://" + string(options, "host", "127.0.0.1") + ":"
        + number(options, "port", 8080) + "/" + string(options, "catalog", "hive")
        + "/" + string(options, "schema", "default");
  }

  static String drillUrl(Map<String, Object> options) {
    return "jdbc:drill:drillbit=" + string(options, "url", "127.0.0.1:31010");
  }

  static String databendUrl(Map<String, Object> options) {
    return "jdbc:databend://" + string(options, "host", "127.0.0.1") + ":"
        + number(options, "port", 8000) + "/" + string(options, "database", "default");
  }
}

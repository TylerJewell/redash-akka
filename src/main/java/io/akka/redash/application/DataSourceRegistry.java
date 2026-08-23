package io.akka.redash.application;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Where the queries in this service run.
 *
 * <p>redash keeps data sources in its own database with encrypted options; this port reads
 * them from configuration, because how a connection string is stored is not part of
 * deciding when to run a query. A data source can be paused, which is one of the reasons a
 * sweep passes a query over (SPEC-001 R11).
 *
 * <p>Configured with {@code REDASH_DATASOURCES} as
 * {@code id=jdbc:postgresql://host/db|user|password[|paused]}, several separated by
 * semicolons. With none set there is one default pointing at the port's own probe database,
 * which is the one its benchmark uses.
 */
public final class DataSourceRegistry {

  private static final Map<String, QueryRunner.DataSource> SOURCES = load();

  private DataSourceRegistry() {}

  public static Map<String, QueryRunner.DataSource> all() {
    return SOURCES;
  }

  private static Map<String, QueryRunner.DataSource> load() {
    var configured = System.getenv("REDASH_DATASOURCES");
    var out = new LinkedHashMap<String, QueryRunner.DataSource>();
    if (configured == null || configured.isBlank()) {
      out.put(
          "ds-1",
          new QueryRunner.DataSource(
              "ds-1", "jdbc:postgresql://localhost:55432/redash_test", "postgres", "postgres", false));
      return Map.copyOf(out);
    }
    for (String entry : configured.split(";")) {
      if (entry.isBlank()) {
        continue;
      }
      var eq = entry.indexOf('=');
      var id = entry.substring(0, eq).trim();
      var parts = entry.substring(eq + 1).split("\\|", -1);
      out.put(
          id,
          new QueryRunner.DataSource(
              id,
              parts[0],
              parts.length > 1 ? parts[1] : "",
              parts.length > 2 ? parts[2] : "",
              parts.length > 3 && Boolean.parseBoolean(parts[3])));
    }
    return Map.copyOf(out);
  }
}

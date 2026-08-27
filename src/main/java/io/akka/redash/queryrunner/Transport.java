package io.akka.redash.queryrunner;

import java.util.Map;

/**
 * How a query reaches the system a data source names.
 *
 * <p>Every one of the seventy-five types is registered, validated and formed whatever this
 * machine happens to carry (SPEC-001 D-4); this is the one part of a type that depends on
 * what is installed. Three kinds exist:
 *
 * <ul>
 *   <li>{@link JdbcTransport} — a JDBC URL built from the type's own configuration
 *       properties. It answers the driver's own error when the driver is not on the
 *       classpath, which is the same shape of answer the original gives when its Python
 *       driver is missing.
 *   <li>{@link HttpTransport} — a request built and a document read back, for the types
 *       whose protocol is HTTP and needs no vendor library.
 *   <li>{@link LocalTransport} — the three types that read something local rather than
 *       talking to anybody: an uploaded delimited file, an uploaded spreadsheet, and the
 *       results of other queries.
 * </ul>
 *
 * <p>A type whose protocol needs a vendor library that is not on the classpath answers a
 * message naming what it would need. It is not absent from the registry, because the list
 * of types is a wire format and a shorter list is a different answer to a question the
 * front end asks on every page load.
 */
public interface Transport {

  RunResult run(RunnerType type, String query, Map<String, Object> options);

  /** The tables and columns behind a data source, for the schema browser. */
  default RunResult schema(RunnerType type, Map<String, Object> options) {
    return RunResult.failed(NOT_SUPPORTED);
  }

  String NOT_SUPPORTED = "Data source type does not support retrieving schema";

  /** A transport that cannot be reached from here, and says exactly what it would need. */
  record Absent(String needs) implements Transport {
    @Override
    public RunResult run(RunnerType type, String query, Map<String, Object> options) {
      return RunResult.failed(
          "The " + type.name() + " data source needs " + needs
              + ", which is not available on this deployment's classpath.");
    }
  }

  static Transport of(String type) {
    var jdbc = JdbcTransport.forType(type);
    if (jdbc != null) {
      return jdbc;
    }
    var http = HttpTransport.forType(type);
    if (http != null) {
      return http;
    }
    var local = LocalTransport.forType(type);
    if (local != null) {
      return local;
    }
    return new Absent(vendorLibraryFor(type));
  }

  /**
   * What a type would need to be reachable. Named per type rather than with one blanket
   * sentence, because "not available" without saying what is missing is what sends somebody
   * reading source code.
   */
  private static String vendorLibraryFor(String type) {
    return switch (type) {
      case "bigquery" -> "the Google BigQuery client library and a service account";
      case "google_analytics", "google_analytics4", "google_search_console",
          "google_spreadsheets" -> "the Google API client library and a service account";
      case "athena" -> "the Athena JDBC driver or the AWS SDK";
      case "cloudwatch", "cloudwatch_insights" -> "the AWS SDK";
      case "aws_es" -> "the AWS SDK, for request signing";
      case "azure_kusto" -> "the Azure Data Explorer client library";
      case "Cassandra", "scylla" -> "the DataStax Java driver";
      case "mongodb" -> "the MongoDB Java driver";
      case "snowflake" -> "the Snowflake JDBC driver";
      case "databricks" -> "the Databricks JDBC driver";
      case "oracle" -> "the Oracle JDBC driver";
      case "db2" -> "the IBM Db2 JDBC driver";
      case "exasol" -> "the Exasol JDBC driver";
      case "nz" -> "the Netezza JDBC driver";
      case "duckdb" -> "the DuckDB JDBC driver";
      case "hive", "hive_http", "impala" -> "the Hive or Impala JDBC driver";
      case "phoenix" -> "the Apache Phoenix JDBC driver";
      case "ignite" -> "the Apache Ignite JDBC driver";
      case "salesforce" -> "the Salesforce client library";
      case "treasuredata" -> "the Treasure Data client library";
      case "yandex_metrika", "yandex_appmetrika", "yandex_disk" ->
          "a Yandex OAuth token and the Yandex API client";
      case "corporate_memory", "sparql_endpoint" -> "an RDF client library";
      case "kylin" -> "the Apache Kylin client";
      case "e6data" -> "the e6data client library";
      case "couchbase" -> "the Couchbase client library";
      case "rockset" -> "the Rockset client library";
      case "excel" -> "a spreadsheet reader";
      default -> "its vendor client library";
    };
  }
}

package io.akka.redash.queryrunner;

import io.akka.redash.domain.Json;
import io.akka.redash.domain.Sql;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Every data source type redash registers, with the configuration schema each one declares
 * (SPEC-001 R39, R40).
 *
 * <p>Seventy-five of them, which is what a default deployment of the original registers -
 * one fewer than it asks for, because Vertica's driver is absent from the image it ships
 * (question-log row 28). This registry does not depend on what happens to be installed:
 * every type is present whatever drivers the deployment carries, so the forms, the
 * validation and `/api/data_sources/types` are the same everywhere. Whether a query can
 * actually be sent is a separate question, answered by the transport each definition names.
 *
 * <p>A schema is a wire format rather than a decision, so these are the original's own
 * documents property for property. `probes/generate_registry.py` is how they were checked.
 */
public final class Registry {

  private Registry() {}

  /**
   * Built once, by the class loader, rather than filled on first use.
   *
   * <p>A lazily-filled static map is shared mutable state: two requests arriving together
   * on a cold instance both find it empty and both fill it. Holding it in a nested class
   * makes the JVM do the locking, and the map handed out is unmodifiable.
   */
  private static final class Held {
    static final Map<String, RunnerType> TYPES = build();
  }

  private static void add(Map<String, RunnerType> into, RunnerType type) {
    into.put(type.type(), type);
  }

  /** Every registered type, keyed by its wire name. */
  public static Map<String, RunnerType> all() {
    return Held.TYPES;
  }

  private static Map<String, RunnerType> build() {
    var into = new LinkedHashMap<String, RunnerType>();
    register(into);
    return java.util.Collections.unmodifiableMap(into);
  }

  public static RunnerType get(String type) {
    return all().get(type);
  }

  public static Map<String, Object> configurationSchema(String type) {
    var runner = get(type);
    return runner == null ? null : runner.configurationSchema();
  }

  /** The list `/api/data_sources/types` answers: sorted by lower-cased name. */
  public static List<Map<String, Object>> asDocuments(io.akka.redash.domain.Settings settings) {
    var out = new ArrayList<>(registered(settings).values());
    out.sort((a, b) -> a.name().toLowerCase(java.util.Locale.ROOT)
        .compareTo(b.name().toLowerCase(java.util.Locale.ROOT)));
    var documents = new ArrayList<Map<String, Object>>(out.size());
    for (RunnerType type : out) {
      documents.add(type.asDocument());
    }
    return documents;
  }

  // ------------------------------------------------------------------ helpers

  private static Schema schema() {
    return new Schema();
  }

  /**
   * A small builder over the shape redash's schemas actually take. Everything it can
   * express appears in at least one of the seventy-five; nothing it cannot express does.
   */
  static final class Schema {
    private final java.util.Set<String> declaredEmpty = new java.util.LinkedHashSet<>();
    private final Map<String, Object> properties = new LinkedHashMap<>();
    private final List<Object> required = new ArrayList<>();
    private final List<Object> secret = new ArrayList<>();
    private final List<Object> order = new ArrayList<>();
    private final List<Object> extraOptions = new ArrayList<>();

    Schema p(String name, Object... pairs) {
      var property = new LinkedHashMap<String, Object>();
      for (int i = 0; i + 1 < pairs.length; i += 2) {
        property.put(String.valueOf(pairs[i]), pairs[i + 1]);
      }
      properties.put(name, property);
      return this;
    }

    /**
     * Declare the list and leave it empty.
     *
     * <p>Four of the source's schemas declare `required` or `secret` as an empty list
     * rather than leaving it out, and the front end reads a missing key and an empty list
     * differently when it decides whether a field is optional. So an empty declaration is
     * kept as one.
     */
    Schema declaresEmpty(String name) {
      declaredEmpty.add(name);
      return this;
    }

    Schema required(String... names) {
      required.addAll(List.of(names));
      return this;
    }

    Schema secret(String... names) {
      secret.addAll(List.of(names));
      return this;
    }

    Schema order(String... names) {
      order.addAll(List.of(names));
      return this;
    }

    Schema extra(String... names) {
      extraOptions.addAll(List.of(names));
      return this;
    }

    Map<String, Object> build() {
      var out = new LinkedHashMap<String, Object>();
      out.put("type", "object");
      out.put("properties", properties);
      if (!order.isEmpty()) {
        out.put("order", order);
      }
      if (!required.isEmpty() || declaredEmpty.contains("required")) {
        out.put("required", required);
      }
      if (!secret.isEmpty() || declaredEmpty.contains("secret")) {
        out.put("secret", secret);
      }
      if (!extraOptions.isEmpty()) {
        out.put("extra_options", extraOptions);
      }
      return out;
    }
  }

  /** One labelled option of a dropdown the front end draws from an extendedEnum. */
  private static Map<String, Object> option(String value, String name) {
    return Json.map("value", value, "name", name);
  }

  private static List<Object> options(Object... entries) {
    return List.of(entries);
  }


  /**
   * The module each type is registered by, which is what the three list settings name.
   *
   * <p>redash composes its registry from module paths rather than from type names, and the
   * two are not one to one: `redash.query_runner.elasticsearch2` registers three types.
   * Recorded from the running original alongside the schemas (question-log row 28).
   */
  private static final Map<String, String> MODULES = Map.ofEntries(
      Map.entry("arangodb", "redash.query_runner.arango"),
      Map.entry("athena", "redash.query_runner.athena"),
      Map.entry("aws_es", "redash.query_runner.amazon_elasticsearch"),
      Map.entry("axibasetsd", "redash.query_runner.axibase_tsd"),
      Map.entry("azure_kusto", "redash.query_runner.azure_kusto"),
      Map.entry("bigquery", "redash.query_runner.big_query"),
      Map.entry("Cassandra", "redash.query_runner.cass"),
      Map.entry("clickhouse", "redash.query_runner.clickhouse"),
      Map.entry("cloudwatch", "redash.query_runner.cloudwatch"),
      Map.entry("cloudwatch_insights", "redash.query_runner.cloudwatch_insights"),
      Map.entry("cockroach", "redash.query_runner.pg"),
      Map.entry("corporate_memory", "redash.query_runner.corporate_memory"),
      Map.entry("couchbase", "redash.query_runner.couchbase"),
      Map.entry("csv", "redash.query_runner.csv"),
      Map.entry("d1", "redash.query_runner.d1"),
      Map.entry("databend", "redash.query_runner.databend"),
      Map.entry("databricks", "redash.query_runner.databricks"),
      Map.entry("db2", "redash.query_runner.db2"),
      Map.entry("dgraph", "redash.query_runner.dgraph"),
      Map.entry("drill", "redash.query_runner.drill"),
      Map.entry("druid", "redash.query_runner.druid"),
      Map.entry("duckdb", "redash.query_runner.duckdb"),
      Map.entry("e6data", "redash.query_runner.e6data"),
      Map.entry("elasticsearch", "redash.query_runner.elasticsearch"),
      Map.entry("elasticsearch2", "redash.query_runner.elasticsearch2"),
      Map.entry("elasticsearch2_OpenDistroSQLElasticSearch", "redash.query_runner.elasticsearch2"),
      Map.entry("elasticsearch2_XPackSQLElasticSearch", "redash.query_runner.elasticsearch2"),
      Map.entry("exasol", "redash.query_runner.exasol"),
      Map.entry("excel", "redash.query_runner.excel"),
      Map.entry("google_analytics", "redash.query_runner.google_analytics"),
      Map.entry("google_analytics4", "redash.query_runner.google_analytics4"),
      Map.entry("google_search_console", "redash.query_runner.google_search_console"),
      Map.entry("google_spreadsheets", "redash.query_runner.google_spreadsheets"),
      Map.entry("graphite", "redash.query_runner.graphite"),
      Map.entry("hive", "redash.query_runner.hive_ds"),
      Map.entry("hive_http", "redash.query_runner.hive_ds"),
      Map.entry("ignite", "redash.query_runner.ignite"),
      Map.entry("impala", "redash.query_runner.impala_ds"),
      Map.entry("influxdb", "redash.query_runner.influx_db"),
      Map.entry("influxdbv2", "redash.query_runner.influx_db_v2"),
      Map.entry("jirajql", "redash.query_runner.jql"),
      Map.entry("json", "redash.query_runner.json_ds"),
      Map.entry("kibana", "redash.query_runner.elasticsearch"),
      Map.entry("kylin", "redash.query_runner.kylin"),
      Map.entry("memsql", "redash.query_runner.memsql_ds"),
      Map.entry("mongodb", "redash.query_runner.mongodb"),
      Map.entry("mssql", "redash.query_runner.mssql"),
      Map.entry("mssql_odbc", "redash.query_runner.mssql_odbc"),
      Map.entry("mysql", "redash.query_runner.mysql"),
      Map.entry("nz", "redash.query_runner.nz"),
      Map.entry("oracle", "redash.query_runner.oracle"),
      Map.entry("pg", "redash.query_runner.pg"),
      Map.entry("phoenix", "redash.query_runner.phoenix"),
      Map.entry("pinot", "redash.query_runner.pinot"),
      Map.entry("presto", "redash.query_runner.presto"),
      Map.entry("prometheus", "redash.query_runner.prometheus"),
      Map.entry("rds_mysql", "redash.query_runner.mysql"),
      Map.entry("redshift", "redash.query_runner.pg"),
      Map.entry("redshift_iam", "redash.query_runner.pg"),
      Map.entry("results", "redash.query_runner.query_results"),
      Map.entry("risingwave", "redash.query_runner.risingwave"),
      Map.entry("rockset", "redash.query_runner.rockset"),
      Map.entry("salesforce", "redash.query_runner.salesforce"),
      Map.entry("scylla", "redash.query_runner.cass"),
      Map.entry("snowflake", "redash.query_runner.snowflake"),
      Map.entry("sparql_endpoint", "redash.query_runner.sparql_endpoint"),
      Map.entry("sqlite", "redash.query_runner.sqlite"),
      Map.entry("tinybird", "redash.query_runner.tinybird"),
      Map.entry("treasuredata", "redash.query_runner.treasuredata"),
      Map.entry("trino", "redash.query_runner.trino"),
      Map.entry("uptycs", "redash.query_runner.uptycs"),
      Map.entry("url", "redash.query_runner.url"),
      Map.entry("yandex_appmetrika", "redash.query_runner.yandex_metrica"),
      Map.entry("yandex_disk", "redash.query_runner.yandex_disk"),
      Map.entry("yandex_metrika", "redash.query_runner.yandex_metrica"));

  /** The module `type` is registered by, as the original registers it. */
  public static String moduleOf(String type) {
    return MODULES.get(type);
  }

  /**
   * The types a deployment registers, which is what the three list settings decide
   * (SPEC-001 R3).
   *
   * <p>The enabled list defaults to every module the original ships; the additional list is
   * added to it and the disabled list taken away, in that order, and a module named twice
   * counts once. A type whose module is not in the resulting set is not registered at all:
   * it is absent from `/api/data_sources/types`, and creating a data source of that type is
   * refused the way an unknown type is.
   */
  public static Map<String, RunnerType> registered(io.akka.redash.domain.Settings settings) {
    var modules = new java.util.LinkedHashSet<>(settings.enabledQueryRunnerModules());
    modules.addAll(settings.additionalQueryRunnerModules());
    modules.removeAll(settings.disabledQueryRunnerModules());
    var out = new LinkedHashMap<String, RunnerType>();
    for (Map.Entry<String, RunnerType> entry : all().entrySet()) {
      if (modules.contains(MODULES.get(entry.getKey()))) {
        out.put(entry.getKey(), entry.getValue());
      }
    }
    return java.util.Collections.unmodifiableMap(out);
  }

  private static void register(Map<String, RunnerType> into) {
    add(into, new RunnerType(
        "arangodb",
        "ArangoDB",
        false,
        "sql",
        true,
        false,
        Sql.LimitStyle.TRAILING,
        "RETURN {'id': 1}",
        Transport.of("arangodb"),
        schema()
                .p("user", "type", "string")
                .p("password", "type", "string")
                .p("host", "type", "string", "default", "127.0.0.1")
                .p("port", "type", "number", "default", 8529)
                .p("dbname", "type", "string", "title", "Database Name")
                .p("timeout", "type", "number", "default", 0.0, "title", "AQL Timeout in seconds (0 = no timeout)")
                .order("host", "port", "user", "password", "dbname")
                .required("host", "user", "password", "dbname")
                .secret("password")
                .build()));
    add(into, new RunnerType(
        "athena",
        "Amazon Athena",
        false,
        "sql",
        true,
        false,
        Sql.LimitStyle.TRAILING,
        "SELECT 1",
        Transport.of("athena"),
        schema()
                .p("region", "type", "string", "title", "AWS Region")
                .p("aws_access_key", "type", "string", "title", "AWS Access Key")
                .p("aws_secret_key", "type", "string", "title", "AWS Secret Key")
                .p("s3_staging_dir", "type", "string", "title", "S3 Staging (Query Results) Bucket Path")
                .p("schema", "type", "string", "title", "Schema Name", "default", "default")
                .p("glue", "type", "boolean", "title", "Use Glue Data Catalog")
                .p("catalog_ids", "type", "string", "title", "Enter Glue Data Catalog IDs, separated by commas (leave blank for default catalog)")
                .p("work_group", "type", "string", "title", "Athena Work Group", "default", "primary")
                .p("cost_per_tb", "type", "number", "title", "Athena cost per Tb scanned (USD)", "default", 5)
                .p("result_reuse_enable", "type", "boolean", "title", "Reuse Athena query results")
                .p("result_reuse_minutes", "type", "number", "title", "Minutes to reuse Athena query results", "default", 60)
                .p("encryption_option", "type", "string", "title", "Encryption Option")
                .p("kms_key", "type", "string", "title", "KMS Key")
                .order("region", "aws_access_key", "aws_secret_key", "s3_staging_dir", "schema", "work_group", "cost_per_tb", "result_reuse_enable", "result_reuse_minutes")
                .required("region", "s3_staging_dir")
                .secret("aws_secret_key")
                .extra("glue", "catalog_ids", "cost_per_tb", "result_reuse_enable", "result_reuse_minutes", "encryption_option", "kms_key")
                .build()));
    add(into, new RunnerType(
        "aws_es",
        "Amazon Elasticsearch Service",
        false,
        "json",
        false,
        false,
        Sql.LimitStyle.TRAILING,
        null,
        Transport.of("aws_es"),
        schema()
                .p("server", "type", "string", "title", "Endpoint")
                .p("region", "type", "string")
                .p("access_key", "type", "string", "title", "Access Key")
                .p("secret_key", "type", "string", "title", "Secret Key")
                .p("use_aws_iam_profile", "type", "boolean", "title", "Use AWS IAM Profile")
                .order("server", "region", "access_key", "secret_key", "use_aws_iam_profile")
                .required("server", "region")
                .secret("secret_key")
                .build()));
    add(into, new RunnerType(
        "axibasetsd",
        "Axibase Time Series Database",
        false,
        "sql",
        true,
        false,
        Sql.LimitStyle.TRAILING,
        "SELECT 1",
        Transport.of("axibasetsd"),
        schema()
                .p("protocol", "type", "string", "title", "Protocol", "default", "http")
                .p("hostname", "type", "string", "title", "Host", "default", "axibase_tsd_hostname")
                .p("port", "type", "number", "title", "Port", "default", 8088)
                .p("username", "type", "string")
                .p("password", "type", "string", "title", "Password")
                .p("timeout", "type", "number", "default", 600, "title", "Connection Timeout")
                .p("min_insert_date", "type", "string", "title", "Metric Minimum Insert Date")
                .p("expression", "type", "string", "title", "Metric Filter")
                .p("limit", "type", "number", "default", 5000, "title", "Metric Limit")
                .p("trust_certificate", "type", "boolean", "title", "Trust SSL Certificate")
                .required("username", "password", "hostname", "protocol", "port")
                .secret("password")
                .build()));
    add(into, new RunnerType(
        "azure_kusto",
        "Azure Data Explorer (Kusto)",
        false,
        "custom",
        false,
        false,
        Sql.LimitStyle.TRAILING,
        "let noop = datatable (Noop:string)[1]; noop",
        Transport.of("azure_kusto"),
        schema()
                .p("cluster", "type", "string")
                .p("azure_ad_client_id", "type", "string", "title", "Azure AD Client ID")
                .p("azure_ad_client_secret", "type", "string", "title", "Azure AD Client Secret")
                .p("azure_ad_tenant_id", "type", "string", "title", "Azure AD Tenant Id")
                .p("database", "type", "string")
                .p("msi", "type", "boolean", "title", "Use Managed Service Identity")
                .p("user_msi", "type", "string", "title", "User-assigned managed identity client ID")
                .order("cluster", "azure_ad_client_id", "azure_ad_client_secret", "azure_ad_tenant_id", "database")
                .required("cluster", "database")
                .secret("azure_ad_client_secret")
                .build()));
    add(into, new RunnerType(
        "bigquery",
        "BigQuery",
        false,
        "sql",
        true,
        true,
        Sql.LimitStyle.TRAILING,
        "SELECT 1",
        Transport.of("bigquery"),
        schema()
                .p("projectId", "type", "string", "title", "Project ID")
                .p("jsonKeyFile", "type", "string", "title", "JSON Key File (ADC is used if omitted)")
                .p("totalMBytesProcessedLimit", "type", "number", "title", "Scanned Data Limit (MB)")
                .p("userDefinedFunctionResourceUri", "type", "string", "title", "UDF Source URIs (i.e. gs://bucket/date_utils.js, gs://bucket/string_utils.js )")
                .p("useStandardSql", "type", "boolean", "title", "Use Standard SQL", "default", true)
                .p("location", "type", "string", "title", "Processing Location")
                .p("loadSchema", "type", "boolean", "title", "Load Schema")
                .p("maximumBillingTier", "type", "number", "title", "Maximum Billing Tier")
                .p("useQueryAnnotation", "type", "boolean", "title", "Use Query Annotation", "default", false)
                .order("projectId", "jsonKeyFile", "loadSchema", "useStandardSql", "location", "totalMBytesProcessedLimit", "maximumBillingTier", "userDefinedFunctionResourceUri", "useQueryAnnotation")
                .required("projectId")
                .secret("jsonKeyFile")
                .build()));
    add(into, new RunnerType(
        "Cassandra",
        "Cassandra",
        false,
        "sql",
        true,
        false,
        Sql.LimitStyle.TRAILING,
        "SELECT dateof(now()) FROM system.local",
        Transport.of("Cassandra"),
        schema()
                .p("host", "type", "string")
                .p("port", "type", "number", "default", 9042)
                .p("keyspace", "type", "string", "title", "Keyspace name")
                .p("username", "type", "string", "title", "Username")
                .p("password", "type", "string", "title", "Password")
                .p("protocol", "type", "number", "title", "Protocol Version", "default", 3)
                .p("timeout", "type", "number", "title", "Timeout", "default", 10)
                .p("useSsl", "type", "boolean", "title", "Use SSL", "default", false)
                .p("sslCertificateFile", "type", "string", "title", "SSL Certificate File")
                .p("sslProtocol", "type", "string", "title", "SSL Protocol", "enum", List.of("PROTOCOL_SSLv23", "PROTOCOL_TLS", "PROTOCOL_TLS_CLIENT", "PROTOCOL_TLS_SERVER", "PROTOCOL_TLSv1", "PROTOCOL_TLSv1_1", "PROTOCOL_TLSv1_2"))
                .required("keyspace", "host", "useSsl")
                .secret("sslCertificateFile")
                .build()));
    add(into, new RunnerType(
        "clickhouse",
        "ClickHouse",
        false,
        "sql",
        true,
        true,
        Sql.LimitStyle.TRAILING,
        "SELECT 1",
        Transport.of("clickhouse"),
        schema()
                .p("url", "type", "string", "default", "http://127.0.0.1:8123")
                .p("user", "type", "string", "default", "default")
                .p("password", "type", "string")
                .p("dbname", "type", "string", "title", "Database Name")
                .p("timeout", "type", "number", "title", "Request Timeout", "default", 30)
                .p("verify", "type", "boolean", "title", "Verify SSL certificate", "default", true)
                .order("url", "user", "password", "dbname")
                .required("dbname")
                .secret("password")
                .extra("timeout", "verify")
                .build()));
    add(into, new RunnerType(
        "cloudwatch",
        "Amazon CloudWatch",
        false,
        "yaml",
        false,
        false,
        Sql.LimitStyle.TRAILING,
        null,
        Transport.of("cloudwatch"),
        schema()
                .p("region", "type", "string", "title", "AWS Region")
                .p("aws_access_key", "type", "string", "title", "AWS Access Key")
                .p("aws_secret_key", "type", "string", "title", "AWS Secret Key")
                .order("region", "aws_access_key", "aws_secret_key")
                .required("region", "aws_access_key", "aws_secret_key")
                .secret("aws_secret_key")
                .build()));
    add(into, new RunnerType(
        "cloudwatch_insights",
        "Amazon CloudWatch Logs Insights",
        false,
        "yaml",
        false,
        false,
        Sql.LimitStyle.TRAILING,
        null,
        Transport.of("cloudwatch_insights"),
        schema()
                .p("region", "type", "string", "title", "AWS Region")
                .p("aws_access_key", "type", "string", "title", "AWS Access Key")
                .p("aws_secret_key", "type", "string", "title", "AWS Secret Key")
                .order("region", "aws_access_key", "aws_secret_key")
                .required("region", "aws_access_key", "aws_secret_key")
                .secret("aws_secret_key")
                .build()));
    add(into, new RunnerType(
        "cockroach",
        "CockroachDB",
        false,
        "sql",
        true,
        true,
        Sql.LimitStyle.TRAILING,
        "SELECT 1",
        Transport.of("cockroach"),
        schema()
                .p("user", "type", "string")
                .p("password", "type", "string")
                .p("host", "type", "string", "default", "127.0.0.1")
                .p("port", "type", "number", "default", 5432)
                .p("dbname", "type", "string", "title", "Database Name")
                .p("dsn", "type", "string", "default", "application_name=redash", "title", "Parameters")
                .p("sslmode", "type", "string", "title", "SSL Mode", "default", "prefer", "extendedEnum", options(option("disable", "Disable"), option("allow", "Allow"), option("prefer", "Prefer"), option("require", "Require"), option("verify-ca", "Verify CA"), option("verify-full", "Verify Full")))
                .p("sslrootcertFile", "type", "string", "title", "SSL Root Certificate")
                .p("sslcertFile", "type", "string", "title", "SSL Client Certificate")
                .p("sslkeyFile", "type", "string", "title", "SSL Client Key")
                .order("host", "port", "user", "password")
                .required("dbname")
                .secret("password", "sslrootcertFile", "sslcertFile", "sslkeyFile")
                .extra("sslmode", "sslrootcertFile", "sslcertFile", "sslkeyFile")
                .build()));
    add(into, new RunnerType(
        "corporate_memory",
        "eccenca Corporate Memory (with SPARQL)",
        false,
        "sql",
        false,
        false,
        Sql.LimitStyle.TRAILING,
        "SELECT ?noop WHERE {BIND('noop' as ?noop)}",
        Transport.of("corporate_memory"),
        schema()
                .p("CMEM_BASE_URI", "type", "string", "title", "Base URL")
                .p("OAUTH_GRANT_TYPE", "type", "string", "title", "Grant Type", "default", "client_credentials", "extendedEnum", options(option("client_credentials", "client_credentials"), option("password", "password")))
                .p("OAUTH_CLIENT_ID", "type", "string", "title", "Client ID (e.g. cmem-service-account)", "default", "cmem-service-account")
                .p("OAUTH_CLIENT_SECRET", "type", "string", "title", "Client Secret - only needed for grant type 'client_credentials'")
                .p("OAUTH_USER", "type", "string", "title", "User account - only needed for grant type 'password'")
                .p("OAUTH_PASSWORD", "type", "string", "title", "User Password - only needed for grant type 'password'")
                .p("SSL_VERIFY", "type", "boolean", "title", "Verify SSL certificates for API requests", "default", true)
                .p("REQUESTS_CA_BUNDLE", "type", "string", "title", "Path to the CA Bundle file (.pem)")
                .required("CMEM_BASE_URI", "OAUTH_GRANT_TYPE", "OAUTH_CLIENT_ID")
                .secret("OAUTH_CLIENT_SECRET", "OAUTH_PASSWORD")
                .extra("OAUTH_GRANT_TYPE", "OAUTH_USER", "OAUTH_PASSWORD", "SSL_VERIFY", "REQUESTS_CA_BUNDLE")
                .build()));
    add(into, new RunnerType(
        "couchbase",
        "Couchbase",
        false,
        "sql",
        false,
        false,
        Sql.LimitStyle.TRAILING,
        "Select 1",
        Transport.of("couchbase"),
        schema()
                .p("protocol", "type", "string", "default", "http")
                .p("host", "type", "string")
                .p("port", "type", "string", "title", "Port (Defaults: 8095 - Analytics, 8093 - N1QL)", "default", "8095")
                .p("user", "type", "string")
                .p("password", "type", "string")
                .order("protocol", "host", "port", "user", "password")
                .required("host", "user", "password")
                .secret("password")
                .build()));
    add(into, new RunnerType(
        "csv",
        "CSV",
        false,
        "yaml",
        false,
        false,
        Sql.LimitStyle.TRAILING,
        null,
        Transport.of("csv"),
        schema()
                .build()));
    add(into, new RunnerType(
        "d1",
        "Cloudflare D1",
        false,
        "sql",
        true,
        false,
        Sql.LimitStyle.TRAILING,
        null,
        Transport.of("d1"),
        schema()
                .p("cf_url", "type", "string", "title", "Cloudflare D1 API URL")
                .p("cf_token", "type", "string", "title", "Cloudflare API Token")
                .required("cf_url", "cf_token")
                .secret("cf_token")
                .build()));
    add(into, new RunnerType(
        "databend",
        "Databend",
        false,
        "sql",
        true,
        false,
        Sql.LimitStyle.TRAILING,
        "SELECT 1",
        Transport.of("databend"),
        schema()
                .p("host", "type", "string", "default", "localhost")
                .p("port", "type", "string", "default", "8000")
                .p("username", "type", "string")
                .p("password", "type", "string", "default", "")
                .p("database", "type", "string")
                .p("secure", "type", "boolean", "default", false)
                .order("username", "password", "host", "port", "database")
                .required("username", "database")
                .secret("password")
                .build()));
    add(into, new RunnerType(
        "databricks",
        "Databricks",
        false,
        "sql",
        false,
        true,
        Sql.LimitStyle.TRAILING,
        "SELECT 1",
        Transport.of("databricks"),
        schema()
                .p("host", "type", "string")
                .p("http_path", "type", "string", "title", "HTTP Path")
                .p("http_password", "type", "string", "title", "Access Token")
                .order("host", "http_path", "http_password")
                .required("host", "http_path", "http_password")
                .secret("http_password")
                .build()));
    add(into, new RunnerType(
        "db2",
        "DB2",
        false,
        "sql",
        true,
        true,
        Sql.LimitStyle.TRAILING,
        "SELECT 1 FROM SYSIBM.SYSDUMMY1",
        Transport.of("db2"),
        schema()
                .p("user", "type", "string")
                .p("password", "type", "string")
                .p("host", "type", "string", "default", "127.0.0.1")
                .p("port", "type", "number", "default", 50000)
                .p("dbname", "type", "string", "title", "Database Name")
                .order("host", "port", "user", "password", "dbname")
                .required("dbname")
                .secret("password")
                .build()));
    add(into, new RunnerType(
        "dgraph",
        "Dgraph",
        false,
        "sql",
        false,
        false,
        Sql.LimitStyle.TRAILING,
        "\n    {\n      test() {\n      }\n    }\n    ",
        Transport.of("dgraph"),
        schema()
                .p("user", "type", "string")
                .p("password", "type", "string")
                .p("servers", "type", "string")
                .order("servers", "user", "password")
                .required("servers")
                .secret("password")
                .build()));
    add(into, new RunnerType(
        "drill",
        "Apache Drill",
        false,
        "sql",
        false,
        false,
        Sql.LimitStyle.TRAILING,
        "select version from sys.version",
        Transport.of("drill"),
        schema()
                .p("url", "type", "string", "title", "Drill URL")
                .p("username", "type", "string", "title", "Username")
                .p("password", "type", "string", "title", "Password")
                .p("allowed_schemas", "type", "string", "title", "List of schemas to use in schema browser (comma separated)")
                .order("url", "username", "password", "allowed_schemas")
                .required("url")
                .secret("password")
                .build()));
    add(into, new RunnerType(
        "druid",
        "Druid",
        false,
        "sql",
        true,
        false,
        Sql.LimitStyle.TRAILING,
        "SELECT 1",
        Transport.of("druid"),
        schema()
                .p("host", "type", "string", "default", "localhost")
                .p("port", "type", "number", "default", 8082)
                .p("scheme", "type", "string", "default", "http")
                .p("user", "type", "string")
                .p("password", "type", "string")
                .order("scheme", "host", "port", "user", "password")
                .required("host")
                .secret("password")
                .build()));
    add(into, new RunnerType(
        "duckdb",
        "DuckDB",
        false,
        "sql",
        true,
        true,
        Sql.LimitStyle.TRAILING,
        "SELECT 1",
        Transport.of("duckdb"),
        schema()
                .p("dbpath", "type", "string", "title", "Database Path", "default", ":memory:")
                .p("extensions", "type", "string", "title", "Extensions (comma separated)")
                .order("dbpath", "extensions")
                .required("dbpath")
                .build()));
    add(into, new RunnerType(
        "e6data",
        "e6data",
        false,
        "sql",
        false,
        false,
        Sql.LimitStyle.TRAILING,
        null,
        Transport.of("e6data"),
        schema()
                .p("host", "type", "string")
                .p("port", "type", "number")
                .p("username", "type", "string")
                .p("password", "type", "string")
                .p("catalog", "type", "string")
                .p("database", "type", "string")
                .order("host", "port", "username", "password", "catalog", "database")
                .required("host", "port", "username", "password", "catalog", "database")
                .secret("password")
                .build()));
    add(into, new RunnerType(
        "elasticsearch",
        "Elasticsearch",
        true,
        "json",
        false,
        false,
        Sql.LimitStyle.TRAILING,
        null,
        Transport.of("elasticsearch"),
        schema()
                .p("server", "type", "string", "title", "Base URL")
                .p("basic_auth_user", "type", "string", "title", "Basic Auth User")
                .p("basic_auth_password", "type", "string", "title", "Basic Auth Password")
                .order("server", "basic_auth_user", "basic_auth_password")
                .required("server")
                .secret("basic_auth_password")
                .build()));
    add(into, new RunnerType(
        "elasticsearch2",
        "Elasticsearch",
        false,
        "json",
        false,
        false,
        Sql.LimitStyle.TRAILING,
        null,
        Transport.of("elasticsearch2"),
        schema()
                .p("url", "type", "string", "title", "URL base path")
                .p("username", "type", "string", "title", "HTTP Basic Auth Username")
                .p("password", "type", "string", "title", "HTTP Basic Auth Password")
                .order("url", "username", "password")
                .required("url")
                .secret("password")
                .build()));
    add(into, new RunnerType(
        "elasticsearch2_OpenDistroSQLElasticSearch",
        "Open Distro SQL Elasticsearch",
        false,
        "sql",
        false,
        false,
        Sql.LimitStyle.TRAILING,
        null,
        Transport.of("elasticsearch2_OpenDistroSQLElasticSearch"),
        schema()
                .p("url", "type", "string", "title", "URL base path")
                .p("username", "type", "string", "title", "HTTP Basic Auth Username")
                .p("password", "type", "string", "title", "HTTP Basic Auth Password")
                .order("url", "username", "password")
                .required("url")
                .secret("password")
                .build()));
    add(into, new RunnerType(
        "elasticsearch2_XPackSQLElasticSearch",
        "X-Pack SQL Elasticsearch",
        false,
        "sql",
        false,
        false,
        Sql.LimitStyle.TRAILING,
        null,
        Transport.of("elasticsearch2_XPackSQLElasticSearch"),
        schema()
                .p("url", "type", "string", "title", "URL base path")
                .p("username", "type", "string", "title", "HTTP Basic Auth Username")
                .p("password", "type", "string", "title", "HTTP Basic Auth Password")
                .order("url", "username", "password")
                .required("url")
                .secret("password")
                .build()));
    add(into, new RunnerType(
        "exasol",
        "Exasol",
        false,
        "sql",
        true,
        false,
        Sql.LimitStyle.TRAILING,
        "SELECT 1 FROM DUAL",
        Transport.of("exasol"),
        schema()
                .p("user", "type", "string")
                .p("password", "type", "string")
                .p("host", "type", "string")
                .p("port", "type", "number", "default", 8563)
                .p("encrypted", "type", "boolean", "title", "Enable SSL Encryption")
                .order("host", "port", "user", "password", "encrypted")
                .required("host", "port", "user", "password")
                .secret("password")
                .build()));
    add(into, new RunnerType(
        "excel",
        "Excel",
        false,
        "yaml",
        false,
        false,
        Sql.LimitStyle.TRAILING,
        null,
        Transport.of("excel"),
        schema()
                .build()));
    add(into, new RunnerType(
        "google_analytics",
        "Google Analytics",
        false,
        "json",
        false,
        true,
        Sql.LimitStyle.TRAILING,
        null,
        Transport.of("google_analytics"),
        schema()
                .p("jsonKeyFile", "type", "string", "title", "JSON Key File (ADC is used if omitted)")
                .declaresEmpty("required")
                .secret("jsonKeyFile")
                .build()));
    add(into, new RunnerType(
        "google_analytics4",
        "Google Analytics 4",
        false,
        "sql",
        false,
        false,
        Sql.LimitStyle.TRAILING,
        null,
        Transport.of("google_analytics4"),
        schema()
                .p("propertyId", "type", "number", "title", "Property Id")
                .p("jsonKeyFile", "type", "string", "title", "JSON Key File (ADC is used if omitted)")
                .required("propertyId")
                .secret("jsonKeyFile")
                .build()));
    add(into, new RunnerType(
        "google_search_console",
        "Google Search Console",
        false,
        "json",
        false,
        true,
        Sql.LimitStyle.TRAILING,
        null,
        Transport.of("google_search_console"),
        schema()
                .p("siteURL", "type", "string", "title", "Site URL")
                .p("jsonKeyFile", "type", "string", "title", "JSON Key File (ADC is used if omitted)")
                .declaresEmpty("required")
                .secret("jsonKeyFile")
                .build()));
    add(into, new RunnerType(
        "google_spreadsheets",
        "Google Sheets",
        false,
        "custom",
        false,
        false,
        Sql.LimitStyle.TRAILING,
        null,
        Transport.of("google_spreadsheets"),
        schema()
                .p("jsonKeyFile", "type", "string", "title", "JSON Key File (ADC is used if omitted)")
                .declaresEmpty("required")
                .secret("jsonKeyFile")
                .build()));
    add(into, new RunnerType(
        "graphite",
        "Graphite",
        false,
        "custom",
        false,
        false,
        Sql.LimitStyle.TRAILING,
        null,
        Transport.of("graphite"),
        schema()
                .p("url", "type", "string")
                .p("username", "type", "string")
                .p("password", "type", "string")
                .p("verify", "type", "boolean", "title", "Verify SSL certificate")
                .required("url")
                .secret("password")
                .build()));
    add(into, new RunnerType(
        "hive",
        "Hive",
        false,
        "sql",
        false,
        true,
        Sql.LimitStyle.TRAILING,
        "SELECT 1",
        Transport.of("hive"),
        schema()
                .p("host", "type", "string")
                .p("port", "type", "number")
                .p("database", "type", "string")
                .p("username", "type", "string")
                .order("host", "port", "database", "username")
                .required("host")
                .build()));
    add(into, new RunnerType(
        "hive_http",
        "Hive (HTTP)",
        false,
        "sql",
        false,
        true,
        Sql.LimitStyle.TRAILING,
        "SELECT 1",
        Transport.of("hive_http"),
        schema()
                .p("host", "type", "string")
                .p("port", "type", "number")
                .p("database", "type", "string")
                .p("username", "type", "string")
                .p("http_scheme", "type", "string", "title", "HTTP Scheme (http or https)", "default", "https")
                .p("http_path", "type", "string", "title", "HTTP Path")
                .p("http_password", "type", "string", "title", "Password")
                .order("host", "port", "http_path", "username", "http_password", "database", "http_scheme")
                .required("host", "http_path")
                .secret("http_password")
                .build()));
    add(into, new RunnerType(
        "ignite",
        "Apache Ignite",
        false,
        "sql",
        false,
        true,
        Sql.LimitStyle.TRAILING,
        "SELECT 1",
        Transport.of("ignite"),
        schema()
                .p("user", "type", "string")
                .p("password", "type", "string")
                .p("server", "type", "string", "default", "127.0.0.1:10800")
                .p("tls", "type", "boolean", "default", false, "title", "Use SSL/TLS connection")
                .p("schema", "type", "string", "title", "Schema Name", "default", "PUBLIC")
                .p("distributed_joins", "type", "boolean", "title", "Allow distributed joins", "default", false)
                .p("enforce_join_order", "type", "boolean", "title", "Enforce join order", "default", false)
                .p("lazy", "type", "boolean", "title", "Lazy query execution", "default", true)
                .p("gridgain", "type", "boolean", "title", "Use GridGain libraries", "default", false)
                .required("server")
                .secret("password")
                .build()));
    add(into, new RunnerType(
        "impala",
        "Impala",
        false,
        "sql",
        true,
        true,
        Sql.LimitStyle.TRAILING,
        "show schemas",
        Transport.of("impala"),
        schema()
                .p("host", "type", "string")
                .p("port", "type", "number")
                .p("protocol", "type", "string", "extendedEnum", options(option("beeswax", "Beeswax"), option("hiveserver2", "Hive Server 2")), "title", "Protocol")
                .p("database", "type", "string")
                .p("use_ldap", "type", "boolean")
                .p("use_ssl", "type", "boolean")
                .p("ldap_user", "type", "string")
                .p("ldap_password", "type", "string")
                .p("timeout", "type", "number")
                .required("host")
                .secret("ldap_password")
                .build()));
    add(into, new RunnerType(
        "influxdb",
        "InfluxDB",
        false,
        "sql",
        false,
        false,
        Sql.LimitStyle.TRAILING,
        "show measurements limit 1",
        Transport.of("influxdb"),
        schema()
                .p("url", "type", "string")
                .required("url")
                .build()));
    add(into, new RunnerType(
        "influxdbv2",
        "InfluxDBv2",
        false,
        "sql",
        false,
        false,
        Sql.LimitStyle.TRAILING,
        null,
        Transport.of("influxdbv2"),
        schema()
                .p("url", "type", "string", "title", "URL")
                .p("org", "type", "string", "title", "Organization")
                .p("token", "type", "string", "title", "Token")
                .p("verify_ssl", "type", "boolean", "title", "Verify SSL", "default", false)
                .p("cert_File", "type", "string", "title", "SSL Client Certificate", "default", null)
                .p("cert_key_File", "type", "string", "title", "SSL Client Key", "default", null)
                .p("cert_key_password", "type", "string", "title", "Password for SSL Client Key", "default", null)
                .p("ssl_ca_cert_File", "type", "string", "title", "SSL Root Certificate", "default", null)
                .order("url", "org", "token", "cert_File", "cert_key_File", "cert_key_password", "ssl_ca_cert_File")
                .required("url", "org", "token")
                .secret("token", "cert_File", "cert_key_File", "cert_key_password", "ssl_ca_cert_File")
                .extra("verify_ssl", "cert_File", "cert_key_File", "cert_key_password", "ssl_ca_cert_File")
                .build()));
    add(into, new RunnerType(
        "jirajql",
        "JIRA (JQL)",
        false,
        "json",
        false,
        false,
        Sql.LimitStyle.TRAILING,
        "{\"queryType\": \"count\"}",
        Transport.of("jirajql"),
        schema()
                .p("url", "type", "string", "title", "JIRA URL")
                .p("username", "type", "string", "title", "Username")
                .p("password", "type", "string", "title", "API Token")
                .order("url", "username", "password")
                .required("url", "username", "password")
                .secret("password")
                .build()));
    add(into, new RunnerType(
        "json",
        "JSON",
        false,
        "yaml",
        false,
        false,
        Sql.LimitStyle.TRAILING,
        null,
        Transport.of("json"),
        schema()
                .p("base_url", "type", "string", "title", "Base URL")
                .p("username", "type", "string", "title", "HTTP Basic Auth Username")
                .p("password", "type", "string", "title", "HTTP Basic Auth Password")
                .order("base_url", "username", "password")
                .secret("password")
                .build()));
    add(into, new RunnerType(
        "kibana",
        "Kibana",
        true,
        "json",
        false,
        false,
        Sql.LimitStyle.TRAILING,
        null,
        Transport.of("kibana"),
        schema()
                .p("server", "type", "string", "title", "Base URL")
                .p("basic_auth_user", "type", "string", "title", "Basic Auth User")
                .p("basic_auth_password", "type", "string", "title", "Basic Auth Password")
                .order("server", "basic_auth_user", "basic_auth_password")
                .required("server")
                .secret("basic_auth_password")
                .build()));
    add(into, new RunnerType(
        "kylin",
        "Kylin",
        false,
        "sql",
        true,
        false,
        Sql.LimitStyle.TRAILING,
        null,
        Transport.of("kylin"),
        schema()
                .p("user", "type", "string", "title", "Kylin Username")
                .p("password", "type", "string", "title", "Kylin Password")
                .p("url", "type", "string", "title", "Kylin API URL", "default", "http://kylin.example.com/kylin/")
                .p("project", "type", "string", "title", "Kylin Project")
                .order("url", "project", "user", "password")
                .required("url", "project", "user", "password")
                .secret("password")
                .build()));
    add(into, new RunnerType(
        "memsql",
        "MemSQL",
        false,
        "sql",
        false,
        true,
        Sql.LimitStyle.TRAILING,
        "SELECT 1",
        Transport.of("memsql"),
        schema()
                .p("host", "type", "string")
                .p("port", "type", "number")
                .p("user", "type", "string")
                .p("password", "type", "string")
                .required("host", "port")
                .secret("password")
                .build()));
    add(into, new RunnerType(
        "mongodb",
        "MongoDB",
        false,
        "json",
        false,
        false,
        Sql.LimitStyle.TRAILING,
        null,
        Transport.of("mongodb"),
        schema()
                .p("connectionString", "type", "string", "title", "Connection String")
                .p("username", "type", "string")
                .p("password", "type", "string")
                .p("dbName", "type", "string", "title", "Database Name")
                .p("replicaSetName", "type", "string", "title", "Replica Set Name")
                .p("readPreference", "type", "string", "extendedEnum", options(option("primaryPreferred", "Primary Preferred"), option("primary", "Primary"), option("secondary", "Secondary"), option("secondaryPreferred", "Secondary Preferred"), option("nearest", "Nearest")), "title", "Replica Set Read Preference")
                .p("flatten", "type", "string", "extendedEnum", options(option("False", "False"), option("True", "True")), "title", "Flatten Results")
                .required("connectionString", "dbName")
                .secret("password")
                .build()));
    add(into, new RunnerType(
        "mssql",
        "Microsoft SQL Server",
        false,
        "sql",
        false,
        true,
        Sql.LimitStyle.TOP,
        "SELECT 1",
        Transport.of("mssql"),
        schema()
                .p("user", "type", "string")
                .p("password", "type", "string")
                .p("server", "type", "string", "default", "127.0.0.1")
                .p("port", "type", "number", "default", 1433)
                .p("tds_version", "type", "string", "default", "7.0", "title", "TDS Version")
                .p("charset", "type", "string", "default", "UTF-8", "title", "Character Set")
                .p("db", "type", "string", "title", "Database Name")
                .required("db")
                .secret("password")
                .build()));
    add(into, new RunnerType(
        "mssql_odbc",
        "Microsoft SQL Server (ODBC)",
        false,
        "sql",
        false,
        false,
        Sql.LimitStyle.TOP,
        "SELECT 1",
        Transport.of("mssql_odbc"),
        schema()
                .p("server", "type", "string")
                .p("port", "type", "number", "default", 1433)
                .p("user", "type", "string")
                .p("password", "type", "string")
                .p("db", "type", "string", "title", "Database Name")
                .p("charset", "type", "string", "default", "UTF-8", "title", "Character Set")
                .p("use_ssl", "type", "boolean", "title", "Use SSL", "default", false)
                .p("verify_ssl", "type", "boolean", "title", "Verify SSL certificate", "default", false)
                .order("server", "port", "user", "password", "db", "charset", "use_ssl", "verify_ssl")
                .required("server", "user", "password", "db")
                .secret("password")
                .extra("verify_ssl", "use_ssl")
                .build()));
    add(into, new RunnerType(
        "mysql",
        "MySQL",
        false,
        "sql",
        true,
        true,
        Sql.LimitStyle.TRAILING,
        "SELECT 1",
        Transport.of("mysql"),
        schema()
                .p("host", "type", "string", "default", "127.0.0.1")
                .p("user", "type", "string")
                .p("passwd", "type", "string", "title", "Password")
                .p("db", "type", "string", "title", "Database name")
                .p("port", "type", "number", "default", 3306)
                .p("connect_timeout", "type", "number", "default", 60, "title", "Connection Timeout")
                .p("charset", "type", "string", "default", "utf8mb4")
                .p("use_unicode", "type", "boolean", "default", true)
                .p("autocommit", "type", "boolean", "default", false)
                .p("ssl_mode", "type", "string", "title", "SSL Mode", "default", "preferred", "extendedEnum", options(option("disabled", "Disabled"), option("preferred", "Preferred"), option("required", "Required"), option("verify-ca", "Verify CA"), option("verify-identity", "Verify Identity")))
                .p("use_ssl", "type", "boolean", "title", "Use SSL")
                .p("ssl_cacert", "type", "string", "title", "Path to CA certificate file to verify peer against (SSL)")
                .p("ssl_cert", "type", "string", "title", "Path to client certificate file (SSL)")
                .p("ssl_key", "type", "string", "title", "Path to private key file (SSL)")
                .order("host", "port", "user", "passwd", "db", "connect_timeout", "charset", "use_unicode", "autocommit")
                .required("db")
                .secret("passwd")
                .build()));
    add(into, new RunnerType(
        "nz",
        "Netezza",
        false,
        "sql",
        true,
        true,
        Sql.LimitStyle.TRAILING,
        "SELECT 1",
        Transport.of("nz"),
        schema()
                .p("user", "type", "string")
                .p("password", "type", "string")
                .p("host", "type", "string", "default", "127.0.0.1")
                .p("port", "type", "number", "default", 5480)
                .p("database", "type", "string", "title", "Database Name", "default", "system")
                .order("host", "port", "user", "password", "database")
                .required("user", "password", "database")
                .secret("password")
                .build()));
    add(into, new RunnerType(
        "oracle",
        "Oracle",
        false,
        "sql",
        false,
        true,
        Sql.LimitStyle.FETCH_NEXT,
        "SELECT 1 FROM dual",
        Transport.of("oracle"),
        schema()
                .p("user", "type", "string")
                .p("password", "type", "string")
                .p("host", "type", "string", "title", "Host: To use a DSN Service Name instead, use the text string `_useservicename` in the host name field.")
                .p("port", "type", "number")
                .p("servicename", "type", "string", "title", "DSN Service Name")
                .p("encoding", "type", "string")
                .required("servicename", "user", "password", "host", "port")
                .secret("password")
                .extra("encoding")
                .build()));
    add(into, new RunnerType(
        "pg",
        "PostgreSQL",
        false,
        "sql",
        true,
        true,
        Sql.LimitStyle.TRAILING,
        "SELECT 1",
        Transport.of("pg"),
        schema()
                .p("user", "type", "string")
                .p("password", "type", "string")
                .p("host", "type", "string", "default", "127.0.0.1")
                .p("port", "type", "number", "default", 5432)
                .p("dbname", "type", "string", "title", "Database Name")
                .p("dsn", "type", "string", "default", "application_name=redash", "title", "Parameters")
                .p("sslmode", "type", "string", "title", "SSL Mode", "default", "prefer", "extendedEnum", options(option("disable", "Disable"), option("allow", "Allow"), option("prefer", "Prefer"), option("require", "Require"), option("verify-ca", "Verify CA"), option("verify-full", "Verify Full")))
                .p("sslrootcertFile", "type", "string", "title", "SSL Root Certificate")
                .p("sslcertFile", "type", "string", "title", "SSL Client Certificate")
                .p("sslkeyFile", "type", "string", "title", "SSL Client Key")
                .order("host", "port", "user", "password")
                .required("dbname")
                .secret("password", "sslrootcertFile", "sslcertFile", "sslkeyFile")
                .extra("sslmode", "sslrootcertFile", "sslcertFile", "sslkeyFile")
                .build()));
    add(into, new RunnerType(
        "phoenix",
        "Phoenix",
        false,
        "sql",
        true,
        false,
        Sql.LimitStyle.TRAILING,
        "select 1",
        Transport.of("phoenix"),
        schema()
                .p("url", "type", "string")
                .required("url")
                .build()));
    add(into, new RunnerType(
        "pinot",
        "Pinot",
        false,
        "sql",
        true,
        false,
        Sql.LimitStyle.TRAILING,
        "SELECT 1",
        Transport.of("pinot"),
        schema()
                .p("brokerHost", "type", "string", "default", "")
                .p("brokerPort", "type", "number", "default", 8099)
                .p("brokerScheme", "type", "string", "default", "http")
                .p("controllerURI", "type", "string", "default", "")
                .p("username", "type", "string")
                .p("password", "type", "string")
                .order("brokerScheme", "brokerHost", "brokerPort", "controllerURI", "username", "password")
                .required("brokerHost", "controllerURI")
                .secret("password")
                .build()));
    add(into, new RunnerType(
        "presto",
        "Presto",
        false,
        "sql",
        true,
        false,
        Sql.LimitStyle.TRAILING,
        "SHOW TABLES",
        Transport.of("presto"),
        schema()
                .p("host", "type", "string")
                .p("protocol", "type", "string", "default", "http")
                .p("port", "type", "number")
                .p("schema", "type", "string")
                .p("catalog", "type", "string")
                .p("username", "type", "string")
                .p("password", "type", "string")
                .order("host", "protocol", "port", "username", "password", "schema", "catalog")
                .required("host")
                .build()));
    add(into, new RunnerType(
        "prometheus",
        "Prometheus",
        false,
        "sql",
        false,
        false,
        Sql.LimitStyle.TRAILING,
        null,
        Transport.of("prometheus"),
        schema()
                .p("url", "type", "string", "title", "Prometheus API URL")
                .p("verify_ssl", "type", "boolean", "title", "Verify SSL (Ignored, if SSL Root Certificate is given)", "default", true)
                .p("cert_File", "type", "string", "title", "SSL Client Certificate", "default", null)
                .p("cert_key_File", "type", "string", "title", "SSL Client Key", "default", null)
                .p("ca_cert_File", "type", "string", "title", "SSL Root Certificate", "default", null)
                .required("url")
                .secret("cert_File", "cert_key_File", "ca_cert_File")
                .extra("verify_ssl", "cert_File", "cert_key_File", "ca_cert_File")
                .build()));
    add(into, new RunnerType(
        "rds_mysql",
        "MySQL (Amazon RDS)",
        false,
        "sql",
        true,
        true,
        Sql.LimitStyle.TRAILING,
        "SELECT 1",
        Transport.of("rds_mysql"),
        schema()
                .p("host", "type", "string")
                .p("user", "type", "string")
                .p("passwd", "type", "string", "title", "Password")
                .p("db", "type", "string", "title", "Database name")
                .p("port", "type", "number", "default", 3306)
                .p("use_ssl", "type", "boolean", "title", "Use SSL")
                .p("charset", "type", "string", "default", "utf8mb4")
                .order("host", "port", "user", "passwd", "db")
                .required("db", "user", "passwd", "host")
                .secret("passwd")
                .build()));
    add(into, new RunnerType(
        "redshift",
        "Redshift",
        false,
        "sql",
        true,
        true,
        Sql.LimitStyle.TRAILING,
        "SELECT 1",
        Transport.of("redshift"),
        schema()
                .p("user", "type", "string")
                .p("password", "type", "string")
                .p("host", "type", "string")
                .p("port", "type", "number")
                .p("dbname", "type", "string", "title", "Database Name")
                .p("sslmode", "type", "string", "title", "SSL Mode", "default", "prefer")
                .p("adhoc_query_group", "type", "string", "title", "Query Group for Adhoc Queries", "default", "default")
                .p("scheduled_query_group", "type", "string", "title", "Query Group for Scheduled Queries", "default", "default")
                .order("host", "port", "user", "password", "dbname", "sslmode", "adhoc_query_group", "scheduled_query_group")
                .required("dbname", "user", "password", "host", "port")
                .secret("password")
                .build()));
    add(into, new RunnerType(
        "redshift_iam",
        "Redshift (with IAM User/Role)",
        false,
        "sql",
        true,
        true,
        Sql.LimitStyle.TRAILING,
        "SELECT 1",
        Transport.of("redshift_iam"),
        schema()
                .p("rolename", "type", "string", "title", "IAM Role Name")
                .p("aws_region", "type", "string", "title", "AWS Region")
                .p("aws_access_key_id", "type", "string", "title", "AWS Access Key ID")
                .p("aws_secret_access_key", "type", "string", "title", "AWS Secret Access Key")
                .p("clusterid", "type", "string", "title", "Redshift Cluster ID")
                .p("user", "type", "string")
                .p("host", "type", "string")
                .p("port", "type", "number")
                .p("dbname", "type", "string", "title", "Database Name")
                .p("sslmode", "type", "string", "title", "SSL Mode", "default", "prefer")
                .p("adhoc_query_group", "type", "string", "title", "Query Group for Adhoc Queries", "default", "default")
                .p("scheduled_query_group", "type", "string", "title", "Query Group for Scheduled Queries", "default", "default")
                .order("rolename", "aws_region", "aws_access_key_id", "aws_secret_access_key", "clusterid", "host", "port", "user", "dbname", "sslmode", "adhoc_query_group", "scheduled_query_group")
                .required("dbname", "user", "host", "port", "aws_region")
                .secret("aws_secret_access_key")
                .build()));
    add(into, new RunnerType(
        "results",
        "Query Results",
        false,
        "sql",
        false,
        false,
        Sql.LimitStyle.TRAILING,
        "SELECT 1",
        Transport.of("results"),
        schema()
                .build()));
    add(into, new RunnerType(
        "risingwave",
        "RisingWave",
        false,
        "sql",
        true,
        true,
        Sql.LimitStyle.TRAILING,
        "SELECT 1",
        Transport.of("risingwave"),
        schema()
                .p("user", "type", "string")
                .p("password", "type", "string")
                .p("host", "type", "string", "default", "127.0.0.1")
                .p("port", "type", "number", "default", 5432)
                .p("dbname", "type", "string", "title", "Database Name")
                .p("dsn", "type", "string", "default", "application_name=redash", "title", "Parameters")
                .p("sslmode", "type", "string", "title", "SSL Mode", "default", "prefer", "extendedEnum", options(option("disable", "Disable"), option("allow", "Allow"), option("prefer", "Prefer"), option("require", "Require"), option("verify-ca", "Verify CA"), option("verify-full", "Verify Full")))
                .p("sslrootcertFile", "type", "string", "title", "SSL Root Certificate")
                .p("sslcertFile", "type", "string", "title", "SSL Client Certificate")
                .p("sslkeyFile", "type", "string", "title", "SSL Client Key")
                .order("host", "port", "user", "password")
                .required("dbname")
                .secret("password", "sslrootcertFile", "sslcertFile", "sslkeyFile")
                .extra("sslmode", "sslrootcertFile", "sslcertFile", "sslkeyFile")
                .build()));
    add(into, new RunnerType(
        "rockset",
        "Rockset",
        false,
        "sql",
        true,
        true,
        Sql.LimitStyle.TRAILING,
        "SELECT 1",
        Transport.of("rockset"),
        schema()
                .p("api_server", "type", "string", "title", "API Server", "default", "https://api.rs2.usw2.rockset.com")
                .p("api_key", "title", "API Key", "type", "string")
                .p("vi_id", "title", "Virtual Instance ID", "type", "string")
                .order("api_key", "api_server", "vi_id")
                .required("api_server", "api_key")
                .secret("api_key")
                .build()));
    add(into, new RunnerType(
        "salesforce",
        "Salesforce",
        false,
        "sql",
        false,
        false,
        Sql.LimitStyle.TRAILING,
        null,
        Transport.of("salesforce"),
        schema()
                .p("username", "type", "string")
                .p("password", "type", "string")
                .p("token", "type", "string", "title", "Security Token")
                .p("sandbox", "type", "boolean")
                .p("api_version", "type", "string", "title", "Salesforce API Version", "default", "38.0")
                .required("username", "password")
                .secret("password", "token")
                .build()));
    add(into, new RunnerType(
        "scylla",
        "ScyllaDB",
        false,
        "sql",
        true,
        false,
        Sql.LimitStyle.TRAILING,
        "SELECT dateof(now()) FROM system.local",
        Transport.of("scylla"),
        schema()
                .p("host", "type", "string")
                .p("port", "type", "number", "default", 9042)
                .p("keyspace", "type", "string", "title", "Keyspace name")
                .p("username", "type", "string", "title", "Username")
                .p("password", "type", "string", "title", "Password")
                .p("protocol", "type", "number", "title", "Protocol Version", "default", 3)
                .p("timeout", "type", "number", "title", "Timeout", "default", 10)
                .p("useSsl", "type", "boolean", "title", "Use SSL", "default", false)
                .p("sslCertificateFile", "type", "string", "title", "SSL Certificate File")
                .p("sslProtocol", "type", "string", "title", "SSL Protocol", "enum", List.of("PROTOCOL_SSLv23", "PROTOCOL_TLS", "PROTOCOL_TLS_CLIENT", "PROTOCOL_TLS_SERVER", "PROTOCOL_TLSv1", "PROTOCOL_TLSv1_1", "PROTOCOL_TLSv1_2"))
                .required("keyspace", "host", "useSsl")
                .secret("sslCertificateFile")
                .build()));
    add(into, new RunnerType(
        "snowflake",
        "Snowflake",
        false,
        "sql",
        true,
        true,
        Sql.LimitStyle.TRAILING,
        "SELECT 1",
        Transport.of("snowflake"),
        schema()
                .p("account", "type", "string")
                .p("user", "type", "string")
                .p("password", "type", "string")
                .p("private_key_File", "type", "string")
                .p("private_key_pwd", "type", "string")
                .p("warehouse", "type", "string")
                .p("database", "type", "string")
                .p("region", "type", "string", "default", "us-west")
                .p("lower_case_columns", "type", "boolean", "title", "Lower Case Column Names in Results", "default", false)
                .p("host", "type", "string")
                .order("account", "user", "password", "private_key_File", "private_key_pwd", "warehouse", "database", "region", "host")
                .required("user", "account", "database", "warehouse")
                .secret("password", "private_key_File", "private_key_pwd")
                .extra("host")
                .build()));
    add(into, new RunnerType(
        "sparql_endpoint",
        "SPARQL Endpoint",
        false,
        "sql",
        true,
        false,
        Sql.LimitStyle.TRAILING,
        "SELECT ?noop WHERE {BIND('noop' as ?noop)}",
        Transport.of("sparql_endpoint"),
        schema()
                .p("SPARQL_BASE_URI", "type", "string", "title", "Base URL")
                .p("SSL_VERIFY", "type", "boolean", "title", "Verify SSL certificates for API requests", "default", true)
                .required("SPARQL_BASE_URI")
                .declaresEmpty("secret")
                .extra("SSL_VERIFY")
                .build()));
    add(into, new RunnerType(
        "sqlite",
        "Sqlite",
        false,
        "sql",
        true,
        true,
        Sql.LimitStyle.TRAILING,
        "pragma quick_check",
        Transport.of("sqlite"),
        schema()
                .p("dbpath", "type", "string", "title", "Database Path")
                .required("dbpath")
                .build()));
    add(into, new RunnerType(
        "tinybird",
        "Tinybird",
        false,
        "sql",
        true,
        true,
        Sql.LimitStyle.TRAILING,
        "SELECT count() FROM tinybird.pipe_stats LIMIT 1",
        Transport.of("tinybird"),
        schema()
                .p("url", "type", "string", "default", "https://api.tinybird.co")
                .p("token", "type", "string", "title", "Auth Token")
                .p("timeout", "type", "number", "title", "Request Timeout", "default", 30)
                .p("verify", "type", "boolean", "title", "Verify SSL certificate", "default", true)
                .order("url", "token")
                .required("token")
                .secret("token")
                .extra("timeout", "verify")
                .build()));
    add(into, new RunnerType(
        "treasuredata",
        "TreasureData",
        false,
        "sql",
        false,
        false,
        Sql.LimitStyle.TRAILING,
        "SELECT 1",
        Transport.of("treasuredata"),
        schema()
                .p("endpoint", "type", "string")
                .p("apikey", "type", "string")
                .p("type", "type", "string")
                .p("db", "type", "string", "title", "Database Name")
                .p("get_schema", "type", "boolean", "title", "Auto Schema Retrieval", "default", false)
                .required("apikey", "db")
                .secret("apikey")
                .build()));
    add(into, new RunnerType(
        "trino",
        "Trino",
        false,
        "sql",
        true,
        true,
        Sql.LimitStyle.TRAILING,
        "SELECT 1",
        Transport.of("trino"),
        schema()
                .p("protocol", "type", "string", "default", "http")
                .p("host", "type", "string")
                .p("port", "type", "number")
                .p("username", "type", "string")
                .p("password", "type", "string")
                .p("source", "type", "string", "default", "redash")
                .p("client_tags", "type", "string", "title", "Client tags (comma separated)")
                .p("catalog", "type", "string")
                .p("schema", "type", "string")
                .p("impersonation", "type", "boolean", "default", false)
                .p("impersonationField", "type", "string", "title", "Impersonation User Attribute", "default", "email", "extendedEnum", options(option("email", "Email"), option("name", "Name")))
                .order("protocol", "host", "port", "username", "password", "source", "client_tags", "catalog", "schema", "impersonation")
                .required("host", "username")
                .secret("password")
                .extra("client_tags", "impersonation", "impersonationField")
                .build()));
    add(into, new RunnerType(
        "uptycs",
        "Uptycs",
        false,
        "sql",
        false,
        true,
        Sql.LimitStyle.TRAILING,
        "SELECT 1",
        Transport.of("uptycs"),
        schema()
                .p("url", "type", "string")
                .p("customer_id", "type", "string")
                .p("key", "type", "string")
                .p("verify_ssl", "type", "boolean", "default", true, "title", "Verify SSL Certificates")
                .p("secret", "type", "string")
                .order("url", "customer_id", "key", "secret")
                .required("url", "customer_id", "key", "secret")
                .secret("secret", "key")
                .build()));
    add(into, new RunnerType(
        "url",
        "Url",
        true,
        "sql",
        false,
        false,
        Sql.LimitStyle.TRAILING,
        null,
        Transport.of("url"),
        schema()
                .p("url", "type", "string", "title", "URL base path")
                .p("username", "type", "string", "title", "HTTP Basic Auth Username")
                .p("password", "type", "string", "title", "HTTP Basic Auth Password")
                .order("url", "username", "password")
                .secret("password")
                .build()));
    add(into, new RunnerType(
        "yandex_appmetrika",
        "Yandex AppMetrica",
        false,
        "yaml",
        false,
        true,
        Sql.LimitStyle.TRAILING,
        null,
        Transport.of("yandex_appmetrika"),
        schema()
                .p("token", "type", "string", "title", "OAuth Token")
                .required("token")
                .secret("token")
                .build()));
    add(into, new RunnerType(
        "yandex_disk",
        "Yandex Disk",
        false,
        "yaml",
        false,
        true,
        Sql.LimitStyle.TRAILING,
        null,
        Transport.of("yandex_disk"),
        schema()
                .p("token", "type", "string", "title", "OAuth Token")
                .required("token")
                .secret("token")
                .build()));
    add(into, new RunnerType(
        "yandex_metrika",
        "Yandex Metrica",
        false,
        "yaml",
        false,
        true,
        Sql.LimitStyle.TRAILING,
        null,
        Transport.of("yandex_metrika"),
        schema()
                .p("token", "type", "string", "title", "OAuth Token")
                .required("token")
                .secret("token")
                .build()));
  }
}


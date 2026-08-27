package io.akka.redash.domain;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Every setting the process reads from its environment, with the source's own defaults
 * (SPEC-001 R1 to R3).
 *
 * <p>The two parsers are worth reading rather than skimming. A boolean accepts eight words
 * and **raises on anything else, including an empty string** — so a deployment that sets a
 * flag to `""` does not quietly get the default, it fails to start. A list splits on commas
 * and drops empty entries but does **not** trim, so a value written with spaces after the
 * commas produces entries with those spaces in them.
 */
public final class Settings {

  private final Map<String, String> environment;

  public Settings(Map<String, String> environment) {
    this.environment = environment;
  }

  public static Settings fromEnvironment() {
    return new Settings(System.getenv());
  }

  // ------------------------------------------------------------------ parsers

  public static boolean parseBoolean(String value) {
    var text = value == null ? "" : value.strip().toLowerCase(Locale.ROOT);
    return switch (text) {
      case "yes", "true", "on", "1" -> true;
      case "no", "false", "off", "0", "none" -> false;
      default -> throw new IllegalArgumentException("Invalid boolean value '" + value + "'");
    };
  }

  public static List<String> arrayFromString(String value) {
    var out = new ArrayList<String>();
    for (String part : (value == null ? "" : value).split(",", -1)) {
      if (!part.isEmpty()) {
        out.add(part);
      }
    }
    return out;
  }

  public static Set<String> setFromString(String value) {
    return new LinkedHashSet<>(arrayFromString(value));
  }

  // ------------------------------------------------------------------ readers

  public String text(String name, String fallback) {
    var value = environment.get(name);
    return value == null ? fallback : value;
  }

  public boolean flag(String name, String fallback) {
    return parseBoolean(text(name, fallback));
  }

  public int number(String name, int fallback) {
    var value = environment.get(name);
    if (value == null || value.isEmpty()) {
      return fallback;
    }
    return Integer.parseInt(value.strip());
  }

  public List<String> list(String name, String fallback) {
    return arrayFromString(text(name, fallback));
  }

  // ------------------------------------------------------------------ the settings

  public String secretKey() {
    var value = environment.get("REDASH_COOKIE_SECRET");
    if (value == null) {
      throw new IllegalStateException(
          "You must set the REDASH_COOKIE_SECRET environment variable. Visit"
              + " http://redash.io/help/open-source/admin-guide/secrets for more information.");
    }
    return value;
  }

  public String dataSourceSecretKey() {
    return text("REDASH_SECRET_KEY", secretKey());
  }

  public boolean multiOrg() {
    return flag("REDASH_MULTI_ORG", "false");
  }

  public String host() {
    return text("REDASH_HOST", "");
  }

  public String authType() {
    return text("REDASH_AUTH_TYPE", "api_key");
  }

  public int invitationTokenMaxAge() {
    return number("REDASH_INVITATION_TOKEN_MAX_AGE", 60 * 60 * 24 * 7);
  }

  public int sessionExpiryTime() {
    return number("REDASH_SESSION_EXPIRY_TIME", 60 * 60 * 6);
  }

  public String sessionCookieName() {
    return text("REDASH_SESSION_COOKIE_NAME", "session");
  }

  public int rememberCookieDuration() {
    return number("REDASH_REMEMBER_COOKIE_DURATION", 60 * 60 * 24 * 31);
  }

  public boolean queryResultsCleanupEnabled() {
    return flag("REDASH_QUERY_RESULTS_CLEANUP_ENABLED", "true");
  }

  public int queryResultsCleanupCount() {
    return number("REDASH_QUERY_RESULTS_CLEANUP_COUNT", 100);
  }

  public int queryResultsCleanupMaxAgeDays() {
    return number("REDASH_QUERY_RESULTS_CLEANUP_MAX_AGE", 7);
  }

  public boolean queryResultsExpiredTtlEnabled() {
    return flag("REDASH_QUERY_RESULTS_EXPIRED_TTL_ENABLED", "false");
  }

  public int queryResultsExpiredTtl() {
    return number("REDASH_QUERY_RESULTS_EXPIRED_TTL", 86400);
  }

  public int schemasRefreshSchedule() {
    return number("REDASH_SCHEMAS_REFRESH_SCHEDULE", 30);
  }

  public int schemasRefreshTimeout() {
    return number("REDASH_SCHEMAS_REFRESH_TIMEOUT", 300);
  }

  public int scheduledQueryTimeLimit() {
    return number("REDASH_SCHEDULED_QUERY_TIME_LIMIT", -1);
  }

  public int adhocQueryTimeLimit() {
    return number("REDASH_ADHOC_QUERY_TIME_LIMIT", -1);
  }

  public int jobExpiryTime() {
    return number("REDASH_JOB_EXPIRY_TIME", 3600 * 12);
  }

  public int sendFailureEmailInterval() {
    return number("REDASH_SEND_FAILURE_EMAIL_INTERVAL", 60);
  }

  public int maxFailureReportsPerQuery() {
    return number("REDASH_MAX_FAILURE_REPORTS_PER_QUERY", 100);
  }

  public String alertsDefaultMailSubjectTemplate() {
    return text("REDASH_ALERTS_DEFAULT_MAIL_SUBJECT_TEMPLATE",
        "Alert: {alert_name} changed status to {state}");
  }

  public boolean versionCheck() {
    return flag("REDASH_VERSION_CHECK", "true");
  }

  public boolean featureDisableRefreshQueries() {
    return flag("REDASH_FEATURE_DISABLE_REFRESH_QUERIES", "false");
  }

  public boolean featureShowQueryResultsCount() {
    return flag("REDASH_FEATURE_SHOW_QUERY_RESULTS_COUNT", "true");
  }

  public boolean featureAllowCustomJsVisualizations() {
    return flag("REDASH_FEATURE_ALLOW_CUSTOM_JS_VISUALIZATIONS", "true");
  }

  public boolean featureAutoPublishNamedQueries() {
    return flag("REDASH_FEATURE_AUTO_PUBLISH_NAMED_QUERIES", "true");
  }

  public boolean featureExtendedAlertOptions() {
    return flag("REDASH_FEATURE_EXTENDED_ALERT_OPTIONS", "false");
  }

  public boolean allowScriptsInUserInput() {
    return flag("REDASH_ALLOW_SCRIPTS_IN_USER_INPUT", "false");
  }

  public boolean allowParametersInEmbeds() {
    return flag("REDASH_ALLOW_PARAMETERS_IN_EMBEDS", "false");
  }

  public boolean enforceCsrf() {
    return flag("REDASH_ENFORCE_CSRF", "false");
  }

  public int csrfTimeLimit() {
    return number("REDASH_CSRF_TIME_LIMIT", 3600 * 6);
  }

  public int pageSize() {
    return number("REDASH_PAGE_SIZE", 20);
  }

  public List<Integer> pageSizeOptions() {
    return numbers("REDASH_PAGE_SIZE_OPTIONS", "5,10,20,50,100");
  }

  public int tableCellMaxJsonSize() {
    return number("REDASH_TABLE_CELL_MAX_JSON_SIZE", 50000);
  }

  public List<Integer> dashboardRefreshIntervals() {
    return numbers("REDASH_DASHBOARD_REFRESH_INTERVALS", "60,300,600,1800,3600,43200,86400");
  }

  public List<Integer> queryRefreshIntervals() {
    return numbers("REDASH_QUERY_REFRESH_INTERVALS",
        "60, 300, 600, 900, 1800, 3600, 7200, 10800, 14400, 18000, 21600, 25200, 28800, 32400,"
            + " 36000, 39600, 43200, 86400, 604800, 1209600, 2592000");
  }

  public Set<String> blockedDomains() {
    return setFromString(text("REDASH_BLOCKED_DOMAINS", "qq.com"));
  }

  public boolean googleOauthEnabled() {
    return !text("REDASH_GOOGLE_CLIENT_ID", "").isEmpty()
        && !text("REDASH_GOOGLE_CLIENT_SECRET", "").isEmpty();
  }

  public boolean ldapLoginEnabled() {
    return flag("REDASH_LDAP_LOGIN_ENABLED", "false");
  }

  public boolean remoteUserLoginEnabled() {
    return flag("REDASH_REMOTE_USER_LOGIN_ENABLED", "false");
  }

  public String remoteUserHeader() {
    return text("REDASH_REMOTE_USER_HEADER", "X-Forwarded-Remote-User");
  }

  public boolean emailServerIsConfigured() {
    return environment.get("REDASH_MAIL_DEFAULT_SENDER") != null;
  }

  public String contentSecurityPolicy() {
    return text("REDASH_CONTENT_SECURITY_POLICY",
        "default-src 'self'; style-src 'self' 'unsafe-inline'; script-src 'self' 'unsafe-eval';"
            + " font-src 'self' data:; img-src 'self' http: https: data: blob:;"
            + " object-src 'none'; frame-ancestors 'none'; frame-src redash.io;");
  }

  public String frameOptions() {
    return text("REDASH_FRAME_OPTIONS", "deny");
  }

  public String referrerPolicy() {
    return text("REDASH_REFERRER_POLICY", "strict-origin-when-cross-origin");
  }

  public Set<String> accessControlAllowOrigin() {
    return setFromString(text("REDASH_CORS_ACCESS_CONTROL_ALLOW_ORIGIN", ""));
  }

  public boolean accessControlAllowCredentials() {
    return flag("REDASH_CORS_ACCESS_CONTROL_ALLOW_CREDENTIALS", "false");
  }

  public String accessControlRequestMethod() {
    return text("REDASH_CORS_ACCESS_CONTROL_REQUEST_METHOD", "GET, POST, PUT");
  }

  public String accessControlAllowHeaders() {
    return text("REDASH_CORS_ACCESS_CONTROL_ALLOW_HEADERS", "Content-Type");
  }


  // ------------------------------------------------------------------ the module lists

  /**
   * The query runner modules a default deployment asks for, in the original's own order.
   *
   * <p>Sixty-six of them, registering seventy-five types: a module can register more than
   * one, and `redash.query_runner.elasticsearch2` registers three. Three modules that exist
   * in the source are not in this list at all - `python`, `script` and `big_query_gce` - so
   * a default deployment has no `python` runner (question-log row 28).
   */
  public static final List<String> DEFAULT_QUERY_RUNNER_MODULES = List.of(
      "redash.query_runner.athena",
      "redash.query_runner.big_query",
      "redash.query_runner.google_spreadsheets",
      "redash.query_runner.graphite",
      "redash.query_runner.mongodb",
      "redash.query_runner.couchbase",
      "redash.query_runner.mysql",
      "redash.query_runner.pg",
      "redash.query_runner.url",
      "redash.query_runner.influx_db",
      "redash.query_runner.influx_db_v2",
      "redash.query_runner.elasticsearch",
      "redash.query_runner.elasticsearch2",
      "redash.query_runner.amazon_elasticsearch",
      "redash.query_runner.trino",
      "redash.query_runner.presto",
      "redash.query_runner.pinot",
      "redash.query_runner.databricks",
      "redash.query_runner.hive_ds",
      "redash.query_runner.impala_ds",
      "redash.query_runner.vertica",
      "redash.query_runner.clickhouse",
      "redash.query_runner.tinybird",
      "redash.query_runner.yandex_metrica",
      "redash.query_runner.yandex_disk",
      "redash.query_runner.rockset",
      "redash.query_runner.treasuredata",
      "redash.query_runner.sqlite",
      "redash.query_runner.mssql",
      "redash.query_runner.mssql_odbc",
      "redash.query_runner.memsql_ds",
      "redash.query_runner.jql",
      "redash.query_runner.google_analytics",
      "redash.query_runner.axibase_tsd",
      "redash.query_runner.salesforce",
      "redash.query_runner.query_results",
      "redash.query_runner.prometheus",
      "redash.query_runner.db2",
      "redash.query_runner.druid",
      "redash.query_runner.kylin",
      "redash.query_runner.drill",
      "redash.query_runner.uptycs",
      "redash.query_runner.snowflake",
      "redash.query_runner.phoenix",
      "redash.query_runner.json_ds",
      "redash.query_runner.cass",
      "redash.query_runner.dgraph",
      "redash.query_runner.azure_kusto",
      "redash.query_runner.exasol",
      "redash.query_runner.cloudwatch",
      "redash.query_runner.cloudwatch_insights",
      "redash.query_runner.corporate_memory",
      "redash.query_runner.sparql_endpoint",
      "redash.query_runner.excel",
      "redash.query_runner.csv",
      "redash.query_runner.databend",
      "redash.query_runner.nz",
      "redash.query_runner.arango",
      "redash.query_runner.google_analytics4",
      "redash.query_runner.google_search_console",
      "redash.query_runner.ignite",
      "redash.query_runner.oracle",
      "redash.query_runner.e6data",
      "redash.query_runner.risingwave",
      "redash.query_runner.d1",
      "redash.query_runner.duckdb");

  /** The destination modules a default deployment asks for, in the original's own order. */
  public static final List<String> DEFAULT_DESTINATION_MODULES = List.of(
      "redash.destinations.email",
      "redash.destinations.slack",
      "redash.destinations.webhook",
      "redash.destinations.discord",
      "redash.destinations.mattermost",
      "redash.destinations.chatwork",
      "redash.destinations.pagerduty",
      "redash.destinations.hangoutschat",
      "redash.destinations.microsoft_teams_webhook",
      "redash.destinations.asana",
      "redash.destinations.webex",
      "redash.destinations.datadog");

  public List<String> enabledQueryRunnerModules() {
    return list("REDASH_ENABLED_QUERY_RUNNERS", String.join(",", DEFAULT_QUERY_RUNNER_MODULES));
  }

  public List<String> additionalQueryRunnerModules() {
    return list("REDASH_ADDITIONAL_QUERY_RUNNERS", "");
  }

  public List<String> disabledQueryRunnerModules() {
    return list("REDASH_DISABLED_QUERY_RUNNERS", "");
  }

  public List<String> enabledDestinationModules() {
    return list("REDASH_ENABLED_DESTINATIONS", String.join(",", DEFAULT_DESTINATION_MODULES));
  }

  public List<String> additionalDestinationModules() {
    return list("REDASH_ADDITIONAL_DESTINATIONS", "");
  }

  // ------------------------------------------------------------------ the response headers

  /**
   * Whether the content security policy is reported rather than enforced.
   *
   * <p>With it on, the two policy headers are named `Content-Security-Policy-Report-Only`
   * and `X-Content-Security-Policy-Report-Only` and the enforcing pair is not sent at all —
   * checked by running the original under each setting (`probes/probe_20_security.py`).
   */
  public boolean contentSecurityPolicyReportOnly() {
    return flag("REDASH_CONTENT_SECURITY_POLICY_REPORT_ONLY", "false");
  }

  /**
   * Where policy violations are reported to.
   *
   * <p>Appended to the policy as `; report-uri <address>`, whether or not the policy is being
   * reported rather than enforced — which is not what the library's own documentation says,
   * and is what running the original under both settings showed.
   */
  public String contentSecurityPolicyReportUri() {
    return text("REDASH_CONTENT_SECURITY_POLICY_REPORT_URI", "");
  }

  /** The policy directives a per-response nonce is added to. */
  public List<String> contentSecurityPolicyNonceIn() {
    return list("REDASH_CONTENT_SECURITY_POLICY_NONCE_IN", "");
  }

  /** Whether `X-Download-Options: noopen` is sent. */
  public boolean enforceFileSave() {
    return flag("REDASH_ENFORCE_FILE_SAVE", "true");
  }

  public String featurePolicy() {
    return text("REDASH_FEATURE_POLICY", "");
  }

  /**
   * The address appended to `X-Frame-Options`, and only when the option is exactly
   * `ALLOW-FROM` in capitals. redash's own default for the option is lower-case `deny`, and
   * a deployment writing lower-case `allow-from` gets that word alone with no address after
   * it — which is what the original does and what was measured.
   */
  public String frameOptionsAllowFrom() {
    return text("REDASH_FRAME_OPTIONS_ALLOW_FROM", "");
  }

  public boolean enforceHttps() {
    return flag("REDASH_ENFORCE_HTTPS", "false");
  }

  public boolean enforceHttpsPermanent() {
    return flag("REDASH_ENFORCE_HTTPS_PERMANENT", "false");
  }

  /** Defaults to whatever https enforcement is set to, which is the source's own default. */
  public boolean hstsEnabled() {
    return flag("REDASH_HSTS_ENABLED", String.valueOf(enforceHttps()));
  }

  public boolean hstsPreload() {
    return flag("REDASH_HSTS_PRELOAD", "false");
  }

  public int hstsMaxAge() {
    return number("REDASH_HSTS_MAX_AGE", 31556926);
  }

  public boolean hstsIncludeSubdomains() {
    return flag("REDASH_HSTS_INCLUDE_SUBDOMAINS", "false");
  }

  // ------------------------------------------------------------------ the cookies

  public boolean sessionCookieSecure() {
    return flag("REDASH_SESSION_COOKIE_SECURE", "false");
  }

  public boolean sessionCookieHttpOnly() {
    return flag("REDASH_SESSION_COOKIE_HTTPONLY", "true");
  }

  public boolean rememberCookieSecure() {
    return flag("REDASH_REMEMBER_COOKIE_SECURE", "false");
  }

  public boolean rememberCookieHttpOnly() {
    return flag("REDASH_REMEMBER_COOKIE_HTTPONLY", "true");
  }

  /** Whether the cross-site token's own cookie is marked secure. */
  public boolean cookiesSecure() {
    return flag("REDASH_COOKIES_SECURE", "false");
  }

  // ------------------------------------------------------------------ rate limiting

  public boolean rateLimitEnabled() {
    return flag("REDASH_RATELIMIT_ENABLED", "false");
  }

  public String throttleLoginPattern() {
    return text("REDASH_THROTTLE_LOGIN_PATTERN", "50/hour");
  }

  public String throttlePasswordResetPattern() {
    return text("REDASH_THROTTLE_PASS_RESET_PATTERN", "10/hour");
  }

  /** Where the rate limiter keeps its counters. No counterpart here; read and reported. */
  public String limiterStorage() {
    return text("REDASH_LIMITER_STORAGE", "redis://redis:6379/0?decode_responses=True");
  }

  // ------------------------------------------------------------------ external directories

  public String ldapUrl() {
    return text("REDASH_LDAP_URL", null);
  }

  public boolean ldapUseSsl() {
    return flag("REDASH_LDAP_USE_SSL", "false");
  }

  public String ldapAuthMethod() {
    return text("REDASH_LDAP_AUTH_METHOD", "SIMPLE");
  }

  public String ldapBindDn() {
    return text("REDASH_LDAP_BIND_DN", null);
  }

  public String ldapBindDnPassword() {
    return text("REDASH_LDAP_BIND_DN_PASSWORD", "");
  }

  public String ldapDisplayNameKey() {
    return text("REDASH_LDAP_DISPLAY_NAME_KEY", "displayName");
  }

  public String ldapEmailKey() {
    return text("REDASH_LDAP_EMAIL_KEY", "mail");
  }

  public String ldapCustomUsernamePrompt() {
    return text("REDASH_LDAP_CUSTOM_USERNAME_PROMPT", "LDAP/AD/SSO username:");
  }

  /**
   * Read from `REDASH_SEARCH_DN` first and `REDASH_LDAP_SEARCH_DN` after it, which is the
   * order the source reads them in.
   */
  public String ldapSearchDn() {
    var legacy = text("REDASH_SEARCH_DN", null);
    return legacy != null ? legacy : text("REDASH_LDAP_SEARCH_DN", null);
  }

  public String ldapSearchTemplate() {
    return text("REDASH_LDAP_SEARCH_TEMPLATE", "(cn=%(username)s)");
  }

  public String samlEncryptionCertPath() {
    return text("REDASH_SAML_ENCRYPTION_CERT_PATH", "");
  }

  public String samlEncryptionPemPath() {
    return text("REDASH_SAML_ENCRYPTION_PEM_PATH", "");
  }

  public String samlSchemeOverride() {
    return text("REDASH_SAML_SCHEME_OVERRIDE", "");
  }

  public String googleOauthSchemeOverride() {
    return text("REDASH_GOOGLE_OAUTH_SCHEME_OVERRIDE", "");
  }

  // ------------------------------------------------------------------ outbound requests

  /** Whether a runner or a destination may reach a private address. */
  public boolean enforcePrivateAddressBlock() {
    return flag("REDASH_ENFORCE_PRIVATE_IP_BLOCK", "true");
  }

  public boolean requestsAllowRedirects() {
    return flag("REDASH_REQUESTS_ALLOW_REDIRECTS", "false");
  }

  /** Every recorded event is also posted to each of these. */
  public List<String> eventReportingWebhooks() {
    return list("REDASH_EVENT_REPORTING_WEBHOOKS", "");
  }

  /** How many proxies in front of this are trusted to have set the forwarded address. */
  public int proxiesCount() {
    return number("REDASH_PROXIES_COUNT", 1);
  }

  // ------------------------------------------------------------------ mail

  public String mailServer() {
    return text("REDASH_MAIL_SERVER", "localhost");
  }

  public int mailPort() {
    return number("REDASH_MAIL_PORT", 25);
  }

  public boolean mailUseTls() {
    return flag("REDASH_MAIL_USE_TLS", "false");
  }

  public boolean mailUseSsl() {
    return flag("REDASH_MAIL_USE_SSL", "false");
  }

  public String mailUsername() {
    return text("REDASH_MAIL_USERNAME", null);
  }

  public String mailPassword() {
    return text("REDASH_MAIL_PASSWORD", null);
  }

  public String mailDefaultSender() {
    return text("REDASH_MAIL_DEFAULT_SENDER", null);
  }

  /** How many messages one connection carries before it is reopened. */
  public Integer mailMaxEmails() {
    var value = environment.get("REDASH_MAIL_MAX_EMAILS");
    return value == null || value.isEmpty() ? null : Integer.parseInt(value.strip());
  }

  public boolean mailAsciiAttachments() {
    return flag("REDASH_MAIL_ASCII_ATTACHMENTS", "false");
  }

  /** The file the default alert body is rendered from. */
  public String alertsDefaultMailBodyTemplateFile() {
    return text("REDASH_ALERTS_DEFAULT_MAIL_BODY_TEMPLATE_FILE",
        staticAssetsPath() + "templates/emails/alert.html");
  }

  // ------------------------------------------------------------------ runner defaults

  public int bigQueryHttpTimeout() {
    return number("REDASH_BIGQUERY_HTTP_TIMEOUT", 600);
  }

  public boolean kylinAcceptPartial() {
    return flag("REDASH_KYLIN_ACCEPT_PARTIAL", "false");
  }

  public int kylinOffset() {
    return number("REDASH_KYLIN_OFFSET", 0);
  }

  public int kylinLimit() {
    return number("REDASH_KYLIN_LIMIT", 50000);
  }

  /** Whether a schema fetch also counts the rows of each table. */
  public boolean schemaRunTableSizeCalculations() {
    return flag("REDASH_SCHEMA_RUN_TABLE_SIZE_CALCULATIONS", "false");
  }

  // ------------------------------------------------------------------ the process

  public String logLevel() {
    return text("REDASH_LOG_LEVEL", "INFO");
  }

  public String logFormat() {
    return text("REDASH_LOG_FORMAT",
        "[%(asctime)s][PID:%(process)d][%(levelname)s][%(name)s] %(message)s");
  }

  public String logPrefix() {
    return text("REDASH_LOG_PREFIX", "");
  }

  public boolean logStdout() {
    return flag("REDASH_LOG_STDOUT", "false");
  }

  public String rqWorkerJobLogFormat() {
    return text("REDASH_RQ_WORKER_JOB_LOG_FORMAT",
        "[%(asctime)s][PID:%(process)d][%(levelname)s][%(name)s] job.func_name=%(job_func_name)s"
            + " job.id=%(job_id)s %(message)s");
  }

  public String databaseUrl() {
    return text("REDASH_DATABASE_URL", "postgresql:///postgres?host=/var/run/postgresql");
  }

  public String redisUrl() {
    return text("REDASH_REDIS_URL", "redis://localhost:6379/0");
  }

  public String staticAssetsPath() {
    return text("REDASH_STATIC_ASSETS_PATH", "../client/dist/");
  }

  public String flaskTemplatePath() {
    return text("REDASH_FLASK_TEMPLATE_PATH", staticAssetsPath());
  }

  public String dynamicSettingsModule() {
    return text("REDASH_DYNAMIC_SETTINGS_MODULE", "redash.settings.dynamic_settings");
  }

  public int jobDefaultFailureTtl() {
    return number("REDASH_JOB_DEFAULT_FAILURE_TTL", 7 * 24 * 60 * 60);
  }

  public String sentryDsn() {
    return text("REDASH_SENTRY_DSN", "");
  }

  public String sentryEnvironment() {
    return text("REDASH_SENTRY_ENVIRONMENT", null);
  }

  public String statsdHost() {
    return text("REDASH_STATSD_HOST", "127.0.0.1");
  }

  public int statsdPort() {
    return number("REDASH_STATSD_PORT", 8125);
  }

  public String statsdPrefix() {
    return text("REDASH_STATSD_PREFIX", "redash");
  }

  public boolean statsdUseTags() {
    return flag("REDASH_STATSD_USE_TAGS", "false");
  }

  private List<Integer> numbers(String name, String fallback) {
    var out = new ArrayList<Integer>();
    for (String value : list(name, fallback)) {
      out.add(Integer.parseInt(value.strip()));
    }
    return out;
  }

  // ------------------------------------------------------------------ organisation defaults

  /**
   * The thirty organisation-level settings, with the defaults a fresh install carries. An
   * organisation's own settings map overrides an entry here; a name in neither is an error
   * rather than a null (SPEC-001 R5).
   */
  public Map<String, Object> organizationDefaults() {
    var out = new LinkedHashMap<String, Object>();
    out.put("beacon_consent", null);
    out.put("auth_password_login_enabled", flag("REDASH_PASSWORD_LOGIN_ENABLED", "true"));
    var samlType = text("REDASH_SAML_AUTH_TYPE", "");
    var samlMetadataUrl = text("REDASH_SAML_METADATA_URL", "");
    var samlSsoUrl = text("REDASH_SAML_SSO_URL", "");
    out.put("auth_saml_enabled", "static".equals(samlType)
        ? !samlSsoUrl.isEmpty() && !samlMetadataUrl.isEmpty()
        : !samlMetadataUrl.isEmpty());
    out.put("auth_saml_type", samlType);
    out.put("auth_saml_entity_id", text("REDASH_SAML_ENTITY_ID", ""));
    out.put("auth_saml_metadata_url", samlMetadataUrl);
    out.put("auth_saml_nameid_format", text("REDASH_SAML_NAMEID_FORMAT", ""));
    out.put("auth_saml_sso_url", samlSsoUrl);
    out.put("auth_saml_x509_cert", text("REDASH_SAML_X509_CERT", ""));
    out.put("auth_saml_sp_settings", text("REDASH_SAML_SP_SETTINGS", ""));
    out.put("date_format", text("REDASH_DATE_FORMAT", "DD/MM/YY"));
    out.put("time_format", text("REDASH_TIME_FORMAT", "HH:mm"));
    out.put("integer_format", text("REDASH_INTEGER_FORMAT", "0,0"));
    out.put("float_format", text("REDASH_FLOAT_FORMAT", "0,0.00"));
    out.put("thousands_separator", text("REDASH_THOUSANDS_SEPARATOR", ","));
    out.put("decimal_separator", text("REDASH_DECIMAL_SEPARATOR", "."));
    out.put("null_value", text("REDASH_NULL_VALUE", "null"));
    out.put("multi_byte_search_enabled", flag("MULTI_BYTE_SEARCH_ENABLED", "false"));
    out.put("auth_jwt_login_enabled", flag("REDASH_JWT_LOGIN_ENABLED", "false"));
    out.put("auth_jwt_auth_issuer", text("REDASH_JWT_AUTH_ISSUER", ""));
    out.put("auth_jwt_auth_public_certs_url", text("REDASH_JWT_AUTH_PUBLIC_CERTS_URL", ""));
    out.put("auth_jwt_auth_audience", text("REDASH_JWT_AUTH_AUDIENCE", ""));
    out.put("auth_jwt_auth_algorithms",
        List.of(text("REDASH_JWT_AUTH_ALGORITHMS", "HS256,RS256,ES256").split(",")));
    out.put("auth_jwt_auth_cookie_name", text("REDASH_JWT_AUTH_COOKIE_NAME", ""));
    out.put("auth_jwt_auth_header_name", text("REDASH_JWT_AUTH_HEADER_NAME", ""));
    out.put("feature_show_permissions_control",
        flag("REDASH_FEATURE_SHOW_PERMISSIONS_CONTROL", "false"));
    out.put("send_email_on_failed_scheduled_queries",
        flag("REDASH_SEND_EMAIL_ON_FAILED_SCHEDULED_QUERIES", "false"));
    out.put("hide_plotly_mode_bar", flag("HIDE_PLOTLY_MODE_BAR", "false"));
    out.put("disable_public_urls", flag("REDASH_DISABLE_PUBLIC_URLS", "false"));
    return out;
  }

  /**
   * The disposable-address domains an invitation is refused for (SPEC-001 R29).
   *
   * <p>The source reads this from a third-party package rather than declaring it, so the
   * list is carried here as a resource exported from a running original — 4,055 domains —
   * and named in `ACKNOWLEDGEMENTS.md` as somebody else's data.
   */
  public static Set<String> disposableDomains() {
    return Held.DISPOSABLE_DOMAINS;
  }

  /** Read once, by the class loader, so two requests cannot both read the file. */
  private static final class Held {
    static final Set<String> DISPOSABLE_DOMAINS = readDisposableDomains();
  }

  private static Set<String> readDisposableDomains() {
    var loaded = new java.util.LinkedHashSet<String>();
    try (var stream = Settings.class.getResourceAsStream("/disposable-email-domains.txt")) {
      if (stream != null) {
        var reader = new java.io.BufferedReader(new java.io.InputStreamReader(stream,
            java.nio.charset.StandardCharsets.UTF_8));
        String line;
        while ((line = reader.readLine()) != null) {
          var trimmed = line.strip();
          if (!trimmed.isEmpty()) {
            loaded.add(trimmed);
          }
        }
      }
    } catch (java.io.IOException e) {
      throw new IllegalStateException("the disposable-domain list could not be read", e);
    }
    return Set.copyOf(loaded);
  }

  /** The version this rebuild reports, which is the version of the source it was built from. */
  public static final String VERSION = "26.08.0-dev";
}

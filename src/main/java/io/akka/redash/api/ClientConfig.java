package io.akka.redash.api;

import io.akka.redash.domain.Json;
import io.akka.redash.domain.Settings;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The document the front end reads before it draws anything (SPEC-001 R4).
 *
 * <p>It is the union of process settings and organisation settings, under the names the
 * front end uses rather than the ones they are stored under. Two of its fields are the
 * organisation's own formats offered as a list of choices, and in the original those lists
 * are built from a Python set — so their **order is not defined** and differs between runs
 * of the same version. The rebuild writes them in a fixed order and the benchmark compares
 * them as sets; that is recorded in the README's differences list rather than papered over.
 */
public final class ClientConfig {

  private ClientConfig() {}

  public static Map<String, Object> of(Settings settings, Map<String, Object> organization,
      Caller caller, String basePath, boolean newVersionAvailable) {
    var org = organizationSettings(settings, organization);
    var out = new LinkedHashMap<String, Object>();

    if (caller != null && !caller.isApiUser() && caller.isAuthenticated()) {
      out.put("newVersionAvailable", newVersionAvailable);
      out.put("version", Settings.VERSION);
    }
    if (caller != null && caller.has("admin") && org.get("beacon_consent") == null) {
      out.put("showBeaconConsentMessage", true);
    }

    out.put("allowScriptsInUserInput", settings.allowScriptsInUserInput());
    out.put("showPermissionsControl", org.get("feature_show_permissions_control"));
    out.put("hidePlotlyModeBar", org.get("hide_plotly_mode_bar"));
    out.put("disablePublicUrls", org.get("disable_public_urls"));
    out.put("multiByteSearchEnabled", org.get("multi_byte_search_enabled"));
    out.put("allowCustomJSVisualizations", settings.featureAllowCustomJsVisualizations());
    out.put("autoPublishNamedQueries", settings.featureAutoPublishNamedQueries());
    out.put("extendedAlertOptions", settings.featureExtendedAlertOptions());
    out.put("mailSettingsMissing", !settings.emailServerIsConfigured());
    out.put("dashboardRefreshIntervals", settings.dashboardRefreshIntervals());
    out.put("queryRefreshIntervals", settings.queryRefreshIntervals());
    out.put("googleLoginEnabled", settings.googleOauthEnabled());
    out.put("ldapLoginEnabled", settings.ldapLoginEnabled());
    out.put("pageSize", (long) settings.pageSize());
    out.put("pageSizeOptions", settings.pageSizeOptions());
    out.put("tableCellMaxJSONSize", (long) settings.tableCellMaxJsonSize());
    out.put("basePath", basePath);

    var dateFormat = String.valueOf(org.get("date_format"));
    var timeFormat = String.valueOf(org.get("time_format"));
    out.put("dateFormat", dateFormat);
    out.put("dateFormatList", distinct(List.of("DD/MM/YY", "MM/DD/YY", "YYYY-MM-DD", dateFormat)));
    out.put("timeFormatList",
        distinct(List.of("HH:mm", "HH:mm:ss", "HH:mm:ss.SSS", timeFormat)));
    out.put("dateTimeFormat", dateFormat + " " + timeFormat);

    out.put("integerFormat", org.get("integer_format"));
    out.put("floatFormat", org.get("float_format"));
    out.put("thousandsSeparator", org.get("thousands_separator"));
    out.put("decimalSeparator", org.get("decimal_separator"));
    out.put("nullValue", org.get("null_value"));
    return out;
  }

  private static List<String> distinct(List<String> values) {
    var out = new ArrayList<String>();
    for (String value : values) {
      if (!out.contains(value)) {
        out.add(value);
      }
    }
    return out;
  }

  /**
   * An organisation's settings: its own where it has them, the process defaults elsewhere
   * (SPEC-001 R5).
   */
  public static Map<String, Object> organizationSettings(Settings settings,
      Map<String, Object> organization) {
    var out = new LinkedHashMap<>(settings.organizationDefaults());
    if (organization != null) {
      var stored = Json.asMap(Json.asMap(organization.get("settings")).get("settings"));
      stored.forEach((name, value) -> {
        if (out.containsKey(name)) {
          out.put(name, value);
        }
      });
    }
    return out;
  }

  /** One setting, refusing a name that is in neither place. */
  public static Object setting(Settings settings, Map<String, Object> organization, String name) {
    var all = organizationSettings(settings, organization);
    if (!all.containsKey(name)) {
      throw new IllegalArgumentException(name);
    }
    return all.get(name);
  }

  /**
   * What `/api/settings/organization` answers: every setting whose value or default is not
   * null, plus the Google domains list, which is kept outside the settings map.
   */
  public static Map<String, Object> asSettingsDocument(Settings settings,
      Map<String, Object> organization) {
    var defaults = settings.organizationDefaults();
    var current = organizationSettings(settings, organization);
    var out = new LinkedHashMap<String, Object>();
    for (var entry : defaults.entrySet()) {
      var value = current.get(entry.getKey());
      if (value == null && entry.getValue() == null) {
        continue;
      }
      out.put(entry.getKey(), value == null ? entry.getValue() : value);
    }
    out.put("auth_google_apps_domains", organization == null
        ? List.of()
        : Json.asMap(organization.get("settings"))
            .getOrDefault("google_apps_domains", List.of()));
    return Json.map("settings", out);
  }
}

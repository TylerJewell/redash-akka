package io.akka.redash.destinations;

import io.akka.redash.domain.Json;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Every alert destination redash registers (SPEC-001 R141, R142, D-5).
 *
 * <p>Twelve, with the schema each one declares. Five deliver: email, webhook, PagerDuty,
 * Datadog and Asana. The other seven post a message into a third party's own chat product,
 * which this run excludes; they stay in the registry because the list is what the front end
 * draws its forms from, and a destination of one of those types can still be created and
 * subscribed to — nothing is delivered to it, and the README says so.
 */
public final class Destinations {

  private Destinations() {}

  /**
   * Built once, by the class loader, rather than filled on first use.
   *
   * <p>A lazily-filled static map is shared mutable state: two requests arriving together
   * on a cold instance both find it empty and both fill it. Holding it in a nested class
   * makes the JVM do the locking, and the map handed out is unmodifiable.
   */
  private static final class Held {
    static final Map<String, DestinationType> TYPES = build();
  }

  private static void add(Map<String, DestinationType> into, DestinationType type) {
    into.put(type.type(), type);
  }

  /**
   * The order `/api/destinations/types` answers in.
   *
   * <p>It is the order `REDASH_ENABLED_DESTINATIONS` defaults to, module for module
   * (`redash/settings/__init__.py`'s `default_destinations`), and the front end draws its
   * picker in exactly this order — so it is a wire format rather than a listing
   * convenience. The declarations below are alphabetical because that is easier to read;
   * this list is what decides what a caller sees.
   */
  static final List<String> ORDER = List.of(
      "email", "slack", "webhook", "discord", "mattermost", "chatwork", "pagerduty",
      "hangouts_chat", "microsoft_teams_webhook", "asana", "webex", "datadog");

  public static Map<String, DestinationType> all() {
    return Held.TYPES;
  }

  private static Map<String, DestinationType> build() {
    var declared = new LinkedHashMap<String, DestinationType>();
    register(declared);
    var ordered = new LinkedHashMap<String, DestinationType>(declared.size());
    for (String type : ORDER) {
      var found = declared.get(type);
      if (found != null) {
        ordered.put(type, found);
      }
    }
    // Anything declared but not in the order list still registers, after the ones that
    // are: a type the source does not ship must not vanish because of a listing order.
    declared.forEach(ordered::putIfAbsent);
    return java.util.Collections.unmodifiableMap(ordered);
  }

  public static DestinationType get(String type) {
    return all().get(type);
  }

  public static Map<String, Object> configurationSchema(String type) {
    var destination = get(type);
    return destination == null ? null : destination.configurationSchema();
  }

  /**
   * The module each type is registered by, which is what the two list settings name.
   *
   * <p>One module to one destination here, unlike the query runners — but named rather than
   * derived, because `hangouts_chat` is registered by `redash.destinations.hangoutschat`.
   */
  private static final Map<String, String> MODULES = Map.ofEntries(
      Map.entry("asana", "redash.destinations.asana"),
      Map.entry("chatwork", "redash.destinations.chatwork"),
      Map.entry("datadog", "redash.destinations.datadog"),
      Map.entry("discord", "redash.destinations.discord"),
      Map.entry("email", "redash.destinations.email"),
      Map.entry("hangouts_chat", "redash.destinations.hangoutschat"),
      Map.entry("mattermost", "redash.destinations.mattermost"),
      Map.entry("microsoft_teams_webhook", "redash.destinations.microsoft_teams_webhook"),
      Map.entry("pagerduty", "redash.destinations.pagerduty"),
      Map.entry("slack", "redash.destinations.slack"),
      Map.entry("webex", "redash.destinations.webex"),
      Map.entry("webhook", "redash.destinations.webhook"));

  /**
   * The destinations a deployment registers, which is what the two list settings decide
   * (SPEC-001 R3). The enabled list defaults to every module the original ships and the
   * additional list is added to it; a module named twice counts once.
   */
  public static Map<String, DestinationType> registered(io.akka.redash.domain.Settings settings) {
    var modules = new java.util.LinkedHashSet<>(settings.enabledDestinationModules());
    modules.addAll(settings.additionalDestinationModules());
    var out = new LinkedHashMap<String, DestinationType>();
    for (Map.Entry<String, DestinationType> entry : all().entrySet()) {
      if (modules.contains(MODULES.get(entry.getKey()))) {
        out.put(entry.getKey(), entry.getValue());
      }
    }
    return java.util.Collections.unmodifiableMap(out);
  }

  /** The list `/api/destinations/types` answers, in the order the registry holds them. */
  public static List<Map<String, Object>> asDocuments(io.akka.redash.domain.Settings settings) {
    var registered = registered(settings);
    var out = new ArrayList<Map<String, Object>>(registered.size());
    for (DestinationType type : registered.values()) {
      out.add(type.asDocument());
    }
    return out;
  }

  private static Schema schema() {
    return new Schema();
  }

  /** The same small builder the data source registry uses, over the same schema shape. */
  static final class Schema {
    private final Map<String, Object> properties = new LinkedHashMap<>();
    private final List<Object> required = new ArrayList<>();
    private final List<Object> secret = new ArrayList<>();
    private Object secretVerbatim;
    private final List<Object> extraOptions = new ArrayList<>();

    Schema p(String name, Object... pairs) {
      var property = new LinkedHashMap<String, Object>();
      for (int i = 0; i + 1 < pairs.length; i += 2) {
        property.put(String.valueOf(pairs[i]), pairs[i + 1]);
      }
      properties.put(name, property);
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

    /**
     * Declare the secret list exactly as the source declares it, whatever shape that is.
     *
     * <p>One destination declares a bare string where the others declare a list, and both
     * the wire format and the masking follow from that shape, so it is carried through
     * rather than tidied into a list of one.
     */
    Schema secretVerbatim(Object declared) {
      this.secretVerbatim = declared;
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
      if (!required.isEmpty()) {
        out.put("required", required);
      }
      if (secretVerbatim != null) {
        out.put("secret", secretVerbatim);
      } else if (!secret.isEmpty()) {
        out.put("secret", secret);
      }
      if (!extraOptions.isEmpty()) {
        out.put("extra_options", extraOptions);
      }
      return out;
    }
  }

  private static void register(Map<String, DestinationType> into) {
    add(into, new DestinationType(
        "asana",
        "Asana",
        "fa-asana",
        true,
        schema()
                .p("pat", "type", "string", "title", "Asana Personal Access Token")
                .p("project_id", "type", "string", "title", "Asana Project ID")
                .required("pat", "project_id")
                .secret("pat")
                .build()));
    add(into, new DestinationType(
        "chatwork",
        "ChatWork",
        "fa-comment",
        false,
        schema()
                .p("api_token", "type", "string", "title", "API Token")
                .p("room_id", "type", "string", "title", "Room ID")
                .p("message_template", "type", "string", "default", "{alert_name} changed state to {new_state}.\\n{alert_url}\\n{query_url}", "title", "Message Template")
                .required("message_template", "api_token", "room_id")
                .secret("api_token")
                .build()));
    add(into, new DestinationType(
        "datadog",
        "Datadog",
        "fa-datadog",
        true,
        schema()
                .p("api_key", "type", "string", "title", "API Key")
                .p("tags", "type", "string", "title", "Tags")
                .p("priority", "type", "string", "default", "normal", "title", "Priority")
                .p("source_type_name", "type", "string", "default", "my_apps", "title", "Source Type Name")
                .required("api_key")
                .secret("api_key")
                .build()));
    add(into, new DestinationType(
        "discord",
        "Discord",
        "fa-discord",
        false,
        schema()
                .p("url", "type", "string", "title", "Discord Webhook URL")
                .required("url")
                .secret("url")
                .build()));
    add(into, new DestinationType(
        "email",
        "Email",
        "fa-envelope",
        true,
        schema()
                .p("addresses", "type", "string")
                .p("subject_template", "type", "string", "default", "Alert: {alert_name} changed status to {state}", "title", "Subject Template")
                .required("addresses")
                .extra("subject_template")
                .build()));
    add(into, new DestinationType(
        "hangouts_chat",
        "Google Hangouts Chat",
        "fa-bolt",
        false,
        schema()
                .p("url", "type", "string", "title", "Webhook URL (get it from the room settings)")
                .p("icon_url", "type", "string", "title", "Icon URL (32x32 or multiple, png format)")
                .required("url")
                .secret("url")
                .build()));
    add(into, new DestinationType(
        "mattermost",
        "Mattermost",
        "fa-bolt",
        false,
        schema()
                .p("url", "type", "string", "title", "Mattermost Webhook URL")
                .p("username", "type", "string", "title", "Username")
                .p("icon_url", "type", "string", "title", "Icon (URL)")
                .p("channel", "type", "string", "title", "Channel")
                .secretVerbatim("url")
                .build()));
    add(into, new DestinationType(
        "microsoft_teams_webhook",
        "Microsoft Teams Webhook",
        "fa-bolt",
        false,
        schema()
                .p("url", "type", "string", "title", "Microsoft Teams Webhook URL")
                .p("message_template", "type", "string", "default", "{\"@type\": \"MessageCard\", \"@context\": \"http://schema.org/extensions\", \"themeColor\": \"0076D7\", \"summary\": \"A Redash Alert was Triggered\", \"sections\": [{\"activityTitle\": \"A Redash Alert was Triggered\", \"facts\": [{\"name\": \"Alert Name\", \"value\": \"{alert_name}\"}, {\"name\": \"Alert URL\", \"value\": \"{alert_url}\"}, {\"name\": \"Query\", \"value\": \"{query_text}\"}, {\"name\": \"Query URL\", \"value\": \"{query_url}\"}], \"markdown\": true}]}", "title", "Message Template")
                .required("url")
                .build()));
    add(into, new DestinationType(
        "pagerduty",
        "PagerDuty",
        "creative-commons-pd-alt",
        true,
        schema()
                .p("integration_key", "type", "string", "title", "PagerDuty Service Integration Key")
                .p("description", "type", "string", "title", "Description for the event, defaults to alert name")
                .required("integration_key")
                .secret("integration_key")
                .build()));
    add(into, new DestinationType(
        "slack",
        "Slack",
        "fa-slack",
        false,
        schema()
                .p("url", "type", "string", "title", "Slack Webhook URL")
                .secret("url")
                .build()));
    add(into, new DestinationType(
        "webex",
        "Webex",
        "fa-webex",
        false,
        schema()
                .p("webex_bot_token", "type", "string", "title", "Webex Bot Token")
                .p("to_person_emails", "type", "string", "title", "People (comma-separated)")
                .p("to_room_ids", "type", "string", "title", "Rooms (comma-separated)")
                .required("webex_bot_token")
                .secret("webex_bot_token")
                .build()));
    add(into, new DestinationType(
        "webhook",
        "Webhook",
        "fa-bolt",
        true,
        schema()
                .p("url", "type", "string")
                .p("username", "type", "string")
                .p("password", "type", "string")
                .required("url")
                .secret("password", "url")
                .build()));
  }
}


package io.akka.redash.destinations;

import io.akka.redash.domain.Json;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * What each destination puts on the wire when an alert changes state (SPEC-001 R142).
 *
 * <p>Five of the twelve deliver here; the seven chat ones are registered and do nothing,
 * which is what this run's exclusion means in practice (SPEC-001 D-5). Every one of the
 * five swallows its own failure and reports it as a string rather than raising, because
 * the caller is a loop over subscriptions and one destination that cannot be reached must
 * cost its own delivery and none of the others (SPEC-001 R116).
 */
public final class Delivery {

  private static final HttpClient CLIENT =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

  /** Everything a destination needs to know about the alert it is describing. */
  public record Notification(
      long alertId,
      String alertName,
      String state,
      String customSubject,
      String customBody,
      long queryId,
      String queryName,
      String host,
      Map<String, Object> alertDocument,
      Map<String, Object> metadata,
      String defaultBody,
      String defaultSubjectTemplate,
      Mail.Server mailServer) {

    public String subject() {
      if (customSubject != null && !customSubject.isEmpty()) {
        return customSubject;
      }
      return defaultSubjectTemplate
          .replace("{alert_name}", alertName)
          .replace("{state}", state.toUpperCase(Locale.ROOT));
    }

    public String body() {
      return customBody != null && !customBody.isEmpty() ? customBody : defaultBody;
    }

    public String queryUrl() {
      return host + "/queries/" + queryId;
    }

    public String alertUrl() {
      return host + "/alerts/" + alertId;
    }
  }

  private Delivery() {}

  /**
   * Where a delivery actually goes.
   *
   * <p>Handed in rather than reached for, so that what each destination puts on the wire can
   * be read without a receiver — which is how R142 is compared against the original, whose
   * own probe replaces `requests.post` and `mail.send` the same way. It stands in for the
   * network and for nothing else: every destination below builds its whole request before
   * this is reached.
   */
  public interface Wire {
    HttpResponse<String> send(HttpRequest request) throws Exception;

    String mail(Mail.Server server, List<String> recipients, String subject, String html,
        String text);
  }

  /** The one that sends. */
  public static final Wire NETWORK = new Wire() {
    @Override
    public HttpResponse<String> send(HttpRequest request) throws Exception {
      return CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
    }

    @Override
    public String mail(Mail.Server server, List<String> recipients, String subject,
        String html, String text) {
      return Mail.send(server, recipients, subject, html, text);
    }
  };

  public static String notify(DestinationType type, Map<String, Object> options,
      Notification notification) {
    return notify(type, options, notification, NETWORK);
  }

  /**
   * Send one notification to one destination.
   *
   * @return null when it went, or a sentence saying why it did not
   */
  public static String notify(DestinationType type, Map<String, Object> options,
      Notification notification, Wire wire) {
    if (type == null) {
      return "Unknown destination type.";
    }
    if (!type.delivers()) {
      // Registered, configurable, subscribable — and not delivered to, because it posts
      // into a third party's own chat product.
      return null;
    }
    try {
      return switch (type.type()) {
        case "email" -> email(options, notification, wire);
        case "webhook" -> webhook(options, notification, wire);
        case "pagerduty" -> pagerduty(options, notification, wire);
        case "datadog" -> datadog(options, notification, wire);
        case "asana" -> asana(options, notification, wire);
        default -> "Unknown destination type.";
      };
    } catch (Exception e) {
      return type.name() + " send ERROR. " + e;
    }
  }

  // ------------------------------------------------------------------ email

  static String email(Map<String, Object> options, Notification notification,
      Wire wire) {
    var recipients = new ArrayList<String>();
    for (String address : String.valueOf(options.getOrDefault("addresses", "")).split(",")) {
      if (!address.isBlank()) {
        recipients.add(address.strip());
      }
    }
    var subjectTemplate = options.get("subject_template");
    var subject = notification.customSubject() != null && !notification.customSubject().isEmpty()
        ? notification.customSubject()
        : String.valueOf(subjectTemplate == null
            ? notification.defaultSubjectTemplate() : subjectTemplate)
            .replace("{alert_name}", notification.alertName())
            .replace("{state}", notification.state().toUpperCase(Locale.ROOT));
    return wire.mail(notification.mailServer(), recipients, subject,
        notification.body(), null);
  }

  // ------------------------------------------------------------------ webhook

  static String webhook(Map<String, Object> options, Notification notification,
      Wire wire) throws Exception {
    var alert = new java.util.LinkedHashMap<>(notification.alertDocument());
    // Empty rather than absent when the alert carries no wording of its own: on the source
    // these two are templates rendered through its own engine, and an absent template
    // renders to the empty string. A caller reading `alert.title` gets `""` there and would
    // get `null` here. Found by comparing what the two put on the wire.
    alert.put("description", notification.customBody() == null ? "" : notification.customBody());
    alert.put("title", notification.customSubject() == null ? "" : notification.customSubject());
    var document = Json.map(
        "event", "alert_state_change",
        "alert", alert,
        "url_base", notification.host(),
        "metadata", notification.metadata());

    var builder = HttpRequest.newBuilder(URI.create(String.valueOf(options.get("url"))))
        .timeout(Duration.ofSeconds(5))
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(Json.dumps(document)));
    var username = options.get("username");
    if (username != null && !String.valueOf(username).isEmpty()) {
      var token = Base64.getEncoder().encodeToString(
          (username + ":" + options.getOrDefault("password", ""))
              .getBytes(StandardCharsets.UTF_8));
      builder.header("Authorization", "Basic " + token);
    }
    var response = wire.send(builder.build());
    if (response.statusCode() != 200) {
      return "webhook send ERROR. status_code => " + response.statusCode();
    }
    return null;
  }

  // ------------------------------------------------------------------ pagerduty

  static String pagerduty(Map<String, Object> options, Notification notification,
      Wire wire)
      throws Exception {
    // An unknown state is not an incident either way, so nothing is sent at all.
    if ("unknown".equals(notification.state())) {
      return null;
    }
    var description = notification.customSubject() != null
        && !notification.customSubject().isEmpty()
        ? notification.customSubject()
        : (options.get("description") != null && !String.valueOf(options.get("description")).isEmpty()
            ? String.valueOf(options.get("description"))
            : "Alert: " + notification.alertName());
    var incidentKey = notification.alertId() + "_" + notification.queryId();
    var payload = new java.util.LinkedHashMap<String, Object>();
    payload.put("summary", description);
    payload.put("severity", "error");
    payload.put("source", "redash");
    if (notification.customBody() != null && !notification.customBody().isEmpty()) {
      payload.put("custom_details", notification.customBody());
    }
    var document = Json.map(
        "routing_key", options.get("integration_key"),
        "incident_key", incidentKey,
        "dedup_key", incidentKey,
        "payload", payload,
        "event_action", "triggered".equals(notification.state()) ? "trigger" : "resolve");
    var request = HttpRequest.newBuilder(URI.create("https://events.pagerduty.com/v2/enqueue"))
        .timeout(Duration.ofSeconds(5))
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(Json.dumps(document)))
        .build();
    var response = wire.send(request);
    if (response.statusCode() >= 300) {
      return "PagerDuty trigger failed! " + response.statusCode();
    }
    return null;
  }

  // ------------------------------------------------------------------ datadog

  static String datadog(Map<String, Object> options, Notification notification,
      Wire wire) throws Exception {
    boolean triggered = "triggered".equals(notification.state());
    var title = notification.customSubject() != null && !notification.customSubject().isEmpty()
        ? notification.customSubject()
        : notification.alertName() + (triggered ? " just triggered" : " went back to normal");
    var text = (notification.customBody() != null && !notification.customBody().isEmpty()
        ? notification.customBody()
        : notification.alertName() + " changed state to " + notification.state() + ".")
        + "\nQuery: " + notification.queryUrl()
        + "\nAlert: " + notification.alertUrl();

    var tags = new ArrayList<String>();
    var declared = options.get("tags");
    if (declared != null && !String.valueOf(declared).isEmpty()) {
      for (String tag : String.valueOf(declared).split(",")) {
        tags.add(tag);
      }
    }
    tags.add("redash");
    tags.add("query_id:" + notification.queryId());
    tags.add("alert_id:" + notification.alertId());

    var document = Json.map(
        "title", title,
        "text", text,
        "alert_type", triggered ? "error" : "success",
        "priority", options.get("priority"),
        "source_type_name", options.get("source_type_name"),
        "aggregation_key", "redash:" + notification.alertUrl(),
        "tags", tags);
    var host = System.getenv().getOrDefault("DATADOG_HOST", "api.datadoghq.com");
    var request = HttpRequest.newBuilder(URI.create("https://" + host + "/api/v1/events"))
        .timeout(Duration.ofSeconds(5))
        .header("Accept", "application/json")
        .header("Content-Type", "application/json")
        .header("DD-API-KEY", String.valueOf(options.get("api_key")))
        .POST(HttpRequest.BodyPublishers.ofString(Json.dumps(document)))
        .build();
    var response = wire.send(request);
    if (response.statusCode() != 202) {
      return "Datadog send ERROR. status_code => " + response.statusCode();
    }
    return null;
  }

  // ------------------------------------------------------------------ asana

  static String asana(Map<String, Object> options, Notification notification,
      Wire wire) throws Exception {
    var state = "triggered".equals(notification.state()) ? "TRIGGERED" : "RECOVERED";
    var notes = notification.alertName() + " has " + state + ".\n\n"
        + "Query: " + notification.queryUrl() + "\n"
        + "Alert: " + notification.alertUrl();
    var form = "name=" + encode("[Redash Alert] " + state + ": " + notification.alertName())
        + "&notes=" + encode(notes)
        // The key repeated rather than indexed: the source hands its client a list and the
        // client writes `projects=<value>` once per member. `projects[0]=<value>` is a
        // different form field and Asana would read no project at all.
        + "&projects=" + encode(String.valueOf(options.get("project_id")));
    var request = HttpRequest.newBuilder(URI.create("https://app.asana.com/api/1.0/tasks"))
        .timeout(Duration.ofSeconds(5))
        .header("Content-Type", "application/x-www-form-urlencoded")
        .header("Authorization", "Bearer " + options.get("pat"))
        .POST(HttpRequest.BodyPublishers.ofString(form))
        .build();
    var response = wire.send(request);
    if (response.statusCode() != 201) {
      return "Asana send ERROR. status_code => " + response.statusCode();
    }
    return null;
  }

  private static String encode(String value) {
    return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  /** The five that deliver, named once so a caller can report the split. */
  public static List<String> delivering() {
    var out = new ArrayList<String>();
    Destinations.all().forEach((type, definition) -> {
      if (definition.delivers()) {
        out.add(type);
      }
    });
    return out;
  }
}

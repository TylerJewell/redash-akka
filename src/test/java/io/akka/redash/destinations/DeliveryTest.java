package io.akka.redash.destinations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.akka.redash.domain.Json;
import io.akka.redash.domain.Oracle;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * What each built destination puts on the wire, against what the original put there
 * (SPEC-001 R142).
 *
 * <p>Thirty cases: five destinations, three alert states, and with and without a subject and
 * body of the alert's own. Each was captured from redash by calling the destination inside a
 * container with the transport it uses replaced by one that writes down its arguments —
 * `probes/probe_19_destinations.py`, recorded at
 * `src/test/resources/from-redash/probe_19_destinations.json`. This side does the same
 * through {@link Delivery.Wire}. Nothing is sent anywhere on either side; every byte handed
 * to a transport is compared.
 *
 * <p>The one thing not compared is the email's rendered body, because it is the source's own
 * template rendered by the source's own engine on both sides and is 4 kB of markup. Its
 * subject, its recipients and the fact that a message was handed over are compared, and
 * `AlertNotificationTest` already puts the rendering itself beside the original's.
 */
class DeliveryTest {

  static final String RECORDED = "probe_19_destinations.json";

  /** A transport that records rather than sends, which is the stand-in this test allows. */
  private static final class Recorder implements Delivery.Wire {
    final List<Map<String, Object>> sent = new ArrayList<>();

    @Override
    public HttpResponse<String> send(HttpRequest request) {
      var body = new java.util.concurrent.atomic.AtomicReference<String>("");
      request.bodyPublisher().ifPresent(publisher -> publisher.subscribe(
          new java.util.concurrent.Flow.Subscriber<java.nio.ByteBuffer>() {
            final StringBuilder text = new StringBuilder();

            @Override
            public void onSubscribe(java.util.concurrent.Flow.Subscription subscription) {
              subscription.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(java.nio.ByteBuffer item) {
              text.append(java.nio.charset.StandardCharsets.UTF_8.decode(item));
            }

            @Override
            public void onError(Throwable throwable) {
              body.set("");
            }

            @Override
            public void onComplete() {
              body.set(text.toString());
            }
          }));
      var headers = new LinkedHashMap<String, Object>();
      request.headers().map().forEach((name, values) -> headers.put(name, values.get(0)));
      sent.add(Json.map("kind", "http-post",
          "url", request.uri().toString(),
          "body", body.get(),
          "headers", headers));
      return new Answered();
    }

    @Override
    public String mail(Mail.Server server, List<String> recipients, String subject,
        String html, String text) {
      sent.add(Json.map("kind", "email", "subject", subject,
          "recipients", new ArrayList<Object>(recipients), "html", html));
      return null;
    }
  }

  /** Whatever a destination checks the status of, this is the one that means "it went". */
  private record Answered() implements HttpResponse<String> {
    @Override
    public int statusCode() {
      return 200;
    }

    @Override
    public HttpRequest request() {
      return null;
    }

    @Override
    public java.util.Optional<HttpResponse<String>> previousResponse() {
      return java.util.Optional.empty();
    }

    @Override
    public java.net.http.HttpHeaders headers() {
      return java.net.http.HttpHeaders.of(Map.of(), (a, b) -> true);
    }

    @Override
    public String body() {
      return "{}";
    }

    @Override
    public java.util.Optional<javax.net.ssl.SSLSession> sslSession() {
      return java.util.Optional.empty();
    }

    @Override
    public java.net.URI uri() {
      return java.net.URI.create("http://recorded.invalid/");
    }

    @Override
    public java.net.http.HttpClient.Version version() {
      return java.net.http.HttpClient.Version.HTTP_1_1;
    }
  }

  private static final Map<String, Map<String, Object>> OPTIONS = Map.of(
      "email", Json.map("addresses", "one@example.com,two@example.com"),
      "webhook", Json.map("url", "http://sink.invalid/hook"),
      "pagerduty", Json.map("integration_key", "probe-integration-key"),
      "datadog", Json.map("api_key", "probe-api-key"),
      "asana", Json.map("pat", "probe-token", "project_id", "probe-project"));

  @Test
  void everyBuiltDestinationSendsWhatTheOriginalSends() {
    var recorded = Oracle.load(RECORDED);
    int compared = 0;
    for (boolean custom : List.of(false, true)) {
      for (String state : List.of("triggered", "ok", "unknown")) {
        for (String type : List.of("email", "webhook", "pagerduty", "datadog", "asana")) {
          var key = type + "/" + state + "/" + (custom ? "custom" : "default");
          var expected = Json.asList(recorded.get(key));
          var wire = new Recorder();
          Delivery.notify(Destinations.get(type), OPTIONS.get(type),
              notification(type, state, custom), wire);
          assertEquals(expected.size(), wire.sent.size(),
              key + ": the original sent " + expected.size() + " message(s)");
          for (int i = 0; i < expected.size(); i++) {
            compare(key, Json.asMap(expected.get(i)), wire.sent.get(i));
          }
          compared++;
        }
      }
    }
    assertEquals(30, compared, "five destinations, three states, with and without a custom"
        + " subject and body");
  }

  /**
   * One message against one message.
   *
   * <p>The recordings carry what each side's own client library was handed, and the two
   * libraries name the same thing differently: redash's PagerDuty client is given the
   * document rather than a request, and its HTTP client records the body as text for three
   * destinations and as a parsed document for Asana, because that one is passed `json=`
   * rather than `data=`. So each is read to a document and compared as one, which is what
   * the destination decided.
   */
  private static void compare(String key, Map<String, Object> expected,
      Map<String, Object> actual) {
    var kind = String.valueOf(expected.get("kind"));
    if ("email".equals(kind)) {
      assertEquals("email", actual.get("kind"), key);
      assertEquals(expected.get("subject"), actual.get("subject"), key + " subject");
      assertEquals(expected.get("recipients"), actual.get("recipients"), key + " recipients");
      assertTrue(String.valueOf(actual.get("html")).length() > 0, key + " has a body");
      return;
    }
    if ("pagerduty".equals(kind)) {
      // The source's client is handed the document; this side is handed a request carrying
      // it. The address is the vendor's own and is asserted separately.
      assertEquals("https://events.pagerduty.com/v2/enqueue", actual.get("url"), key + " url");
      assertEquals(Json.asMap(expected.get("data")), documentOf(actual), key + " payload");
      return;
    }
    assertEquals(expected.get("url"), actual.get("url"), key + " url");
    if (expected.get("body") instanceof Map<?, ?> form) {
      // Asana is handed a map rather than a document, and its client writes a form: a list
      // value becomes the key repeated once per member.
      assertEquals(formOf(Json.asMap(expected.get("body"))),
          String.valueOf(actual.get("body")), key + " form");
      assertEquals(Json.asMap(expected.get("headers")).get("Authorization"),
          Json.asMap(actual.get("headers")).get("Authorization"), key + " authorization");
      return;
    }
    var wanted = expected.get("json_body") != null
        ? Json.asMap(expected.get("json_body"))
        : (expected.get("body") instanceof String text
            ? Json.asMap(Json.loads(text)) : Json.asMap(expected.get("body")));
    assertEquals(blankInstants(wanted), blankInstants(documentOf(actual)), key + " payload");
    // The headers a destination sets deliberately. Everything else is the client's own:
    // `Accept` is added by python-requests and by nothing this side does.
    var expectedHeaders = Json.asMap(expected.get("headers"));
    var actualHeaders = Json.asMap(actual.get("headers"));
    for (var header : List.of("Content-Type", "DD-API-KEY", "Authorization")) {
      if (expectedHeaders.containsKey(header)) {
        assertEquals(expectedHeaders.get(header), actualHeaders.get(header),
            key + " header " + header);
      }
    }
  }

  /** Stamped instants, blanked by name on both sides: the two were recorded minutes apart. */
  @SuppressWarnings("unchecked")
  private static Object blankInstants(Object value) {
    if (value instanceof Map<?, ?> map) {
      var out = new LinkedHashMap<String, Object>();
      map.forEach((k, v) -> out.put(String.valueOf(k),
          List.of("updated_at", "created_at", "retrieved_at").contains(String.valueOf(k))
              ? "<instant>" : blankInstants(v)));
      return out;
    }
    if (value instanceof List<?> list) {
      var out = new ArrayList<Object>();
      list.forEach(item -> out.add(blankInstants(item)));
      return out;
    }
    return value;
  }

  /** A map written the way python-requests writes one: a list repeats its key. */
  private static String formOf(Map<String, Object> fields) {
    var out = new ArrayList<String>();
    fields.forEach((name, value) -> {
      if (value instanceof List<?> many) {
        many.forEach(item -> out.add(name + "=" + urlEncode(String.valueOf(item))));
      } else {
        out.add(name + "=" + urlEncode(String.valueOf(value)));
      }
    });
    return String.join("&", out);
  }

  private static String urlEncode(String value) {
    return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8);
  }

  private static Map<String, Object> documentOf(Map<String, Object> sent) {
    return Json.asMap(Json.loads(String.valueOf(sent.get("body"))));
  }

  /**
   * The alert the original was asked about, rebuilt here field for field.
   *
   * <p>Its identifiers are the ones the recording carries — alert 4 on query 9 — because
   * three of the five put them on the wire: PagerDuty's deduplication key, Datadog's tags
   * and aggregation key, and the addresses Asana and Datadog write into their text.
   */
  private static Delivery.Notification notification(String type, String state,
      boolean custom) {
    // The probe built a second alert for the custom cases, so its identifier is 5 there and
    // 4 otherwise — and three of the five destinations put that identifier on the wire.
    long alertId = custom ? 5L : 4L;
    var options = new LinkedHashMap<String, Object>(
        Json.map("op", ">", "value", 0L, "column", "value", "selector", "first"));
    if (custom) {
      options.put("custom_subject", "A subject of my own");
      options.put("custom_body", "A body of my own");
    }
    var alert = Json.map(
        "id", alertId,
        "name", "probe alert",
        "options", options,
        "state", state,
        "last_triggered_at", null,
        "updated_at", "<instant>",
        "created_at", "<instant>",
        "rearm", null,
        "query_id", 9L,
        "user_id", 1L);
    return new Delivery.Notification(
        alertId, "probe alert", state,
        custom ? "A subject of my own" : null,
        custom ? "A body of my own" : null,
        9L, "probe query", "http://localhost:26600",
        alert, Json.map("Scheduled", false),
        "the default body", "Alert: {alert_name} changed status to {state}",
        new Mail.Server("localhost", 25, false, false, null, null,
            "redash@example.com", null, false));
  }
}

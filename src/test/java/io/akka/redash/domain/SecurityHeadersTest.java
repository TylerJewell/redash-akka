package io.akka.redash.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.akka.redash.api.Http;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The headers the original sets, under every configuration that changes one (SPEC-001 R152).
 *
 * <p>Fourteen settings reach a library redash hands them to, and the header each produces is
 * that library's decision rather than redash's — so reading redash gives the names and not
 * the wording. Each configuration was put to the original by building its own app with that
 * setting and reading the headers off a real response (`probes/probe_20_security.py`,
 * recorded at `src/test/resources/from-redash/probe_20_security.json`), and this puts the
 * same configurations to the rebuild.
 *
 * <p>Five of them are not what reading the source would predict, and each is asserted here
 * rather than described: switching file-save off <em>removes</em> a header rather than
 * changing it; the report-only policy <em>renames both</em> policy headers and sends no
 * enforcing pair; the report address is appended whether the policy is enforced or reported;
 * the frame address is appended only for the capitalised spelling, which is not redash's own default; and the
 * transport-security header is absent entirely unless the request arrived over https.
 */
class SecurityHeadersTest {

  static final String RECORDED = "probe_20_security.json";

  /**
   * The header names both sides decide.
   *
   * <p>Everything else in the recording belongs to whatever carried the answer —
   * `Content-Length`, `Content-Type`, and the session cookie's own value. `Vary` and the
   * cross-site cookie are set by both and are compared in the walk, over a real request.
   */
  private static final List<String> DECIDED = List.of(
      "X-Frame-Options", "X-XSS-Protection", "X-Content-Type-Options", "X-Download-Options",
      "Content-Security-Policy", "X-Content-Security-Policy",
      "Content-Security-Policy-Report-Only", "X-Content-Security-Policy-Report-Only",
      "Referrer-Policy", "Feature-Policy", "Strict-Transport-Security");

  private static Map<String, String> headersOf(Map<String, String> environment,
      boolean secureRequest) {
    var settings = new Settings(environment);
    var response = Http.secured(Http.json(Json.map()), settings, null, secureRequest);
    var out = new LinkedHashMap<String, String>();
    response.getHeaders().forEach(header -> out.putIfAbsent(header.name(), header.value()));
    return out;
  }

  /**
   * What the original set, as a caller would receive it.
   *
   * <p>The recording is taken off the application object rather than off the wire, because
   * each case needs its own configuration and a real deployment per case is sixteen
   * container restarts. The one place the two differ is trailing whitespace: the library
   * renders the policy with two spaces on the end, and HTTP strips trailing whitespace from
   * a header value — so the default policy reaches a caller without them and the one with a
   * report address keeps them, because something follows. Both readings were taken from a
   * real response before this was written, and they are why the strip is here rather than in
   * the assertion.
   */
  private static Map<String, String> recorded(String caseName) {
    var out = new LinkedHashMap<String, String>();
    Json.asMap(Oracle.section(RECORDED, caseName).get("headers"))
        .forEach((name, value) -> out.put(name, String.valueOf(value).stripTrailing()));
    return out;
  }

  private static void compare(String caseName, Map<String, String> environment,
      boolean secureRequest) {
    var expected = recorded(caseName);
    var actual = headersOf(environment, secureRequest);
    for (String name : DECIDED) {
      assertEquals(expected.get(name), actual.get(name),
          caseName + ": " + name + " differs from what the original sent");
    }
  }

  private static Map<String, String> with(String... pairs) {
    var out = new LinkedHashMap<String, String>();
    out.put("REDASH_COOKIE_SECRET", "probe-cookie-secret");
    for (int i = 0; i + 1 < pairs.length; i += 2) {
      out.put(pairs[i], pairs[i + 1]);
    }
    return out;
  }

  @Test
  @DisplayName("every header the original sets by default is set here")
  void theDefaults() {
    compare("default", with(), false);
  }

  @Test
  @DisplayName("switching file-save off removes a header rather than changing it")
  void fileSave() {
    compare("no-file-save", with("REDASH_ENFORCE_FILE_SAVE", "false"), false);
  }

  @Test
  @DisplayName("a feature policy appears only when it is set")
  void featurePolicy() {
    compare("feature-policy", with("REDASH_FEATURE_POLICY", "geolocation 'none'"), false);
  }

  @Test
  @DisplayName("report-only renames both policy headers and sends no enforcing pair")
  void reportOnly() {
    var environment = with("REDASH_CONTENT_SECURITY_POLICY_REPORT_ONLY", "true");
    compare("csp-report-only", environment, false);
    assertNull(headersOf(environment, false).get("Content-Security-Policy"),
        "the enforcing header is not sent alongside the reporting one");
  }

  @Test
  @DisplayName("the report address is appended, with report-only on or off")
  void reportUri() {
    compare("csp-report-uri",
        with("REDASH_CONTENT_SECURITY_POLICY_REPORT_URI", "https://example.com/csp"), false);
    compare("csp-report-only-with-uri",
        with("REDASH_CONTENT_SECURITY_POLICY_REPORT_ONLY", "true",
            "REDASH_CONTENT_SECURITY_POLICY_REPORT_URI", "https://example.com/csp"), false);
  }

  @Test
  @DisplayName("a nonce goes where the original puts it, and is fresh on every response")
  void nonce() {
    var environment = with("REDASH_CONTENT_SECURITY_POLICY_NONCE_IN", "script-src");
    var first = headersOf(environment, false).get("Content-Security-Policy");
    var second = headersOf(environment, false).get("Content-Security-Policy");
    var expected = recorded("csp-nonce").get("Content-Security-Policy");
    // The nonce itself is fresh per response on both sides, so what is compared is where it
    // goes and the shape of the directive around it.
    assertEquals(expected.replaceAll("'nonce-[^']*'", "'nonce-X'"),
        first.replaceAll("'nonce-[^']*'", "'nonce-X'"), "the nonce is in the wrong place");
    assertFalse(first.equals(second), "a nonce reused across two responses is not a nonce");
  }

  @Test
  @DisplayName("the frame address is appended only for the capitalised spelling")
  void frameOptions() {
    compare("frame-allow-from",
        with("REDASH_FRAME_OPTIONS", "allow-from",
            "REDASH_FRAME_OPTIONS_ALLOW_FROM", "https://example.com"), false);
    compare("frame-options-allowfrom-uppercase",
        with("REDASH_FRAME_OPTIONS", "ALLOW-FROM",
            "REDASH_FRAME_OPTIONS_ALLOW_FROM", "https://example.com"), false);
  }

  @Test
  @DisplayName("transport security is absent over http and is the original's wording over https")
  void strictTransportSecurity() {
    compare("hsts", with("REDASH_HSTS_ENABLED", "true"), false);
    compare("hsts-all", allTheHstsSettings(), false);
    compare("hsts-over-https", with("REDASH_HSTS_ENABLED", "true"), true);
    compare("hsts-all-over-https", allTheHstsSettings(), true);
  }

  private static Map<String, String> allTheHstsSettings() {
    return with("REDASH_HSTS_ENABLED", "true", "REDASH_HSTS_PRELOAD", "true",
        "REDASH_HSTS_INCLUDE_SUBDOMAINS", "true", "REDASH_HSTS_MAX_AGE", "1234");
  }

  @Test
  @DisplayName("the session cookie carries the two flags the deployment decides")
  void sessionCookie() {
    var cookies = new ArrayList<String>();
    Json.asList(Oracle.section(RECORDED, "session-cookie").get("set_cookie"))
        .forEach(value -> cookies.add(String.valueOf(value)));
    var recordedSession = cookies.stream().filter(c -> c.startsWith("session="))
        .findFirst().orElseThrow();
    assertTrue(recordedSession.contains("; Secure"),
        "the recorded cookie is the one this compares against");
    assertFalse(recordedSession.contains("HttpOnly"),
        "and the recorded one dropped HttpOnly when the setting was turned off");

    var settings = new Settings(with("REDASH_SESSION_COOKIE_SECURE", "true",
        "REDASH_SESSION_COOKIE_HTTPONLY", "false"));
    var written = Http.withCookie(Http.json(Json.map()), settings, "session", "x", 3600)
        .getHeaders().iterator().next().value();
    assertTrue(written.contains("; Secure"), "secure is set when the deployment asks for it");
    assertFalse(written.contains("HttpOnly"), "and HttpOnly is dropped when it does not");
  }
}

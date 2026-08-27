package io.akka.redash.api;

import akka.http.javadsl.model.ContentTypes;
import akka.http.javadsl.model.HttpHeader;
import akka.http.javadsl.model.HttpResponse;
import akka.http.javadsl.model.MediaTypes;
import akka.http.javadsl.model.StatusCodes;
import akka.http.javadsl.model.headers.RawHeader;
import akka.javasdk.http.HttpResponses;
import io.akka.redash.domain.Json;
import io.akka.redash.domain.Settings;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The shapes every endpoint answers in, and the two error bodies the original produces
 * without meaning to (SPEC-001 R150).
 *
 * <p>A refusal with a message answers that message; a refusal without one answers
 * werkzeug's own wording, because that is what the original answers and a caller reading
 * the text sees it. Both are here rather than in each endpoint so that the wrong one
 * cannot be written by accident.
 */
public final class Http {

  public static final String BAD_REQUEST =
      "The browser (or proxy) sent a request that this server could not understand.";

  public static final String NOT_FOUND =
      "The requested URL was not found on the server. If you entered the URL manually please"
          + " check your spelling and try again.";

  /**
   * The page the source's framework produces for an unhandled failure on a plain route.
   *
   * <p>A flask-restful resource answers `{"message": "Internal Server Error"}`; a route
   * registered directly on the blueprint — `/status.json`, `/api/admin/queries/*` — gets
   * this instead, because nothing has installed a JSON error handler on it.
   */
  public static final String SERVER_ERROR_PAGE =
      "<!doctype html>\n<html lang=en>\n<title>500 Internal Server Error</title>\n"
      + "<h1>Internal Server Error</h1>\n<p>The server encountered an internal"
      + " error and was unable to complete your request. Either the server is overloaded"
      + " or there is an error in the application.</p>\n";

  /** The framework's own page for a request it rejected before any handler saw it. */
  public static String badRequestPage(String message) {
    return "<!doctype html>" + PAGE_BREAK + "<html lang=en>" + PAGE_BREAK
        + "<title>400 Bad Request</title>" + PAGE_BREAK + "<h1>Bad Request</h1>" + PAGE_BREAK
        + "<p>" + message + "</p>" + PAGE_BREAK;
  }

  private static final String PAGE_BREAK = "\n";

  public static final String UNAUTHENTICATED =
      "Couldn't find resource. Please login and try again.";

  private Http() {}

  /** A refusal, carried as an exception so a handler can stop where it notices. */
  public static final class Refused extends RuntimeException {
    private final int status;
    private final Object body;
    private final String page;
    private final String location;

    public Refused(int status, Object body) {
      this(status, body, null);
    }

    /** A refusal that is a redirect: the caller is sent somewhere else instead. */
    public static Refused redirectTo(String location) {
      var refused = new Refused(302, null, redirectBody(location), location);
      return refused;
    }

    /**
     * @param page an HTML page to answer with instead of the JSON body, for a refusal made
     *     on a route the source renders pages on
     */
    public Refused(int status, Object body, String page) {
      this(status, body, page, null);
    }

    private Refused(int status, Object body, String page, String location) {
      super(String.valueOf(body), null, false, false);
      this.status = status;
      this.body = body;
      this.page = page;
      this.location = location;
    }

    public String location() {
      return location;
    }

    public String page() {
      return page;
    }

    public int status() {
      return status;
    }

    public Object body() {
      return body;
    }
  }

  public static Refused refuse(int status, String message) {
    return new Refused(status, Json.map("message", message));
  }

  /** 400 with the wording the original's own framework produces for a bare refusal. */
  public static Refused badRequest() {
    return refuse(400, BAD_REQUEST);
  }

  public static Refused badRequest(String message) {
    return refuse(400, message);
  }

  public static Refused notFound() {
    return refuse(404, NOT_FOUND);
  }

  public static Refused notFound(String message) {
    return refuse(404, message);
  }

  public static Refused forbidden() {
    return new Refused(403, null);
  }

  public static Refused forbidden(String message) {
    return refuse(403, message);
  }

  public static Refused conflict() {
    return new Refused(409, null);
  }

  public static Refused serverError() {
    return refuse(500, "Internal Server Error");
  }

  // ------------------------------------------------------------------ responses

  /** A JSON body written exactly the way the original writes it, separators and all. */
  public static HttpResponse json(Object body) {
    return json(200, body);
  }

  public static HttpResponse json(int status, Object body) {
    var text = body == null ? "null" : Json.dumps(body);
    return HttpResponses.of(StatusCodes.get(status), ContentTypes.APPLICATION_JSON,
        text.getBytes(StandardCharsets.UTF_8));
  }

  public static HttpResponse noContent() {
    return HttpResponses.of(StatusCodes.get(204), ContentTypes.APPLICATION_JSON, new byte[0]);
  }

  public static HttpResponse text(int status, String mediaType, String body) {
    return HttpResponses.of(StatusCodes.get(status),
        akka.http.javadsl.model.ContentTypes.parse(mediaType),
        body.getBytes(StandardCharsets.UTF_8));
  }

  public static HttpResponse bytes(int status, String mediaType, byte[] body) {
    return HttpResponses.of(StatusCodes.get(status),
        akka.http.javadsl.model.MediaTypes.APPLICATION_OCTET_STREAM.toContentType(), body)
        .addHeader(RawHeader.create("Content-Type", mediaType));
  }

  public static HttpResponse redirect(String location) {
    return HttpResponses.of(StatusCodes.get(302),
        ContentTypes.TEXT_HTML_UTF8, redirectBody(location).getBytes(StandardCharsets.UTF_8))
        .addHeader(RawHeader.create("Location", location));
  }

  /** The body the original's own redirect carries, which some clients display. */
  static String redirectBody(String location) {
    return "<!doctype html>\n<html lang=en>\n<title>Redirecting...</title>\n"
        + "<h1>Redirecting...</h1>\n<p>You should be redirected automatically to the target URL:"
        + " <a href=\"" + location + "\">" + location + "</a>. If not, click the link.\n";
  }

  public static HttpResponse html(String body) {
    return HttpResponses.of(StatusCodes.OK, ContentTypes.TEXT_HTML_UTF8,
        body.getBytes(StandardCharsets.UTF_8));
  }

  public static HttpResponse html(int status, String body) {
    return HttpResponses.of(StatusCodes.get(status), ContentTypes.TEXT_HTML_UTF8,
        body.getBytes(StandardCharsets.UTF_8));
  }

  /** Turn a refusal into the answer it stands for. */
  public static HttpResponse of(Refused refused) {
    if (refused.location() != null) {
      return redirect(refused.location());
    }
    if (refused.page() != null) {
      return html(refused.status(), refused.page());
    }
    if (refused.body() == null) {
      // A refusal with no message answers the framework's own page, which is HTML.
      var title = refused.status() == 403 ? "403 Forbidden" : refused.status() + " Error";
      var heading = refused.status() == 403 ? "Forbidden" : "Error";
      return html(refused.status(),
          "<!doctype html>\n<html lang=en>\n<title>" + title + "</title>\n<h1>" + heading
              + "</h1>\n<p>You don't have the permission to access the requested resource. It is"
              + " either read-protected or not readable by the server.</p>\n");
    }
    return json(refused.status(), refused.body());
  }

  // ------------------------------------------------------------------ headers

  /**
   * The security headers the original sets on every response (SPEC-001 R152).
   *
   * <p>Every branch below was put to the running original one setting at a time and its
   * answer recorded — `probes/probe_20_security.py`, question-log rows 99 to 103 — because
   * the header is the decision of the library redash hands these settings to rather than of
   * redash, so reading redash gives the names and not the wording.
   *
   * @param secureRequest whether the caller reached this over https, which is the only
   *     condition under which the transport-security header is sent at all
   */
  public static HttpResponse secured(HttpResponse response, Settings settings,
      String csrfToken, boolean secureRequest) {
    var headers = new ArrayList<HttpHeader>();
    // `ALLOW-FROM` in capitals is the only spelling the address is appended to. redash's own
    // default is lower-case `deny`, and a deployment writing lower-case `allow-from` gets
    // that word alone.
    var frameOptions = settings.frameOptions();
    if ("ALLOW-FROM".equals(frameOptions) && !settings.frameOptionsAllowFrom().isEmpty()) {
      frameOptions = frameOptions + " " + settings.frameOptionsAllowFrom();
    }
    headers.add(RawHeader.create("X-Frame-Options", frameOptions));
    headers.add(RawHeader.create("X-XSS-Protection", "1; mode=block"));
    headers.add(RawHeader.create("X-Content-Type-Options", "nosniff"));
    if (settings.enforceFileSave()) {
      headers.add(RawHeader.create("X-Download-Options", "noopen"));
    }
    var policy = contentSecurityPolicy(settings);
    var policyHeader = settings.contentSecurityPolicyReportOnly()
        ? "Content-Security-Policy-Report-Only" : "Content-Security-Policy";
    headers.add(RawHeader.create(policyHeader, policy));
    headers.add(RawHeader.create("X-" + policyHeader, policy));
    headers.add(RawHeader.create("Referrer-Policy", settings.referrerPolicy()));
    if (!settings.featurePolicy().isEmpty()) {
      headers.add(RawHeader.create("Feature-Policy", settings.featurePolicy()));
    }
    if (settings.hstsEnabled() && secureRequest) {
      var value = new StringBuilder("max-age=").append(settings.hstsMaxAge());
      if (settings.hstsIncludeSubdomains()) {
        value.append("; includeSubDomains");
      }
      if (settings.hstsPreload()) {
        value.append("; preload");
      }
      headers.add(RawHeader.create("Strict-Transport-Security", value.toString()));
    }
    headers.add(RawHeader.create("Vary", "Cookie"));
    if (csrfToken != null) {
      headers.add(RawHeader.create("Set-Cookie", "csrf_token=" + csrfToken + "; Path=/"
          + (settings.cookiesSecure() ? "; Secure" : "")));
    }
    var out = response;
    for (HttpHeader header : headers) {
      out = out.addHeader(header);
    }
    return out;
  }

  /**
   * The policy as it goes on the wire.
   *
   * <p>Three things happen to the configured string, and all three were measured rather than
   * read (`probes/probe_20_security.py`): a nonce is appended inside each directive that
   * asks for one, the whole thing gains two trailing spaces, and the report address is
   * appended after them when one is set — with or without report-only, which is not what the
   * library's own documentation says.
   *
   * <p>The nonce is put in by editing the string rather than by taking it apart and
   * rebuilding it, because everything else about the header is byte for byte the configured
   * value and rebuilding it would change the spacing of every directive.
   */
  private static String contentSecurityPolicy(Settings settings) {
    var policy = settings.contentSecurityPolicy();
    var nonceIn = settings.contentSecurityPolicyNonceIn();
    if (!nonceIn.isEmpty()) {
      var raw = new byte[16];
      new java.security.SecureRandom().nextBytes(raw);
      var nonce = " 'nonce-"
          + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(raw) + "'";
      for (String directive : nonceIn) {
        policy = withNonce(policy, directive.strip(), nonce);
      }
    }
    // Two spaces are the library's own rendering of the policy it was handed, and they are
    // only visible when something follows them: HTTP strips trailing whitespace from a
    // header value, so the default policy reaches a caller ending in `redash.io;` and the
    // one with a report address reaches it ending in `redash.io;  ; report-uri …`. Both were
    // read off a real response from the running original rather than off its app object,
    // which is where the difference lives (question-log row 108).
    policy = policy + "  ";
    var reportUri = settings.contentSecurityPolicyReportUri();
    if (!reportUri.isEmpty()) {
      policy = policy + "; report-uri " + reportUri;
    }
    return policy.stripTrailing();
  }

  /** The nonce appended to the end of one named directive's value. */
  private static String withNonce(String policy, String directive, String nonce) {
    int at = policy.indexOf(directive);
    while (at >= 0) {
      boolean startsDirective = at == 0 || policy.charAt(at - 1) == ' '
          || policy.charAt(at - 1) == ';';
      if (startsDirective) {
        int end = policy.indexOf(';', at);
        if (end < 0) {
          end = policy.length();
        }
        return policy.substring(0, end) + nonce + policy.substring(end);
      }
      at = policy.indexOf(directive, at + 1);
    }
    return policy;
  }

  public static HttpResponse withCookie(HttpResponse response, Settings settings, String name,
      String value, int maxAgeSeconds) {
    var cookie = new StringBuilder(name).append("=").append(value).append("; Path=/");
    if (settings.sessionCookieHttpOnly()) {
      cookie.append("; HttpOnly");
    }
    if (settings.sessionCookieSecure()) {
      cookie.append("; Secure");
    }
    if (maxAgeSeconds >= 0) {
      cookie.append("; Max-Age=").append(maxAgeSeconds);
    }
    return response.addHeader(RawHeader.create("Set-Cookie", cookie.toString()));
  }

  /** Read a cookie out of a request's `Cookie` header, which arrives as one string. */
  public static Map<String, String> cookies(String header) {
    var out = new LinkedHashMap<String, String>();
    if (header == null) {
      return out;
    }
    for (String pair : header.split(";")) {
      var trimmed = pair.strip();
      int equals = trimmed.indexOf('=');
      if (equals > 0) {
        out.put(trimmed.substring(0, equals), trimmed.substring(equals + 1));
      }
    }
    return out;
  }

  /** The `Content-Disposition` a download carries, with the two filename forms. */
  public static List<String> contentDisposition(String filename) {
    var out = new ArrayList<String>();
    boolean ascii = filename.chars().allMatch(c -> c < 128);
    if (ascii) {
      out.add("attachment; filename=\"" + filename + "\"");
    } else {
      var normalised = java.text.Normalizer.normalize(filename, java.text.Normalizer.Form.NFKD)
          .replaceAll("[^\\x00-\\x7F]", "");
      out.add("attachment; filename=\"" + normalised + "\"; filename*=UTF-8''"
          + java.net.URLEncoder.encode(filename, StandardCharsets.UTF_8).replace("+", "%20"));
    }
    return out;
  }

  /** The media type each download format is served as. */
  public static String mediaTypeFor(String filetype) {
    return switch (filetype) {
      case "csv" -> "text/csv; charset=UTF-8";
      case "tsv" -> "text/tab-separated-values; charset=UTF-8";
      case "xlsx" ->
          "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
      default -> "application/json";
    };
  }

  /** Kept so a caller can name the spreadsheet media type without repeating it. */
  public static akka.http.javadsl.model.MediaType.Binary spreadsheet() {
    return MediaTypes.APPLICATION_OCTET_STREAM;
  }
}

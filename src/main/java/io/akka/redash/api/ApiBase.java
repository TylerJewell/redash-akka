package io.akka.redash.api;

import akka.http.javadsl.model.HttpHeader;
import akka.http.javadsl.model.HttpResponse;
import akka.javasdk.http.AbstractHttpEndpoint;
import io.akka.redash.domain.Access;
import io.akka.redash.domain.Crypto;
import io.akka.redash.domain.Json;
import io.akka.redash.application.Store;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * What every endpoint under `/api` shares: who is calling, what that refuses, and how an
 * answer is written.
 *
 * <p>The order the two guards run in is observable and is the source's. CSRF is checked
 * **before** authentication, so an unauthenticated request with no token is refused for the
 * token rather than for the session; and an authenticated caller is never checked at all,
 * because the source's guard fires only when the caller is unauthenticated or the session
 * carries a literal `user_id` key, which its login extension does not set
 * (question-log row 74).
 */
public abstract class ApiBase extends AbstractHttpEndpoint {

  protected final Service service;

  protected ApiBase(Service service) {
    this.service = service;
  }

  // ------------------------------------------------------------------ the CSRF guard

  /**
   * flask-wtf's guard, run before anything else a state-changing handler does
   * (SPEC-001 R151).
   *
   * <p>The source registers this only when `REDASH_ENFORCE_CSRF` is set, and then only for a
   * request that is unauthenticated or whose session carries a literal `user_id` key.
   * Nothing in redash ever writes that key — its login extension stores `_user_id` — so the
   * second half of the condition is unreachable and the guard is, in effect, about
   * unauthenticated requests alone. That is why a session-authenticated POST with no token
   * succeeds against the running original, which is what `probes/probe_16_surface.py`
   * recorded and what surprised the first reading of this rule.
   *
   * <p>The token is taken from the form field first and then from either of two headers,
   * in that order, because that is the order the source's own extension looks in.
   */
  protected void guardCsrf(akka.http.javadsl.model.HttpEntity.Strict entity) {
    var settings = service.settings();
    if (!settings.enforceCsrf()) {
      return;
    }
    var caller = Sessions.resolve(service, header("Cookie"), header("Authorization"),
        queryParam("api_key"), null);
    if (caller.isAuthenticated()) {
      return;
    }
    var token = formToken(entity);
    if (token == null || token.isBlank()) {
      token = header("X-CSRFToken");
    }
    if (token == null || token.isBlank()) {
      token = header("X-CSRF-Token");
    }
    if (token == null || token.isBlank()) {
      throw refuseCsrf("The CSRF token is missing.");
    }
    if (!Sessions.isValidCsrfToken(settings, token)) {
      throw refuseCsrf("The CSRF token is invalid.");
    }
  }

  /**
   * The refusal, in the shape the route that was asked answers refusals in.
   *
   * <p>A flask-restful resource under `/api` answers JSON; a page rendered by the
   * application itself gets the framework's own error page, because the guard runs before
   * any handler and nothing has installed a JSON error handler on those routes.
   */
  private Http.Refused refuseCsrf(String message) {
    if (!rendersPages()) {
      return Http.refuse(400, message);
    }
    return new Http.Refused(400, message, Http.badRequestPage(message));
  }

  /** Whether this endpoint serves pages rather than the JSON API. */
  protected boolean rendersPages() {
    return false;
  }

  /**
   * The token a posted form carries, in either encoding a form arrives in.
   *
   * <p>`Pages.form` reads both because the original's framework reads both — a JSON body is
   * neither and is left to the header instead.
   */
  private static String formToken(akka.http.javadsl.model.HttpEntity.Strict entity) {
    if (entity == null) {
      return null;
    }
    var body = entity.getData().utf8String();
    if (body.isEmpty() || body.startsWith("{") || body.startsWith("[")) {
      return null;
    }
    return Pages.form(entity).get("csrf_token");
  }

  // ------------------------------------------------------------------ the caller

  /** Resolve the caller, refusing outright when there is none. */
  protected Caller caller() {
    var caller = Sessions.resolve(service, header("Cookie"), header("Authorization"),
        queryParam("api_key"), null);
    if (!caller.isAuthenticated()) {
      throw Http.notFound(Http.UNAUTHENTICATED);
    }
    noteActive(caller);
    return caller;
  }

  /**
   * Remember that this person was here, for the minute-by-minute sync (SPEC-001 R36).
   *
   * <p>The source writes the instant into a redis hash on every request and moves it into
   * the person's row once a minute, so that a busy instance does not write a database row
   * per request. This keeps the same two steps and the same visible behaviour — `active_at`
   * lags by up to a minute — but writes one small record **per person** rather than one
   * shared one, because a single shared record would put every request in the instance
   * through one writer.
   */
  private void noteActive(Caller caller) {
    if (caller.isApiUser() || caller.id() == null) {
      return;
    }
    try {
      store().put(Store.STATE, "last-active:" + caller.id(),
          Json.map("user_id", caller.id(), "at", Service.now()));
    } catch (RuntimeException e) {
      // Losing the instant is not worth failing a request over; the source's own write is
      // fire-and-forget for the same reason.
      LOG.debug("could not record activity for {}", caller.id(), e);
    }
  }

  /**
   * The same, but a query identifier in the path lets a caller present that query's own
   * key — which is how a shared visualisation reads its results.
   */
  protected Caller callerFor(Object queryId) {
    var caller = Sessions.resolve(service, header("Cookie"), header("Authorization"),
        queryParam("api_key"), queryId);
    if (!caller.isAuthenticated()) {
      throw Http.notFound(Http.UNAUTHENTICATED);
    }
    return caller;
  }

  protected String header(String name) {
    return requestContext().requestHeader(name).map(HttpHeader::value).orElse(null);
  }

  protected String queryParam(String name) {
    return requestContext().queryParams().getString(name).orElse(null);
  }

  protected List<String> queryParams(String name) {
    var out = new ArrayList<String>();
    requestContext().queryParams().getAll(name).forEach(out::add);
    return out;
  }

  protected boolean hasQueryParam(String name) {
    return requestContext().queryParams().getString(name).isPresent();
  }

  protected int intParam(String name, int fallback) {
    var value = queryParam(name);
    if (value == null || value.isBlank()) {
      return fallback;
    }
    try {
      return Integer.parseInt(value.strip());
    } catch (NumberFormatException e) {
      return fallback;
    }
  }

  protected Boolean booleanParam(String name) {
    var value = queryParam(name);
    if (value == null) {
      return null;
    }
    try {
      return io.akka.redash.domain.Settings.parseBoolean(value);
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  // ------------------------------------------------------------------ events

  protected void record(Caller caller, Map<String, Object> options) {
    service.recordEvent(caller, options, header("User-Agent"), remoteAddress());
  }

  /**
   * The caller's address, as the event log records it (SPEC-001 R145).
   *
   * <p>`REDASH_PROXIES_COUNT` says how many hops in front of this are trusted to have
   * appended to the forwarded list, and the address taken is that many from the right —
   * which is what the source's proxy fix does, and why the first entry is the wrong one to
   * take when more than one proxy is in the way. A list shorter than the count means the
   * trusted hops did not all append, and the leftmost entry is the closest thing to an
   * answer.
   */
  protected String remoteAddress() {
    var forwarded = header("X-Forwarded-For");
    if (forwarded != null && !forwarded.isBlank()) {
      var hops = forwarded.split(",");
      var trusted = Math.max(1, service.settings().proxiesCount());
      var index = Math.max(0, hops.length - trusted);
      return hops[index].strip();
    }
    return header("Remote-Address");
  }

  // ------------------------------------------------------------------ answers

  /**
   * Run a handler and turn a refusal into the answer it stands for.
   *
   * <p>Every endpoint method goes through this, so an exception can be thrown wherever a
   * rule notices rather than threaded back as a return value — which is what makes the
   * handlers read like the source's, whose `abort()` does the same.
   */
  protected HttpResponse answer(Supplier<Object> handler) {
    try {
      var body = handler.get();
      if (body instanceof HttpResponse response) {
        return decorate(response);
      }
      return decorate(Http.json(body));
    } catch (Http.Refused refused) {
      return decorate(Http.of(refused));
    } catch (IllegalArgumentException e) {
      return decorate(Http.of(Http.badRequest()));
    } catch (RuntimeException e) {
      // The original answers 500 for an unhandled failure and says nothing more to the
      // caller — a query created against a data source that does not exist is one of these
      // (walk step 49). It writes the traceback to its own log, and so does this: a 500
      // whose cause is nowhere is a fault that can only be found by guessing.
      LOG.error("unhandled failure answering a request", e);
      return decorate(Http.of(Http.serverError()));
    }
  }

  private static final org.slf4j.Logger LOG =
      org.slf4j.LoggerFactory.getLogger(ApiBase.class);

  /** Add the headers the original puts on every response. */
  protected HttpResponse decorate(HttpResponse response) {
    var settings = service.settings();
    return Http.secured(response, settings, Sessions.csrfToken(settings), isSecureRequest());
  }

  /**
   * Whether the caller reached this over https.
   *
   * <p>Read from the forwarding header rather than from the socket, because the deployment
   * this reproduces sits behind a proxy that terminates the connection — which is also why
   * `REDASH_PROXIES_COUNT` exists. It decides one header: the transport-security one is sent
   * only on a secure request, on both sides.
   */
  protected boolean isSecureRequest() {
    // An endpoint answering on behalf of another one — `/setup` finishing by signing the
    // new administrator in — holds no request context of its own, and asking for one
    // raises. Not secure is the same answer the request itself would give here, because a
    // handler that has one always reads it before this is reached.
    try {
      return "https".equalsIgnoreCase(header("X-Forwarded-Proto"));
    } catch (IllegalStateException noRequestOfItsOwn) {
      return false;
    }
  }

  /**
   * `REDASH_ENFORCE_HTTPS` is read and not acted on, and this is where it would act.
   *
   * <p>The original redirects an http request to the https address of the same URL — 302,
   * or 301 when the deployment says the move is permanent — for every route except
   * `/ping`, which is exempt by accident: the library it hands the setting to caches the
   * option on each view the first time that view is asked for, and something asks `/ping`
   * while the application is still being built. Both settings were run against four routes
   * to establish that (question-log row 100).
   *
   * <p>Rebuilding it needs the request's own path, and a handler on this runtime is given
   * the request only when it declares an `HttpRequest` parameter — there is no filter that
   * sees every request on its way in. Redirecting to the host without the path would send a
   * caller somewhere other than where they asked for, so the setting is carried and the
   * redirect is not made. Listed in the README as a difference.
   */


  // ------------------------------------------------------------------ small shared rules

  /** Refuse unless every named field is present, which is what `require_fields` does. */
  protected static void requireFields(Map<String, Object> body, String... names) {
    for (String name : names) {
      if (!body.containsKey(name)) {
        throw Http.badRequest();
      }
    }
  }

  /** Read a request body as a document, refusing anything that is not one. */
  protected static Map<String, Object> document(Object body) {
    if (body instanceof Map<?, ?> map) {
      return Json.asMap(map);
    }
    if (body instanceof CharSequence text) {
      var parsed = Json.loads(text.toString());
      if (parsed instanceof Map<?, ?> map) {
        return Json.asMap(map);
      }
    }
    throw Http.badRequest();
  }

  /** Whether a caller may see a data source at all, which most reads open with. */
  protected boolean canView(Caller caller, Map<String, Object> dataSource) {
    return service.hasAccessToDataSource(caller, dataSource, Access.VIEW_ONLY);
  }

  /** A fresh API key, which several endpoints mint. */
  protected static String newApiKey() {
    return Crypto.generateToken(40);
  }

  /** Named once so an endpoint does not spell a table wrong. */
  protected Store store() {
    return service.store();
  }

  /**
   * An identifier taken from the path.
   *
   * <p>redash's routes declare these as strings and its models compare them against an
   * integer column, so a path segment that is not a number reaches the database and is
   * refused there. Here it is refused before that, as not found.
   */
  protected static long identifier(String value) {
    try {
      return Long.parseLong(value.strip());
    } catch (RuntimeException e) {
      throw Http.notFound();
    }
  }

  /**
   * The body as a document, where an absent body means an empty one.
   *
   * <p>Every endpoint takes its body as the raw entity rather than as a typed record. A
   * typed parameter is deserialised by the runtime before the handler sees it, and a request
   * whose shape does not fit answers the runtime's own parse error rather than the wording
   * the source uses — which is half of what this surface does. Reading the bytes here keeps
   * the refusal the handler's.
   */
  protected static Map<String, Object> body(akka.http.javadsl.model.HttpEntity.Strict entity) {
    return body(text(entity));
  }

  protected static Map<String, Object> body(String raw) {
    if (raw == null || raw.isBlank()) {
      return new java.util.LinkedHashMap<>();
    }
    return document(raw);
  }

  /** The body as a list, which only the events endpoint takes. */
  protected static List<Object> bodyList(akka.http.javadsl.model.HttpEntity.Strict entity) {
    var raw = text(entity);
    if (raw == null || raw.isBlank()) {
      return List.of();
    }
    var parsed = Json.loads(raw);
    if (parsed instanceof List<?> list) {
      return List.copyOf(list);
    }
    throw Http.badRequest();
  }

  protected static String text(akka.http.javadsl.model.HttpEntity.Strict entity) {
    return entity == null ? "" : entity.getData().utf8String();
  }

  /**
   * The grace a caller allows on a cached result (SPEC-001 R81).
   *
   * <p>An absent key and an explicit null both mean -1 — any age — which is not the same as
   * 0, and the difference decides whether a run happens at all.
   */
  protected static long maxAgeOf(Map<String, Object> request) {
    if (!request.containsKey("max_age") || request.get("max_age") == null) {
      return -1;
    }
    var value = request.get("max_age");
    if (value instanceof Number number) {
      return number.longValue();
    }
    try {
      return Long.parseLong(String.valueOf(value).strip());
    } catch (NumberFormatException e) {
      throw Http.badRequest();
    }
  }

  /** Only the named keys of a document survive, which is what most updates allow. */
  protected static Map<String, Object> only(Map<String, Object> fields, String... names) {
    var out = new java.util.LinkedHashMap<String, Object>();
    for (String name : names) {
      if (fields.containsKey(name)) {
        out.put(name, fields.get(name));
      }
    }
    return out;
  }
}

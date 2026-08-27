package io.akka.redash.api;

import io.akka.redash.domain.Crypto;
import io.akka.redash.domain.Settings;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * How a request is attributed to somebody (SPEC-001 R11, R12, R14).
 *
 * <p>The order is the source's and it matters: an API key wins over a session, so a request
 * that carries both is the key's. A key resolves to, in turn, the person holding it, the
 * object holding it, or — when a query identifier is in the path — that query's own key. A
 * disabled user's key resolves to nobody at all.
 *
 * <p>The session cookie carries the same identity string the source's does,
 * {@code <id>-<md5(email + "," + password_hash)>}, signed with the instance secret. That
 * shape is why changing an address or a password invalidates every session: both halves of
 * the hashed pair move, so the stored identity no longer matches.
 */
public final class Sessions {

  /** The salt the session cookie's signature is derived under. */
  static final String SALT = "redash-session";

  private Sessions() {}

  public static String sign(Settings settings, String identity) {
    return Crypto.signToken(settings.secretKey(), SALT, identity, Instant.now().getEpochSecond());
  }

  /** The identity a cookie carries, or null when it is missing, tampered with or too old. */
  public static String read(Settings settings, String cookieHeader) {
    var cookies = Http.cookies(cookieHeader);
    var value = cookies.get(settings.sessionCookieName());
    if (value == null) {
      return null;
    }
    var result = Crypto.readToken(settings.secretKey(), SALT, value,
        settings.sessionExpiryTime(), Instant.now().getEpochSecond());
    return result instanceof Crypto.TokenResult.Valid valid ? valid.payload() : null;
  }

  /**
   * The token written into the `csrf_token` cookie on every response.
   *
   * <p>It is signed rather than random so that it can be checked without keeping any state,
   * which is what the source does too.
   */
  public static String csrfToken(Settings settings) {
    return Crypto.signToken(settings.secretKey(), "wtf-csrf-token", "csrf",
        Instant.now().getEpochSecond());
  }

  public static boolean isValidCsrfToken(Settings settings, String token) {
    if (token == null) {
      return false;
    }
    var result = Crypto.readToken(settings.secretKey(), "wtf-csrf-token", token,
        settings.csrfTimeLimit(), Instant.now().getEpochSecond());
    return result instanceof Crypto.TokenResult.Valid;
  }

  /**
   * Work out who is calling.
   *
   * @param queryId a query identifier from the path, which lets that query's own key in
   */
  public static Caller resolve(Service service, String cookieHeader, String authorization,
      String apiKeyParam, Object queryId) {
    var org = service.currentOrg();

    var presented = apiKey(authorization, apiKeyParam);
    if (presented != null) {
      var user = service.userByApiKey(presented);
      if (user != null) {
        if (user.get("disabled_at") != null) {
          return Caller.anonymous(org);
        }
        return Caller.person(user, service.permissionsOf(user), service.groupIdsOf(user), org);
      }
      var apiKey = service.apiKeyByValue(presented);
      if (apiKey != null) {
        var object = objectFor(service, apiKey);
        return Caller.apiUser(presented, object, List.of(), org);
      }
      if (queryId != null) {
        var query = service.queryById(queryId);
        if (query != null && presented.equals(query.get("api_key"))) {
          var dataSource = service.dataSourceById(query.get("data_source_id"));
          return Caller.apiUser(presented, query,
              List.copyOf(service.groupsOf(dataSource).keySet()), org);
        }
      }
      return Caller.anonymous(org);
    }

    var identity = read(service.settings(), cookieHeader);
    if (identity == null) {
      return Caller.anonymous(org);
    }
    int hyphen = identity.indexOf('-');
    if (hyphen < 0) {
      return Caller.anonymous(org);
    }
    var user = service.userById(Long.parseLong(identity.substring(0, hyphen)));
    if (user == null || user.get("disabled_at") != null) {
      return Caller.anonymous(org);
    }
    var expected = Crypto.sessionIdentity(Service.number(user.get("id")),
        String.valueOf(user.get("email")), String.valueOf(user.get("password_hash")));
    if (!expected.equals(identity)) {
      return Caller.anonymous(org);
    }
    return Caller.person(user, service.permissionsOf(user), service.groupIdsOf(user), org);
  }

  /** The object a shared key belongs to, which is always a dashboard in practice. */
  private static Map<String, Object> objectFor(Service service, Map<String, Object> apiKey) {
    var objectType = String.valueOf(apiKey.get("object_type"));
    var objectId = apiKey.get("object_id");
    return switch (objectType) {
      case "dashboards" -> service.store()
          .find(io.akka.redash.application.Store.DASHBOARDS, objectId);
      case "queries" -> service.queryById(objectId);
      default -> null;
    };
  }

  /** A key is taken from the query string first, then from the header. */
  static String apiKey(String authorization, String apiKeyParam) {
    if (apiKeyParam != null && !apiKeyParam.isBlank()) {
      return apiKeyParam;
    }
    if (authorization != null && authorization.startsWith("Key ")) {
      return authorization.substring("Key ".length());
    }
    return null;
  }
}

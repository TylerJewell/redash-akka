package io.akka.redash.api;

import akka.http.javadsl.model.HttpEntity;
import akka.http.javadsl.model.HttpResponse;
import akka.javasdk.annotations.Acl;
import akka.javasdk.annotations.http.Get;
import akka.javasdk.annotations.http.HttpEndpoint;
import akka.javasdk.annotations.http.Post;
import akka.javasdk.client.ComponentClient;
import io.akka.redash.application.Store;
import io.akka.redash.domain.Crypto;
import io.akka.redash.domain.Json;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Signing in, signing out, and the four token-carrying pages
 * (SPEC-001 R14 to R20).
 *
 * <p>Every refusal here re-renders the page with a flash message rather than answering a
 * distinct status, so a caller cannot tell a wrong password from an unknown address from a
 * disabled account. That is the source's behaviour and it is the point of it.
 */
@Acl(allow = @Acl.Matcher(principal = Acl.Principal.ALL))
@HttpEndpoint("")
public class AuthEndpoint extends ApiBase {

  /** These are pages, so a refusal made before any handler runs answers HTML. */
  @Override
  protected boolean rendersPages() {
    return true;
  }


  public AuthEndpoint(ComponentClient client) {
    super(new Service(client));
  }

  // ------------------------------------------------------------------ login and logout

  @Get("/login")
  public HttpResponse loginPage() {
    return answer(() -> {
      if (!service.isSetUp()) {
        return Http.redirect("/setup");
      }
      var caller = Sessions.resolve(service, header("Cookie"), header("Authorization"),
          queryParam("api_key"), null);
      var next = Pages.nextPath(queryParam("next"));
      if (caller.isAuthenticated()) {
        return Http.redirect(next);
      }
      return Http.html(loginHtml(next, "", List.of()));
    });
  }

  @Post("/login")
  public HttpResponse login(HttpEntity.Strict entity) {
    return answer(() -> {
      guardCsrf(entity);
      if (!service.isSetUp()) {
        return Http.redirect("/setup");
      }
      var form = Pages.form(entity);
      var next = Pages.nextPath(form.getOrDefault("next", queryParam("next")));
      var email = form.getOrDefault("email", "");
      var password = form.getOrDefault("password", "");

      if (!Boolean.TRUE.equals(ClientConfig.setting(service.settings(), service.currentOrg(),
          "auth_password_login_enabled"))) {
        return Http.html(loginHtml(next, email,
            List.of("Password login is not enabled for your organization.")));
      }
      var user = service.userByEmail(email);
      if (user == null || user.get("disabled_at") != null
          || !Crypto.verifyPassword(password, (String) user.get("password_hash"))) {
        return Http.html(loginHtml(next, email, List.of("Wrong email or password.")));
      }
      return signedIn(user, next, form.containsKey("remember"));
    });
  }

  @Get("/logout")
  public HttpResponse logout() {
    return Http.withCookie(decorate(Http.redirect("/login")), service.settings(),
        service.settings().sessionCookieName(), "", 0);
  }

  // ------------------------------------------------------------------ tokens

  @Get("/invite/{token}")
  public HttpResponse invitePage(String token) {
    return answer(() -> tokenPage("invite.html", token, true, null, 200));
  }

  @Post("/invite/{token}")
  public HttpResponse acceptInvite(String token, HttpEntity.Strict entity) {
    return answer(() -> {
      guardCsrf(entity);
      return acceptToken("invite.html", token, true, entity);
    });
  }

  @Get("/reset/{token}")
  public HttpResponse resetPage(String token) {
    return answer(() -> tokenPage("reset.html", token, false, null, 200));
  }

  @Post("/reset/{token}")
  public HttpResponse acceptReset(String token, HttpEntity.Strict entity) {
    return answer(() -> {
      guardCsrf(entity);
      return acceptToken("reset.html", token, false, entity);
    });
  }

  @Get("/verify/{token}")
  public HttpResponse verify(String token) {
    return answer(() -> {
      var user = userFromToken(token);
      if (user == null) {
        return Http.html(400, Pages.error(service,
            "Your verification link is invalid. Please ask for a new one."));
      }
      store().update(Store.USERS, user.get("id"), Json.map("is_email_verified", true));
      return Http.html(service.templates().render("verify.html",
          Pages.base(service, Json.map("next_url", "/"))));
    });
  }

  @Post("/verification_email/")
  public HttpResponse verificationEmail() {
    return answer(() -> {
      guardCsrf(null);
      var caller = caller();
      if (caller.user() != null
          && Boolean.FALSE.equals(caller.user().get("is_email_verified"))) {
        service.sendVerifyEmail(caller.user(), service.baseUrl() + "/verify/"
            + Crypto.signToken(service.settings().secretKey(), "itsdangerous",
                String.valueOf(Service.number(caller.user().get("id"))),
                java.time.Instant.now().getEpochSecond()));
      }
      return Json.map("message",
          "Please check your email inbox in order to verify your email address.");
    });
  }

  @Get("/forgot")
  public HttpResponse forgotPage() {
    return answer(() -> {
      requirePasswordLogin();
      return Http.html(service.templates().render("forgot.html",
          Pages.base(service, Json.map("submitted", false))));
    });
  }

  @Post("/forgot")
  public HttpResponse forgot(HttpEntity.Strict entity) {
    return answer(() -> {
      guardCsrf(entity);
      requirePasswordLogin();
      var form = Pages.form(entity);
      var email = form.get("email");
      boolean submitted = false;
      if (email != null && !email.isEmpty()) {
        submitted = true;
        var user = service.userByEmail(email);
        if (user != null) {
          if (user.get("disabled_at") != null) {
            service.sendDisabledEmail(user);
          } else {
            service.sendPasswordResetEmail(user, service.baseUrl() + "/reset/"
                + Crypto.signToken(service.settings().secretKey(), "itsdangerous",
                    String.valueOf(Service.number(user.get("id"))),
                    java.time.Instant.now().getEpochSecond()));
          }
        }
      }
      return Http.html(service.templates().render("forgot.html",
          Pages.base(service, Json.map("submitted", submitted))));
    });
  }

  // ------------------------------------------------------------------ external identity

  /**
   * The four external login paths (SPEC-001 R19, R20).
   *
   * <p>Each one ends in the same place: find or create the person by address in this
   * organisation, refuse a disabled one, clear a pending invitation, and take a changed
   * name. What differs is only where the assertion came from.
   */
  @Get("/oauth/google")
  public HttpResponse googleAuthorize() {
    return answer(() -> googleAuthorization(service, queryParam("next")));
  }

  /**
   * Where a caller is sent to sign in with Google.
   *
   * <p>Static because two endpoints answer for it. redash registers the same handler at
   * `/oauth/google` and at `/<org_slug>/oauth/google`, so that a multi-organisation
   * deployment can send somebody to their own organisation's sign-in; the second of those
   * is three segments and every three-segment path on this runtime belongs to
   * `StaticEndpoint`. An endpoint cannot answer through another endpoint's instance — the
   * request context belongs to the one the runtime called — so what they share is this.
   */
  static Object googleAuthorization(Service service, String next) {
    if (!service.settings().googleOauthEnabled()) {
      throw Http.notFound();
    }
    var where = Pages.nextPath(next);
    return Http.redirect(service.settings().text("REDASH_GOOGLE_AUTH_URL",
        "https://accounts.google.com/o/oauth2/v2/auth")
        + "?client_id=" + service.settings().text("REDASH_GOOGLE_CLIENT_ID", "")
        + "&response_type=code&scope=openid%20email%20profile"
        + "&redirect_uri=" + service.baseUrl() + "/oauth/google_callback"
        + "&state=" + java.net.URLEncoder.encode(where, StandardCharsets.UTF_8));
  }

  @Get("/oauth/google_callback")
  public HttpResponse googleCallback() {
    return answer(() -> {
      if (!service.settings().googleOauthEnabled()) {
        throw Http.notFound();
      }
      var email = queryParam("email");
      if (email == null) {
        throw Http.badRequest();
      }
      var domains = Json.asList(Json.asMap(service.requireOrg().get("settings"))
          .get("google_apps_domains"));
      int at = email.lastIndexOf('@');
      var domain = at < 0 ? "" : email.substring(at + 1);
      boolean known = service.userByEmail(email) != null;
      boolean isPublic = Boolean.TRUE.equals(ClientConfig.setting(service.settings(),
          service.currentOrg(), "is_public"));
      if (!domains.contains(domain) && !isPublic && !known) {
        return Http.html(403, Pages.error(service,
            "Your Google Apps account (" + domain + ") isn't allowed."));
      }
      var user = findOrCreate(email, queryParam("name"));
      if (user == null) {
        return Http.redirect("/");
      }
      return signedIn(user, Pages.nextPath(queryParam("state")), true);
    });
  }

  @Get("/saml/login")
  public HttpResponse samlLogin() {
    return answer(() -> {
      if (!Boolean.TRUE.equals(ClientConfig.setting(service.settings(), service.currentOrg(),
          "auth_saml_enabled"))) {
        throw Http.notFound();
      }
      var target = ClientConfig.setting(service.settings(), service.currentOrg(),
          "auth_saml_sso_url");
      return Http.redirect(String.valueOf(target));
    });
  }

  @Post("/saml/callback")
  public HttpResponse samlCallback(HttpEntity.Strict entity) {
    return answer(() -> {
      guardCsrf(entity);
      if (!Boolean.TRUE.equals(ClientConfig.setting(service.settings(), service.currentOrg(),
          "auth_saml_enabled"))) {
        throw Http.notFound();
      }
      var form = Pages.form(entity);
      var assertion = Saml.read(form.get("SAMLResponse"));
      if (assertion == null || assertion.email() == null) {
        return Http.html(400, Pages.error(service, "SAML login failed."));
      }
      var user = findOrCreate(assertion.email(),
          assertion.firstName() == null && assertion.lastName() == null
              ? assertion.email()
              : String.join(" ", assertion.firstName() == null ? "" : assertion.firstName(),
                  assertion.lastName() == null ? "" : assertion.lastName()).strip());
      if (user == null) {
        return Http.redirect("/");
      }
      if (!assertion.groups().isEmpty()) {
        reassignGroups(user, assertion.groups());
      }
      return signedIn(service.userById(user.get("id")), "/", true);
    });
  }

  /**
   * Signing in on the word of whatever is in front of this (SPEC-001 R19).
   *
   * <p>Three shapes answer the same redirect, and none of them is a refusal: the setting
   * being off, the header being absent, and the header holding the literal word `(null)` —
   * which some directory front ends write where a falsy value belongs, and which the
   * original special-cases by name. Each answers 302 to the index carrying an empty `next`,
   * which is what running the original under all three showed (question-log row 107).
   */
  @Get("/remote_user/login")
  public HttpResponse remoteUserLogin() {
    return answer(() -> {
      var next = Pages.nextPath(queryParam("next"));
      var email = service.settings().remoteUserLoginEnabled()
          ? header(service.settings().remoteUserHeader()) : null;
      if (email == null || email.isEmpty() || "(null)".equals(email)) {
        return Http.redirect("/?next=" + java.net.URLEncoder.encode(
            "/".equals(next) ? "" : next, StandardCharsets.UTF_8));
      }
      var user = findOrCreate(email, email);
      if (user == null) {
        return Http.redirect("/");
      }
      return signedIn(user, "/".equals(next) ? "/" : next, true);
    });
  }

  @Get("/ldap/login")
  public HttpResponse ldapLoginPage() {
    return answer(() -> {
      if (!service.settings().ldapLoginEnabled()) {
        throw Http.notFound();
      }
      return Http.html(loginHtml("/", "", List.of()));
    });
  }

  @Post("/ldap/login")
  public HttpResponse ldapLogin(HttpEntity.Strict entity) {
    return answer(() -> {
      guardCsrf(entity);
      if (!service.settings().ldapLoginEnabled()) {
        throw Http.notFound();
      }
      var form = Pages.form(entity);
      var directory = new Ldap(service.settings());
      var found = directory.authenticate(form.getOrDefault("email", ""),
          form.getOrDefault("password", ""));
      if (found == null) {
        return Http.html(loginHtml(Pages.nextPath(form.get("next")), form.get("email"),
            List.of("Incorrect credentials.")));
      }
      var user = findOrCreate(found.email(), found.displayName());
      if (user == null) {
        return Http.redirect("/");
      }
      return signedIn(user, Pages.nextPath(form.get("next")), true);
    });
  }

  // ------------------------------------------------------------------ shared

  private void requirePasswordLogin() {
    if (!Boolean.TRUE.equals(ClientConfig.setting(service.settings(), service.currentOrg(),
        "auth_password_login_enabled"))) {
      throw Http.notFound();
    }
  }

  /** Find the person this assertion names, or make them; refuse a disabled one. */
  private Map<String, Object> findOrCreate(String email, String name) {
    var user = service.userByEmail(email);
    if (user == null) {
      var defaultGroup = service.builtinGroup("default");
      return service.createUser(email, name == null ? email : name,
          defaultGroup == null ? List.of() : List.of(defaultGroup), null, false);
    }
    if (user.get("disabled_at") != null) {
      return null;
    }
    var updates = new LinkedHashMap<String, Object>();
    if (Boolean.TRUE.equals(user.get("is_invitation_pending"))) {
      updates.put("is_invitation_pending", false);
    }
    if (name != null && !name.isEmpty() && !name.equals(user.get("name"))) {
      updates.put("name", name);
    }
    return updates.isEmpty() ? user : store().update(Store.USERS, user.get("id"), updates);
  }

  /** The groups a SAML assertion names, plus the organisation's default group. */
  private void reassignGroups(Map<String, Object> user, List<String> names) {
    var wanted = new java.util.ArrayList<Object>();
    for (Map<String, Object> group : service.allGroups()) {
      if (names.contains(String.valueOf(group.get("name")))) {
        wanted.add(Service.number(group.get("id")));
      }
    }
    var defaultGroup = service.builtinGroup("default");
    if (defaultGroup != null && !wanted.contains(defaultGroup)) {
      wanted.add(defaultGroup);
    }
    store().update(Store.USERS, user.get("id"), Json.map("groups", wanted));
  }


  /** The redirect a successful sign-in answers, carrying the session cookie. */
  HttpResponse signedIn(Map<String, Object> user, String next, boolean remember) {
    return signedIn(user, next, remember, header("User-Agent"), remoteAddress());
  }

  /**
   * The same, told who is asking rather than reading it from a request context.
   *
   * <p>The setup page signs the first administrator in, and it is a different endpoint with
   * a request context of its own — reaching into this one's would throw. So the two values
   * a sign-in records are passed in.
   */
  HttpResponse signedIn(Map<String, Object> user, String next, boolean remember,
      String userAgent, String remoteAddress) {
    var identity = Crypto.sessionIdentity(Service.number(user.get("id")),
        String.valueOf(user.get("email")), String.valueOf(user.get("password_hash")));
    var cookie = Sessions.sign(service.settings(), identity);
    int maxAge = remember ? service.settings().rememberCookieDuration() * 86400 : -1;
    // Signing in is itself an event, and it carries no name — the source records it from a
    // signal that has the person but not the request's own actor, so the list falls back to
    // "User <id>" when it draws the row.
    service.recordRawEvent(Service.numberOrNull(user.get("id")),
        Json.map("action", "login", "object_type", "redash"), userAgent, remoteAddress);
    return Http.withCookie(decorate(Http.redirect(next)), service.settings(),
        service.settings().sessionCookieName(), cookie, maxAge);
  }

  private HttpResponse tokenPage(String template, String token, boolean invite,
      List<String> messages, int status) {
    var user = userFromToken(token);
    if (user == null) {
      return Http.html(400, Pages.error(service, invite
          ? "Your invite link is invalid. Bad signature. Please double-check the token."
          : "Your reset link is invalid. Bad signature. Please double-check the token."));
    }
    if (invite && Boolean.FALSE.equals(user.get("is_invitation_pending"))) {
      return Http.html(400, Pages.error(service, "This invitation has already been accepted."
          + " Please try resetting your password instead."));
    }
    var context = Pages.base(service, Json.map(
        "user", user,
        "show_google_openid", service.settings().googleOauthEnabled(),
        "google_auth_url", "/oauth/google",
        "show_saml_login", ClientConfig.setting(service.settings(), service.currentOrg(),
            "auth_saml_enabled"),
        "show_remote_user_login", service.settings().remoteUserLoginEnabled(),
        "show_ldap_login", service.settings().ldapLoginEnabled(),
        "get_flashed_messages", Jinja.helper(ignored -> messages == null ? List.of() : messages)));
    return Http.html(status, service.templates().render(template, context));
  }

  private HttpResponse acceptToken(String template, String token, boolean invite,
      HttpEntity.Strict entity) {
    var user = userFromToken(token);
    if (user == null) {
      return Http.html(400, Pages.error(service,
          "Your invite link is invalid. Bad signature. Please double-check the token."));
    }
    var form = Pages.form(entity);
    if (!form.containsKey("password")) {
      return tokenPage(template, token, invite, List.of("Bad Request"), 400);
    }
    var password = form.get("password");
    if (password.isEmpty()) {
      return tokenPage(template, token, invite, List.of("Cannot use empty password."), 400);
    }
    if (password.length() < 6) {
      return tokenPage(template, token, invite, List.of("Password length is too short (<6)."), 400);
    }
    var updates = new LinkedHashMap<String, Object>();
    updates.put("password_hash", Crypto.hashPassword(password));
    if (invite || Boolean.TRUE.equals(user.get("is_invitation_pending"))) {
      updates.put("is_invitation_pending", false);
    }
    var updated = store().update(Store.USERS, user.get("id"), updates);
    return signedIn(updated, "/", false);
  }

  /** The person a signed token names, or null when it is invalid, expired or unknown. */
  private Map<String, Object> userFromToken(String token) {
    var result = Crypto.readToken(service.settings().secretKey(), "itsdangerous", token,
        service.settings().invitationTokenMaxAge(), java.time.Instant.now().getEpochSecond());
    if (!(result instanceof Crypto.TokenResult.Valid valid)) {
      return null;
    }
    try {
      return service.userById(Long.parseLong(valid.payload()));
    } catch (NumberFormatException e) {
      return null;
    }
  }

  private String loginHtml(String next, String email, List<String> messages) {
    var context = Pages.base(service, Json.map(
        "next", next,
        "email", email == null ? "" : email,
        "show_google_openid", service.settings().googleOauthEnabled(),
        "google_auth_url", "/oauth/google?next="
            + java.net.URLEncoder.encode(next, StandardCharsets.UTF_8),
        "show_password_login", ClientConfig.setting(service.settings(), service.currentOrg(),
            "auth_password_login_enabled"),
        "show_saml_login", ClientConfig.setting(service.settings(), service.currentOrg(),
            "auth_saml_enabled"),
        "show_remote_user_login", service.settings().remoteUserLoginEnabled(),
        "show_ldap_login", service.settings().ldapLoginEnabled(),
        "get_flashed_messages", Jinja.helper(ignored -> messages)));
    return service.templates().render("login.html", context);
  }

  /** Kept so a caller can decode a form body without reaching into the entity. */
  static Map<String, String> decode(String body) {
    var out = new LinkedHashMap<String, String>();
    if (body == null || body.isBlank()) {
      return out;
    }
    for (String pair : body.split("&")) {
      int equals = pair.indexOf('=');
      if (equals < 0) {
        out.put(URLDecoder.decode(pair, StandardCharsets.UTF_8), "");
      } else {
        out.put(URLDecoder.decode(pair.substring(0, equals), StandardCharsets.UTF_8),
            URLDecoder.decode(pair.substring(equals + 1), StandardCharsets.UTF_8));
      }
    }
    return out;
  }
}

package io.akka.redash.api;

import akka.http.javadsl.model.HttpEntity;
import akka.http.javadsl.model.HttpResponse;
import akka.javasdk.annotations.Acl;
import akka.javasdk.annotations.http.Delete;
import akka.javasdk.annotations.http.Get;
import akka.javasdk.annotations.http.HttpEndpoint;
import akka.javasdk.annotations.http.Post;
import akka.javasdk.client.ComponentClient;
import io.akka.redash.application.Store;
import io.akka.redash.domain.Crypto;
import io.akka.redash.domain.Json;
import io.akka.redash.domain.Settings;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * `/api/users` — the people of an organisation (SPEC-001 R29 to R36).
 *
 * <p>Two asymmetries here are the source's and are reproduced rather than tidied. A search
 * matches the **name** case-insensitively and the **email** case-sensitively, because the
 * source's filter is `name ILIKE` or-ed with `email LIKE`. And a freshly created user is
 * answered without an API key while the same user read back by an administrator carries
 * one, because the create path serialises through the invitation helper and the read path
 * does not.
 */
@Acl(allow = @Acl.Matcher(principal = Acl.Principal.ALL))
@HttpEndpoint("/api/users")
public class UsersEndpoint extends ApiBase {

  public UsersEndpoint(ComponentClient client) {
    super(new Service(client));
  }

  @Get("")
  public HttpResponse list() {
    return answer(() -> {
      var caller = caller();
      caller.require("list_users");
      int page = intParam("page", 1);
      int pageSize = intParam("page_size", 25);
      var term = queryParam("q");
      boolean disabled = Boolean.TRUE.equals(booleanParam("disabled"));
      Boolean pending = booleanParam("pending");

      var users = new ArrayList<Map<String, Object>>();
      for (Map<String, Object> user : service.allUsers()) {
        if ((user.get("disabled_at") != null) != disabled) {
          continue;
        }
        if (pending != null
            && Boolean.TRUE.equals(user.get("is_invitation_pending")) != pending) {
          continue;
        }
        if (term != null && !term.isEmpty() && !matches(user, term)) {
          continue;
        }
        users.add(user);
      }

      if (term != null && !term.isEmpty()) {
        record(caller, Json.map("action", "search", "object_type", "user", "term", term,
            "pending", pending));
      } else {
        record(caller, Json.map("action", "list", "object_type", "user", "pending", pending));
      }

      var ordered = Listing.order(users, queryParam("order"), "-created_at",
          Listing.USER_ORDER, term == null || term.isEmpty());
      var groups = service.groupsById();
      return Listing.paginate(ordered, page, pageSize, user -> withGroups(user, groups));
    });
  }

  @Post("")
  public HttpResponse create(HttpEntity.Strict requestBody) {
    return answer(() -> {
      guardCsrf(requestBody);
      var caller = caller();
      caller.require("admin");
      var fields = body(requestBody);
      requireFields(fields, "name", "email");
      var email = String.valueOf(fields.get("email"));
      if (!email.contains("@")) {
        throw Http.badRequest("Bad email address.");
      }
      requireAllowedEmail(email);
      if (service.userByEmail(email) != null) {
        throw Http.badRequest("Email already taken.");
      }
      var defaultGroup = service.builtinGroup("default");
      var user = service.createUser(email, String.valueOf(fields.get("name")),
          defaultGroup == null ? List.of() : List.of(defaultGroup), null, true);
      record(caller, Json.map("action", "create", "object_id", user.get("id"),
          "object_type", "user"));
      return invited(user, !hasQueryParam("no_invite"));
    });
  }

  @Get("/{userId}")
  public HttpResponse get(String userId) {
    return answer(() -> {
      var caller = caller();
      long id = identifier(userId);
      if (!caller.has("list_users") && !Objects.equals(caller.id(), id)) {
        throw Http.forbidden();
      }
      var user = requireUser(id);
      record(caller, Json.map("action", "view", "object_id", userId, "object_type", "user"));
      return Serializers.user(user, caller.isAdminOrOwner(id));
    });
  }

  @Post("/{userId}")
  public HttpResponse update(String userId, HttpEntity.Strict requestBody) {
    return answer(() -> {
      guardCsrf(requestBody);
      var caller = caller();
      long id = identifier(userId);
      caller.requireAdminOrOwner(id);
      var user = requireUser(id);
      var request = only(body(requestBody), "email", "name", "password", "old_password",
          "group_ids");

      if (request.containsKey("password") && !request.containsKey("old_password")) {
        throw Http.forbidden("Must provide current password to update password.");
      }
      if (request.containsKey("old_password")
          && !Crypto.verifyPassword(String.valueOf(request.get("old_password")),
              (String) user.get("password_hash"))) {
        throw Http.forbidden("Incorrect current password.");
      }

      var updates = new LinkedHashMap<String, Object>();
      var changed = new ArrayList<String>();
      if (request.containsKey("password")) {
        updates.put("password_hash", Crypto.hashPassword(String.valueOf(request.get("password"))));
        changed.add("password");
      }
      if (request.containsKey("group_ids")) {
        if (!caller.has("admin")) {
          throw Http.forbidden("Must be admin to change groups membership.");
        }
        var wanted = new ArrayList<Object>();
        for (Object groupId : Json.asList(request.get("group_ids"))) {
          if (service.groupById(Service.number(groupId)) == null) {
            throw Http.badRequest("Group id " + groupId + " is invalid.");
          }
          wanted.add(Service.number(groupId));
        }
        if (!wanted.isEmpty()) {
          updates.put("groups", wanted);
          changed.add("group_ids");
        }
      }
      if (request.containsKey("email")) {
        var email = String.valueOf(request.get("email"));
        requireAllowedEmail(email);
        var existing = service.userByEmail(email);
        if (existing != null && !Objects.equals(existing.get("id"), user.get("id"))) {
          throw Http.badRequest("Email already taken.");
        }
        boolean moved = !email.toLowerCase(Locale.ROOT).equals(user.get("email"));
        updates.put("email", email.toLowerCase(Locale.ROOT));
        changed.add("email");
        if (moved && service.settings().emailServerIsConfigured()) {
          updates.put("is_email_verified", false);
        }
      }
      if (request.containsKey("name")) {
        updates.put("name", request.get("name"));
        changed.add("name");
      }
      updates.put("updated_at", Service.now());
      var updated = store().update(Store.USERS, user.get("id"), updates);
      if (updates.containsKey("email")) {
        service.forgetEmail(user.get("email"));
        service.indexEmail(updated.get("email"), updated.get("id"));
      }
      record(caller, Json.map("action", "edit", "object_id", user.get("id"),
          "object_type", "user", "updated_fields", changed));
      return Serializers.user(updated, caller.isAdminOrOwner(id));
    });
  }

  @Delete("/{userId}")
  public HttpResponse remove(String userId) {
    return answer(() -> {
      guardCsrf(null);
      var caller = caller();
      caller.require("admin");
      long id = identifier(userId);
      var user = requireUser(id);
      if (Objects.equals(caller.id(), id)) {
        throw Http.forbidden("You cannot delete your own account. Please ask another admin to"
            + " do this for you.");
      }
      if (!Boolean.TRUE.equals(user.get("is_invitation_pending"))) {
        throw Http.forbidden("You cannot delete activated users. Please disable the user"
            + " instead.");
      }
      store().delete(Store.USERS, id);
      service.forgetEmail(user.get("email"));
      return Serializers.user(user, caller.isAdminOrOwner(id));
    });
  }

  @Post("/{userId}/disable")
  public HttpResponse disable(String userId) {
    return answer(() -> {
      guardCsrf(null);
      var caller = caller();
      caller.require("admin");
      long id = identifier(userId);
      requireUser(id);
      if (Objects.equals(caller.id(), id)) {
        throw Http.forbidden("You cannot disable your own account. Please ask another admin to"
            + " do this for you.");
      }
      var updated = store().update(Store.USERS, id, Json.map("disabled_at", Service.now()));
      return Serializers.user(updated, caller.isAdminOrOwner(id));
    });
  }

  @Delete("/{userId}/disable")
  public HttpResponse enable(String userId) {
    return answer(() -> {
      guardCsrf(null);
      var caller = caller();
      caller.require("admin");
      long id = identifier(userId);
      requireUser(id);
      var updated = store().update(Store.USERS, id, Json.map("disabled_at", null));
      return Serializers.user(updated, caller.isAdminOrOwner(id));
    });
  }

  @Post("/{userId}/invite")
  public HttpResponse invite(String userId) {
    return answer(() -> {
      guardCsrf(null);
      var caller = caller();
      caller.require("admin");
      var user = requireUser(identifier(userId));
      return invited(user, true);
    });
  }

  @Post("/{userId}/reset_password")
  public HttpResponse resetPassword(String userId) {
    return answer(() -> {
      guardCsrf(null);
      var caller = caller();
      caller.require("admin");
      var user = requireUser(identifier(userId));
      if (user.get("disabled_at") != null) {
        throw Http.notFound("Not found");
      }
      var link = link("reset", user);
      service.sendPasswordResetEmail(user, link);
      return Json.map("reset_link", link);
    });
  }

  @Post("/{userId}/regenerate_api_key")
  public HttpResponse regenerateApiKey(String userId) {
    return answer(() -> {
      guardCsrf(null);
      var caller = caller();
      long id = identifier(userId);
      var user = requireUser(id);
      if (user.get("disabled_at") != null) {
        throw Http.notFound("Not found");
      }
      if (!caller.isAdminOrOwner(id)) {
        throw Http.forbidden();
      }
      var updated = store().update(Store.USERS, id, Json.map("api_key", newApiKey()));
      record(caller, Json.map("action", "regnerate_api_key", "object_id", id,
          "object_type", "user"));
      return Serializers.user(updated, true);
    });
  }

  // ------------------------------------------------------------------ shared

  /**
   * The document a created or re-invited user answers with.
   *
   * <p>The link is handed back in the body only when no invitation could be posted — which
   * is what makes an installation with no mail server usable at all.
   */
  private Map<String, Object> invited(Map<String, Object> user, boolean sendEmail) {
    var document = Serializers.user(user, false);
    var link = link("invite", user);
    if (service.settings().emailServerIsConfigured() && sendEmail) {
      service.sendInviteEmail(user, link);
    } else {
      document.put("invite_link", link);
    }
    return document;
  }

  private String link(String kind, Map<String, Object> user) {
    var token = Crypto.signToken(service.settings().secretKey(), "itsdangerous",
        String.valueOf(Service.number(user.get("id"))), java.time.Instant.now().getEpochSecond());
    return service.baseUrl() + "/" + kind + "/" + token;
  }


  private Map<String, Object> requireUser(long id) {
    var user = service.userById(id);
    if (user == null) {
      throw Http.notFound();
    }
    return user;
  }

  /** A name matched without regard to case, an address matched with it. */
  private static boolean matches(Map<String, Object> user, String term) {
    var name = String.valueOf(user.getOrDefault("name", ""));
    var email = String.valueOf(user.getOrDefault("email", ""));
    return name.toLowerCase(Locale.ROOT).contains(term.toLowerCase(Locale.ROOT))
        || email.contains(term);
  }

  private void requireAllowedEmail(String email) {
    int at = email.lastIndexOf('@');
    if (at < 0) {
      throw Http.badRequest("Bad email address.");
    }
    var domain = email.toLowerCase(Locale.ROOT).replaceAll("\\.+$", "").substring(at + 1);
    if (Settings.disposableDomains().contains(domain)
        || service.settings().blockedDomains().contains(domain)) {
      throw Http.badRequest("Bad email address.");
    }
  }

  /** A user's groups, expanded to the pairs the list endpoint answers (SPEC-001 R36). */
  private static Map<String, Object> withGroups(Map<String, Object> user,
      Map<Long, Map<String, Object>> groups) {
    var document = Serializers.user(user, false);
    var expanded = new ArrayList<Map<String, Object>>();
    var seen = new java.util.LinkedHashSet<Long>();
    for (Object groupId : Json.asList(user.get("groups"))) {
      long id = Service.number(groupId);
      if (!seen.add(id)) {
        continue;
      }
      var group = groups.get(id);
      if (group != null) {
        expanded.add(Json.map("id", group.get("id"), "name", group.get("name")));
      }
    }
    document.put("groups", expanded);
    return document;
  }
}

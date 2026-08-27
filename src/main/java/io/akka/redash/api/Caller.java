package io.akka.redash.api;

import io.akka.redash.domain.Access;
import java.util.List;
import java.util.Map;

/**
 * Who is making a request (SPEC-001 R11, R12).
 *
 * <p>Three kinds, and the difference between them decides most refusals. A **person** has
 * a session or their own API key and carries their groups and permissions. An **API user**
 * holds a key that belongs to an object rather than to a person: it carries the single
 * permission `view_query`, it may read the object its key belongs to and nothing else, and
 * it may never write. **Anonymous** is neither.
 *
 * @param user the person, or null for an API user or nobody
 * @param permissions what the caller may do, which for an API user is the one permission
 * @param groupIds the groups whose data sources the caller can reach
 * @param apiKey the key presented, or null
 * @param apiObject the object an API key belongs to — a query, or a shared dashboard
 * @param org the organisation the request resolved to
 */
public record Caller(
    Map<String, Object> user,
    List<String> permissions,
    List<Long> groupIds,
    String apiKey,
    Map<String, Object> apiObject,
    Map<String, Object> org) {

  public static Caller anonymous(Map<String, Object> org) {
    return new Caller(null, List.of(), List.of(), null, null, org);
  }

  public static Caller person(Map<String, Object> user, List<String> permissions,
      List<Long> groupIds, Map<String, Object> org) {
    return new Caller(user, permissions, groupIds, null, null, org);
  }

  public static Caller apiUser(String apiKey, Map<String, Object> apiObject, List<Long> groupIds,
      Map<String, Object> org) {
    return new Caller(null, List.of("view_query"), groupIds, apiKey, apiObject, org);
  }

  public boolean isAuthenticated() {
    return user != null || apiKey != null;
  }

  public boolean isApiUser() {
    return user == null && apiKey != null;
  }

  public Long id() {
    if (user == null) {
      return null;
    }
    var value = user.get("id");
    return value instanceof Number number ? number.longValue() : null;
  }

  public String name() {
    return user == null ? "ApiKey" : String.valueOf(user.get("name"));
  }

  /** What an event records as the actor: the person's address, or the key's own name. */
  public String actualUser() {
    return user == null ? "ApiKey: " + apiKey : String.valueOf(user.get("email"));
  }

  public long orgId() {
    var value = org == null ? null : org.get("id");
    return value instanceof Number number ? number.longValue() : 0;
  }

  public boolean has(String permission) {
    return permissions.contains(permission);
  }

  /** Refuse unless the permission is held, which is what most handlers open with. */
  public void require(String permission) {
    if (!has(permission)) {
      throw Http.forbidden();
    }
  }

  public void requireAny(String... candidates) {
    if (!Access.hasAnyPermission(permissions, List.of(candidates))) {
      throw Http.forbidden();
    }
  }

  public boolean isAdminOrOwner(Object ownerId) {
    var owner = ownerId instanceof Number number ? number.longValue() : null;
    return Access.isAdminOrOwner(permissions, owner, id());
  }

  public void requireAdminOrOwner(Object ownerId) {
    if (!isAdminOrOwner(ownerId)) {
      throw Http.forbidden();
    }
  }

  /** The wording the original uses for this one refusal, which a person sees in the UI. */
  public void requireAdminOrOwnerOfResource(Object ownerId) {
    if (!isAdminOrOwner(ownerId)) {
      throw Http.forbidden("You don't have permission to edit this resource.");
    }
  }
}

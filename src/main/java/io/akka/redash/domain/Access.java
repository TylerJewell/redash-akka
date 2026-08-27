package io.akka.redash.domain;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Who may see a thing and who may change it (SPEC-001 R21 to R28).
 *
 * <p>The whole decision is two levels and a comparison. An object's level is 1 when
 * **every** group the caller shares with it is view-only and 2 when any of them is not;
 * viewing needs level 1 and changing needs level 2. The consequence is worth stating
 * plainly because it surprises people: adding a caller to a second, unrestricted group
 * gives them write access to an object they could previously only read, without anything
 * about the object changing.
 */
public final class Access {

  public static final String VIEW = "view";
  public static final String MODIFY = "modify";
  public static final String DELETE = "delete";
  public static final List<String> ACCESS_TYPES = List.of(VIEW, MODIFY, DELETE);

  public static final boolean VIEW_ONLY = true;
  public static final boolean NOT_VIEW_ONLY = false;

  /** The twelve a regular group carries by default, in the order the source lists them. */
  public static final List<String> DEFAULT_PERMISSIONS = List.of(
      "create_dashboard", "create_query", "edit_dashboard", "edit_query", "view_query",
      "view_source", "execute_query", "list_users", "schedule_query", "list_dashboards",
      "list_alerts", "list_data_sources");

  public static final List<String> ADMIN_PERMISSIONS = List.of("admin", "super_admin");

  public static final String BUILTIN_GROUP = "builtin";
  public static final String REGULAR_GROUP = "regular";

  private Access() {}

  /**
   * The group-based decision.
   *
   * @param objectGroups group id to view-only flag, as the object carries it
   * @param permissions the caller's permissions
   * @param groupIds the caller's groups
   * @param needViewOnly whether only reading was wanted
   */
  public static boolean hasAccessToGroups(Map<Long, Boolean> objectGroups,
      Collection<String> permissions, Collection<Long> groupIds, boolean needViewOnly) {
    if (permissions.contains("admin")) {
      return true;
    }
    var matching = new java.util.LinkedHashSet<>(objectGroups.keySet());
    matching.retainAll(Set.copyOf(groupIds));
    if (matching.isEmpty()) {
      return false;
    }
    boolean everyMatchIsViewOnly = true;
    for (Long group : matching) {
      if (!Boolean.TRUE.equals(objectGroups.get(group))) {
        everyMatchIsViewOnly = false;
        break;
      }
    }
    int required = needViewOnly ? 1 : 2;
    int available = everyMatchIsViewOnly ? 1 : 2;
    return required <= available;
  }

  /**
   * The decision for a caller holding an API key rather than a session.
   *
   * <p>A key that matches grants exactly what was asked for when only viewing was asked
   * for, and nothing when changing was — so a shared link can never write, whatever it is
   * pointed at.
   */
  public static boolean hasAccessToObject(String objectApiKey, List<String> dashboardApiKeys,
      String presentedKey, boolean needViewOnly) {
    if (objectApiKey != null && objectApiKey.equals(presentedKey)) {
      return needViewOnly;
    }
    if (dashboardApiKeys != null && dashboardApiKeys.contains(presentedKey)) {
      return needViewOnly;
    }
    return false;
  }

  /** Whether the caller is the owner or an administrator, which most writes require. */
  public static boolean isAdminOrOwner(Collection<String> permissions, Long ownerId,
      Long callerId) {
    return (ownerId != null && ownerId.equals(callerId)) || permissions.contains("admin");
  }

  /** Whether every permission named is held. */
  public static boolean hasPermissions(Collection<String> held, Collection<String> wanted) {
    return held.containsAll(wanted);
  }

  /** Whether any one of the permissions named is held. */
  public static boolean hasAnyPermission(Collection<String> held, Collection<String> wanted) {
    for (String permission : wanted) {
      if (held.contains(permission)) {
        return true;
      }
    }
    return false;
  }
}

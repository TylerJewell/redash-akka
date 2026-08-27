package io.akka.redash.api;

import io.akka.redash.application.Store;
import io.akka.redash.domain.Access;
import io.akka.redash.domain.Json;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The access control list an object carries (SPEC-001 R27, R28).
 *
 * <p>redash registers one route for every object type — `/api/<type>/<id>/acl` — and lets
 * only two types through it. Here each of those two answers the route itself and this class
 * holds what they share, so a third type does not arrive by accident.
 *
 * <p>`object_type` on the wire is the source's table name (`queries`, `dashboards`) rather
 * than the model's, which is what the front end reads back.
 */
final class Permissions {

  private Permissions() {}

  /** The permissions on an object, grouped by access type, each as a list of people. */
  static Map<String, Object> list(Service service, String objectType, long objectId) {
    var out = new LinkedHashMap<String, Object>();
    for (Map<String, Object> permission : service.permissionsOn(objectType, objectId)) {
      var accessType = String.valueOf(permission.get("access_type"));
      @SuppressWarnings("unchecked")
      var people = (List<Object>) out.computeIfAbsent(accessType, ignored -> new ArrayList<>());
      var grantee = service.userById(permission.get("grantee_id"));
      if (grantee != null) {
        people.add(Serializers.user(grantee, false));
      }
    }
    return out;
  }

  /**
   * Grant one access type to one person. Granting the same thing twice by the same grantor
   * answers the row that is already there rather than making a second one.
   */
  static Map<String, Object> grant(Service service, String objectType, long objectId,
      Map<String, Object> request, Caller caller) {
    var accessType = String.valueOf(request.get("access_type"));
    if (!Access.ACCESS_TYPES.contains(accessType)) {
      throw Http.badRequest("Unknown access type.");
    }
    var grantee = service.userById(Service.number(request.get("user_id")));
    if (grantee == null) {
      throw Http.badRequest("User not found.");
    }
    for (Map<String, Object> existing : service.permissionsOn(objectType, objectId)) {
      if (accessType.equals(existing.get("access_type"))
          && Service.number(existing.get("grantee_id")) == Service.number(grantee.get("id"))
          && Service.number(existing.get("grantor_id")) == Service.number(caller.id())) {
        return asDocument(existing);
      }
    }
    var permission = service.store().insert(Store.ACCESS_PERMISSIONS, Json.map(
        "object_type", objectType,
        "object_id", objectId,
        "group_key", objectType + ":" + objectId,
        "access_type", accessType,
        "grantee_id", grantee.get("id"),
        "grantor_id", caller.id(),
        "created_at", Service.now()));
    return asDocument(permission);
  }

  /** Remove every permission of that access type held by that person on that object. */
  static void revoke(Service service, String objectType, long objectId,
      Map<String, Object> request) {
    var grantee = service.userById(Service.number(request.get("user_id")));
    if (grantee == null) {
      throw Http.badRequest("User not found.");
    }
    var accessType = request.get("access_type");
    for (Map<String, Object> permission : service.permissionsOn(objectType, objectId)) {
      boolean sameGrantee = Service.number(permission.get("grantee_id"))
          == Service.number(grantee.get("id"));
      boolean sameType = accessType == null
          || String.valueOf(accessType).equals(permission.get("access_type"));
      if (sameGrantee && sameType) {
        service.store().delete(Store.ACCESS_PERMISSIONS, permission.get("id"));
      }
    }
  }

  static Map<String, Object> asDocument(Map<String, Object> permission) {
    return Json.map(
        "id", permission.get("id"),
        "object_type", permission.get("object_type"),
        "object_id", permission.get("object_id"),
        "access_type", permission.get("access_type"),
        "grantor", permission.get("grantor_id"),
        "grantee", permission.get("grantee_id"));
  }
}

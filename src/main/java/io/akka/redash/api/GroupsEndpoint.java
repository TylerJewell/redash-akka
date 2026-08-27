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
import io.akka.redash.domain.Access;
import io.akka.redash.domain.Json;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * `/api/groups` — the groups of an organisation, who is in them, and which data sources
 * they reach (SPEC-001 R37, R38, R50).
 *
 * <p>Two answers here read oddly until they are checked against the original. A group name
 * is not unique, so creating `analysts` twice makes two groups; and removing a data source
 * from a group answers `null` rather than the data source, because the source's handler
 * returns nothing at all.
 */
@Acl(allow = @Acl.Matcher(principal = Acl.Principal.ALL))
@HttpEndpoint("/api/groups")
public class GroupsEndpoint extends ApiBase {

  public GroupsEndpoint(ComponentClient client) {
    super(new Service(client));
  }

  @Get("")
  public HttpResponse list() {
    return answer(() -> {
      var caller = caller();
      var groups = new ArrayList<Map<String, Object>>();
      for (Map<String, Object> group : service.allGroups()) {
        if (caller.has("admin") || caller.groupIds().contains(Service.number(group.get("id")))) {
          groups.add(Serializers.group(group));
        }
      }
      record(caller, Json.map("action", "list", "object_id", "groups", "object_type", "group"));
      return groups;
    });
  }

  @Post("")
  public HttpResponse create(HttpEntity.Strict requestBody) {
    return answer(() -> {
      guardCsrf(requestBody);
      var caller = caller();
      caller.require("admin");
      var fields = body(requestBody);
      requireFields(fields, "name");
      var group = store().insert(Store.GROUPS, Json.map(
          "org_id", 1L,
          "name", fields.get("name"),
          "permissions", Access.DEFAULT_PERMISSIONS,
          "type", Access.REGULAR_GROUP,
          "created_at", Service.now()));
      record(caller, Json.map("action", "create", "object_id", group.get("id"),
          "object_type", "group"));
      return Serializers.group(group);
    });
  }

  @Get("/{groupId}")
  public HttpResponse get(String groupId) {
    return answer(() -> {
      var caller = caller();
      long id = identifier(groupId);
      if (!caller.has("admin") && !caller.groupIds().contains(id)) {
        throw Http.forbidden();
      }
      var group = requireGroup(id);
      record(caller, Json.map("action", "view", "object_id", groupId, "object_type", "group"));
      return Serializers.group(group);
    });
  }

  @Post("/{groupId}")
  public HttpResponse rename(String groupId, HttpEntity.Strict requestBody) {
    return answer(() -> {
      guardCsrf(requestBody);
      var caller = caller();
      caller.require("admin");
      var group = requireGroup(identifier(groupId));
      if (Access.BUILTIN_GROUP.equals(group.get("type"))) {
        throw Http.badRequest("Can't modify built-in groups.");
      }
      var fields = body(requestBody);
      requireFields(fields, "name");
      var updated = store().update(Store.GROUPS, group.get("id"),
          Json.map("name", fields.get("name")));
      record(caller, Json.map("action", "edit", "object_id", group.get("id"),
          "object_type", "group"));
      return Serializers.group(updated);
    });
  }

  @Delete("/{groupId}")
  public HttpResponse remove(String groupId) {
    return answer(() -> {
      guardCsrf(null);
      var caller = caller();
      caller.require("admin");
      long id = identifier(groupId);
      var group = requireGroup(id);
      if (Access.BUILTIN_GROUP.equals(group.get("type"))) {
        throw Http.badRequest("Can't delete built-in groups.");
      }
      for (Map<String, Object> member : members(id)) {
        var remaining = new ArrayList<>(Json.asList(member.get("groups")));
        remaining.removeIf(value -> Service.number(value) == id);
        store().update(Store.USERS, member.get("id"), Json.map("groups", remaining));
      }
      store().delete(Store.GROUPS, id);
      return null;
    });
  }

  // ------------------------------------------------------------------ members

  @Get("/{groupId}/members")
  public HttpResponse listMembers(String groupId) {
    return answer(() -> {
      var caller = caller();
      caller.require("list_users");
      long id = identifier(groupId);
      if (!caller.has("admin") && !caller.groupIds().contains(id)) {
        throw Http.forbidden();
      }
      return Serializers.each(members(id), user -> Serializers.user(user, false));
    });
  }

  @Post("/{groupId}/members")
  public HttpResponse addMember(String groupId, HttpEntity.Strict requestBody) {
    return answer(() -> {
      guardCsrf(requestBody);
      var caller = caller();
      caller.require("admin");
      var fields = body(requestBody);
      requireFields(fields, "user_id");
      long id = identifier(groupId);
      var group = requireGroup(id);
      var user = service.userById(Service.number(fields.get("user_id")));
      if (user == null) {
        throw Http.notFound();
      }
      var groups = new ArrayList<>(Json.asList(user.get("groups")));
      groups.add(Service.number(group.get("id")));
      var updated = store().update(Store.USERS, user.get("id"), Json.map("groups", groups));
      record(caller, Json.map("action", "add_member", "object_id", group.get("id"),
          "object_type", "group", "member_id", user.get("id")));
      return Serializers.user(updated, false);
    });
  }

  @Delete("/{groupId}/members/{userId}")
  public HttpResponse removeMember(String groupId, String userId) {
    return answer(() -> {
      guardCsrf(null);
      var caller = caller();
      caller.require("admin");
      long id = identifier(groupId);
      var user = service.userById(identifier(userId));
      if (user == null) {
        throw Http.notFound();
      }
      var groups = new ArrayList<>(Json.asList(user.get("groups")));
      groups.removeIf(value -> Service.number(value) == id);
      store().update(Store.USERS, user.get("id"), Json.map("groups", groups));
      record(caller, Json.map("action", "remove_member", "object_id", groupId,
          "object_type", "group", "member_id", user.get("id")));
      return null;
    });
  }

  // ------------------------------------------------------------------ data sources

  @Get("/{groupId}/data_sources")
  public HttpResponse listDataSources(String groupId) {
    return answer(() -> {
      var caller = caller();
      caller.require("admin");
      long id = identifier(groupId);
      requireGroup(id);
      var out = new ArrayList<Map<String, Object>>();
      for (Map<String, Object> dataSource : service.allDataSources()) {
        var groups = service.groupsOf(dataSource);
        if (groups.containsKey(id)) {
          out.add(Serializers.dataSource(dataSource, service.runnerFor(dataSource), false,
              groups.get(id)));
        }
      }
      record(caller, Json.map("action", "list", "object_id", groupId, "object_type", "group"));
      return out;
    });
  }

  @Post("/{groupId}/data_sources")
  public HttpResponse addDataSource(String groupId, HttpEntity.Strict requestBody) {
    return answer(() -> {
      guardCsrf(requestBody);
      var caller = caller();
      caller.require("admin");
      var fields = body(requestBody);
      requireFields(fields, "data_source_id");
      long id = identifier(groupId);
      requireGroup(id);
      var dataSource = requireDataSource(Service.number(fields.get("data_source_id")));
      var groups = new LinkedHashMap<String, Object>(Json.asMap(dataSource.get("groups")));
      groups.put(String.valueOf(id), false);
      var updated = store().update(Store.DATA_SOURCES, dataSource.get("id"),
          Json.map("groups", groups));
      record(caller, Json.map("action", "add_data_source", "object_id", groupId,
          "object_type", "group", "member_id", dataSource.get("id")));
      return Serializers.dataSource(updated, service.runnerFor(updated), false, false);
    });
  }

  @Post("/{groupId}/data_sources/{dataSourceId}")
  public HttpResponse changeDataSourcePermission(String groupId, String dataSourceId,
      HttpEntity.Strict requestBody) {
    return answer(() -> {
      guardCsrf(requestBody);
      var caller = caller();
      caller.require("admin");
      var fields = body(requestBody);
      requireFields(fields, "view_only");
      long id = identifier(groupId);
      requireGroup(id);
      var dataSource = requireDataSource(identifier(dataSourceId));
      boolean viewOnly = Boolean.TRUE.equals(fields.get("view_only"));
      var groups = new LinkedHashMap<String, Object>(Json.asMap(dataSource.get("groups")));
      groups.put(String.valueOf(id), viewOnly);
      var updated = store().update(Store.DATA_SOURCES, dataSource.get("id"),
          Json.map("groups", groups));
      record(caller, Json.map("action", "change_data_source_permission", "object_id", groupId,
          "object_type", "group", "member_id", dataSource.get("id"), "view_only", viewOnly));
      return Serializers.dataSource(updated, service.runnerFor(updated), false, viewOnly);
    });
  }

  @Delete("/{groupId}/data_sources/{dataSourceId}")
  public HttpResponse removeDataSource(String groupId, String dataSourceId) {
    return answer(() -> {
      guardCsrf(null);
      var caller = caller();
      caller.require("admin");
      long id = identifier(groupId);
      requireGroup(id);
      var dataSource = requireDataSource(identifier(dataSourceId));
      var groups = new LinkedHashMap<String, Object>(Json.asMap(dataSource.get("groups")));
      groups.remove(String.valueOf(id));
      store().update(Store.DATA_SOURCES, dataSource.get("id"), Json.map("groups", groups));
      record(caller, Json.map("action", "remove_data_source", "object_id", groupId,
          "object_type", "group", "member_id", dataSource.get("id")));
      return null;
    });
  }

  // ------------------------------------------------------------------ shared

  private List<Map<String, Object>> members(long groupId) {
    var out = new ArrayList<Map<String, Object>>();
    for (Map<String, Object> user : service.allUsers()) {
      for (Object value : Json.asList(user.get("groups"))) {
        if (Service.number(value) == groupId) {
          out.add(user);
          break;
        }
      }
    }
    return out;
  }

  private Map<String, Object> requireGroup(long id) {
    var group = service.groupById(id);
    if (group == null) {
      throw Http.notFound();
    }
    return group;
  }

  private Map<String, Object> requireDataSource(long id) {
    var dataSource = service.dataSourceById(id);
    if (dataSource == null) {
      throw Http.notFound();
    }
    return dataSource;
  }
}

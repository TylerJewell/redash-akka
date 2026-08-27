package io.akka.redash.api;

import akka.http.javadsl.model.HttpEntity;
import akka.http.javadsl.model.HttpResponse;
import akka.javasdk.annotations.Acl;
import akka.javasdk.annotations.http.Get;
import akka.javasdk.annotations.http.HttpEndpoint;
import akka.javasdk.annotations.http.Post;
import akka.javasdk.client.ComponentClient;
import io.akka.redash.application.Store;
import io.akka.redash.destinations.Mail;
import io.akka.redash.domain.Access;
import io.akka.redash.domain.Configuration;
import io.akka.redash.domain.Crypto;
import io.akka.redash.domain.Json;
import io.akka.redash.domain.QueryHash;
import io.akka.redash.queryrunner.Registry;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * What the command line asks the service to do (SPEC-001 R148).
 *
 * <p>redash's `manage.py` opens the database directly, because it runs on the same host as
 * the server. The state here lives in the service, so the command line asks the service
 * instead — and this is the only route into it. Every call carries the instance secret in
 * a header; nothing here is reachable with a session or an API key, so a person who can
 * sign in cannot drop the tables.
 */
@Acl(allow = @Acl.Matcher(principal = Acl.Principal.ALL))
@HttpEndpoint("/api/cli")
public class CliEndpoint extends ApiBase {

  public CliEndpoint(ComponentClient client) {
    super(new Service(client));
  }

  /**
   * Refuse anything not carrying the instance secret.
   *
   * <p>Every handler below reads its request body **before** calling this. That order is
   * load-bearing rather than stylistic: a handler that answers before the request entity has
   * been consumed makes the runtime close the connection, and a client that has already sent
   * its body sees the close rather than the answer.
   */
  private void requireSecret() {
    var presented = header(io.akka.redash.cli.Client.HEADER);
    var expected = service.settings().secretKey();
    if (presented == null || !java.security.MessageDigest.isEqual(
        presented.getBytes(java.nio.charset.StandardCharsets.UTF_8),
        expected.getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
      throw Http.notFound();
    }
  }

  // ------------------------------------------------------------------ database

  @Post("/database/create_tables")
  public HttpResponse createTables(HttpEntity.Strict requestBody) {
    return answer(() -> {
      body(requestBody);
      requireSecret();
      // Nothing to create: the store has no schema. Answering rather than refusing keeps
      // the deployment script that calls it working unchanged.
      return Json.map("created", true);
    });
  }

  @Post("/database/drop_tables")
  public HttpResponse dropTables(HttpEntity.Strict requestBody) {
    return answer(() -> {
      body(requestBody);
      requireSecret();
      int dropped = 0;
      for (String table : TABLES) {
        for (Map<String, Object> row : store().all(table)) {
          store().delete(table, row.get("id"));
          dropped++;
        }
      }
      return Json.map("dropped", (long) dropped);
    });
  }

  @Post("/database/reencrypt")
  public HttpResponse reencrypt(HttpEntity.Strict requestBody) {
    return answer(() -> {
      var request = body(requestBody);
      requireSecret();
      var oldSecret = String.valueOf(request.get("old_secret"));
      var newSecret = String.valueOf(request.get("new_secret"));
      // Read through a store opened with the old key and write through one opened with
      // the new one. Every row that will not decrypt under the old key is left as it was
      // and counted, because a row written under a third key must not be overwritten with
      // an unreadable-marker document.
      var readSide = new Store(store().client(), oldSecret);
      var writeSide = new Store(store().client(), newSecret);
      long moved = 0;
      long skipped = 0;
      for (String table : List.of(Store.DATA_SOURCES, Store.DESTINATIONS)) {
        for (Map<String, Object> row : readSide.byOrg(table, 1L)) {
          var options = Json.asMap(row.get("options"));
          if (options.containsKey("__could_not_decrypt")) {
            skipped++;
            continue;
          }
          writeSide.update(table, row.get("id"), Json.map("options", options));
          moved++;
        }
      }
      return Json.map("reencrypted", moved, "skipped", skipped);
    });
  }

  // ------------------------------------------------------------------ users

  @Post("/users/create")
  public HttpResponse createUser(HttpEntity.Strict requestBody) {
    return answer(() -> {
      var request = body(requestBody);
      requireSecret();
      var groups = new ArrayList<Long>();
      var declared = String.valueOf(request.getOrDefault("groups", ""));
      if (declared != null && !declared.isEmpty() && !"null".equals(declared)) {
        for (String id : declared.split(",")) {
          if (!id.isBlank()) {
            groups.add(Long.parseLong(id.strip()));
          }
        }
      } else {
        var fallback = service.builtinGroup("default");
        if (fallback != null) {
          groups.add(fallback);
        }
      }
      if (Boolean.TRUE.equals(request.get("is_admin"))) {
        var admin = service.builtinGroup("admin");
        if (admin != null) {
          groups.add(admin);
        }
      }
      var password = request.get("password") == null
          ? null : String.valueOf(request.get("password"));
      var user = service.createUser(String.valueOf(request.get("email")),
          String.valueOf(request.get("name")), groups,
          Boolean.TRUE.equals(request.get("google_auth")) ? null : password, false);
      return Json.map("id", user.get("id"));
    });
  }

  @Post("/users/create_root")
  public HttpResponse createRoot(HttpEntity.Strict requestBody) {
    return answer(() -> {
      var request = body(requestBody);
      requireSecret();
      var email = String.valueOf(request.get("email"));
      if (service.userByEmail(email) != null) {
        return Json.map("already_exists", true);
      }
      if (service.isSetUp()) {
        var groups = new ArrayList<Long>();
        var admin = service.builtinGroup("admin");
        var standard = service.builtinGroup("default");
        if (admin != null) {
          groups.add(admin);
        }
        if (standard != null) {
          groups.add(standard);
        }
        var user = service.createUser(email, String.valueOf(request.get("name")), groups,
            String.valueOf(request.get("password")), false);
        return Json.map("id", user.get("id"));
      }
      var created = service.setup(String.valueOf(request.getOrDefault("org_name", "default")),
          String.valueOf(request.get("name")), email, String.valueOf(request.get("password")));
      return Json.map("id", Json.asMap(created.get("user")).get("id"));
    });
  }

  @Post("/users/delete")
  public HttpResponse deleteUser(HttpEntity.Strict requestBody) {
    return answer(() -> {
      var email = String.valueOf(body(requestBody).get("email"));
      requireSecret();
      var user = service.userByEmail(email);
      if (user == null) {
        return Json.map("deleted", 0L);
      }
      store().delete(Store.USERS, user.get("id"));
      service.forgetEmail(user.get("email"));
      return Json.map("deleted", 1L);
    });
  }

  @Post("/users/password")
  public HttpResponse setPassword(HttpEntity.Strict requestBody) {
    return answer(() -> {
      var request = body(requestBody);
      requireSecret();
      var user = service.userByEmail(String.valueOf(request.get("email")));
      if (user == null) {
        return Json.map("updated", false);
      }
      store().update(Store.USERS, user.get("id"), Json.map("password_hash",
          Crypto.hashPassword(String.valueOf(request.get("password")))));
      return Json.map("updated", true);
    });
  }

  @Post("/users/grant_admin")
  public HttpResponse grantAdmin(HttpEntity.Strict requestBody) {
    return answer(() -> {
      var email = String.valueOf(body(requestBody).get("email"));
      requireSecret();
      var user = service.userByEmail(email);
      if (user == null) {
        return Json.map("outcome", "missing");
      }
      var admin = service.builtinGroup("admin");
      var groups = new ArrayList<>(Json.asList(user.get("groups")));
      for (Object id : groups) {
        if (Service.number(id) == (admin == null ? -1 : admin)) {
          return Json.map("outcome", "already");
        }
      }
      groups.add(admin);
      store().update(Store.USERS, user.get("id"), Json.map("groups", groups));
      return Json.map("outcome", "updated");
    });
  }

  @Post("/users/invite")
  public HttpResponse inviteUser(HttpEntity.Strict requestBody) {
    return answer(() -> {
      var request = body(requestBody);
      requireSecret();
      var inviter = service.userByEmail(String.valueOf(request.get("inviter_email")));
      if (inviter == null) {
        return Json.map("outcome", "no-inviter");
      }
      var email = String.valueOf(request.get("email"));
      if (service.userByEmail(email) != null) {
        return Json.map("outcome", "exists");
      }
      var groups = new ArrayList<Long>();
      var declared = String.valueOf(request.getOrDefault("groups", ""));
      if (!declared.isEmpty() && !"null".equals(declared)) {
        for (String id : declared.split(",")) {
          if (!id.isBlank()) {
            groups.add(Long.parseLong(id.strip()));
          }
        }
      } else {
        var fallback = service.builtinGroup("default");
        if (fallback != null) {
          groups.add(fallback);
        }
      }
      if (Boolean.TRUE.equals(request.get("is_admin"))) {
        var admin = service.builtinGroup("admin");
        if (admin != null) {
          groups.add(admin);
        }
      }
      var user = service.createUser(email, String.valueOf(request.get("name")), groups,
          null, true);
      var token = Crypto.signToken(service.settings().secretKey(), "itsdangerous",
          String.valueOf(Service.number(user.get("id"))),
          java.time.Instant.now().getEpochSecond());
      service.sendInviteEmail(user, service.baseUrl() + "/invite/" + token);
      return Json.map("outcome", "sent", "id", user.get("id"));
    });
  }

  @Get("/users/list")
  public HttpResponse listUsers() {
    return answer(() -> {
      requireSecret();
      var org = service.currentOrg();
      var groups = service.groupsById();
      var rows = new ArrayList<Map<String, Object>>();
      var users = new ArrayList<>(service.allUsers());
      users.sort(java.util.Comparator.comparing(
          user -> String.valueOf(user.getOrDefault("name", ""))));
      for (Map<String, Object> user : users) {
        var names = new ArrayList<String>();
        for (Object id : Json.asList(user.get("groups"))) {
          var group = groups.get(Service.number(id));
          if (group != null) {
            names.add(String.valueOf(group.get("name")));
          }
        }
        rows.add(Json.map(
            "id", user.get("id"),
            "name", user.get("name"),
            "email", user.get("email"),
            "org", Json.map("slug", org == null ? "default" : org.get("slug"),
                "name", org == null ? "default" : org.get("name")),
            "active", user.get("disabled_at") == null,
            "groups", names));
      }
      return Json.map("users", rows);
    });
  }

  // ------------------------------------------------------------------ groups

  @Post("/groups/create")
  public HttpResponse createGroup(HttpEntity.Strict requestBody) {
    return answer(() -> {
      var request = body(requestBody);
      requireSecret();
      var permissions = permissionsFrom(request.get("permissions"));
      store().insert(Store.GROUPS, Json.map(
          "org_id", 1L, "name", request.get("name"), "permissions", permissions,
          "type", Access.REGULAR_GROUP, "created_at", Service.now()));
      return Json.map("permissions", permissions);
    });
  }

  @Post("/groups/change_permissions")
  public HttpResponse changePermissions(HttpEntity.Strict requestBody) {
    return answer(() -> {
      var request = body(requestBody);
      requireSecret();
      var group = service.groupById(Long.parseLong(String.valueOf(request.get("group_id"))));
      if (group == null) {
        throw Http.notFound();
      }
      var permissions = permissionsFrom(request.get("permissions"));
      var previous = group.get("permissions");
      store().update(Store.GROUPS, group.get("id"), Json.map("permissions", permissions));
      return Json.map("previous", previous, "permissions", permissions);
    });
  }

  @Get("/groups/list")
  public HttpResponse listGroups() {
    return answer(() -> {
      requireSecret();
      var org = service.currentOrg();
      var rows = new ArrayList<Map<String, Object>>();
      var groups = new ArrayList<>(service.allGroups());
      groups.sort(java.util.Comparator.comparing(
          group -> String.valueOf(group.getOrDefault("name", ""))));
      for (Map<String, Object> group : groups) {
        var members = new ArrayList<String>();
        for (Map<String, Object> user : service.allUsers()) {
          for (Object id : Json.asList(user.get("groups"))) {
            if (Service.number(id) == Service.number(group.get("id"))) {
              members.add(String.valueOf(user.get("name")));
              break;
            }
          }
        }
        rows.add(Json.map(
            "id", group.get("id"), "name", group.get("name"), "type", group.get("type"),
            "org_slug", org == null ? "default" : org.get("slug"),
            "permissions", group.get("permissions"), "users", members));
      }
      return Json.map("groups", rows);
    });
  }

  // ------------------------------------------------------------------ data sources

  @Get("/ds/list")
  public HttpResponse listDataSources() {
    return answer(() -> {
      requireSecret();
      var rows = new ArrayList<Map<String, Object>>();
      for (Map<String, Object> dataSource : service.allDataSources()) {
        var type = service.runnerFor(dataSource);
        rows.add(Json.map(
            "id", dataSource.get("id"), "name", dataSource.get("name"),
            "type", dataSource.get("type"),
            "options", type == null ? dataSource.get("options")
                : type.mask(Json.asMap(dataSource.get("options")))));
      }
      return Json.map("data_sources", rows);
    });
  }

  @Get("/ds/list_types")
  public HttpResponse listTypes() {
    return answer(() -> {
      requireSecret();
      var names = new ArrayList<String>(
          Registry.registered(service.settings()).keySet());
      names.sort(java.util.Comparator.naturalOrder());
      return Json.map("types", names);
    });
  }

  @Post("/ds/test")
  public HttpResponse testDataSource(HttpEntity.Strict requestBody) {
    return answer(() -> {
      requireSecret();
      var dataSource = dataSourceNamed(String.valueOf(body(requestBody).get("name")));
      if (dataSource == null) {
        return Json.map("found", false);
      }
      var runner = service.runnerFor(dataSource);
      var failure = runner == null ? "Unknown data source type."
          : runner.testConnection(Json.asMap(dataSource.get("options")));
      return Json.map("found", true, "id", dataSource.get("id"),
          "ok", failure == null, "message", failure);
    });
  }

  @Post("/ds/new")
  public HttpResponse newDataSource(HttpEntity.Strict requestBody) {
    return answer(() -> {
      var request = body(requestBody);
      requireSecret();
      var runner = Registry.registered(service.settings())
          .get(String.valueOf(request.get("type")));
      if (runner == null) {
        return Json.map("valid", false);
      }
      var options = Configuration.withoutNulls(
          Json.asMap(Json.loads(String.valueOf(request.getOrDefault("options", "{}")))));
      if (!Configuration.isValid(options, runner.configurationSchema())) {
        return Json.map("valid", false);
      }
      var groups = new LinkedHashMap<String, Object>();
      var defaultGroup = service.builtinGroup("default");
      if (defaultGroup != null) {
        groups.put(String.valueOf(defaultGroup), false);
      }
      var dataSource = store().insert(Store.DATA_SOURCES, Json.map(
          "org_id", 1L, "name", request.get("name"), "type", runner.type(),
          "options", options, "groups", groups,
          "queue_name", "queries", "scheduled_queue_name", "scheduled_queries",
          "pause_reason", null, "created_at", Service.now()));
      return Json.map("valid", true, "id", dataSource.get("id"),
          "options", runner.mask(options));
    });
  }

  @Post("/ds/delete")
  public HttpResponse deleteDataSource(HttpEntity.Strict requestBody) {
    return answer(() -> {
      requireSecret();
      var dataSource = dataSourceNamed(String.valueOf(body(requestBody).get("name")));
      if (dataSource == null) {
        return Json.map("found", false);
      }
      store().delete(Store.DATA_SOURCES, dataSource.get("id"));
      return Json.map("found", true, "id", dataSource.get("id"));
    });
  }

  @Post("/ds/edit")
  public HttpResponse editDataSource(HttpEntity.Strict requestBody) {
    return answer(() -> {
      var request = body(requestBody);
      requireSecret();
      var dataSource = dataSourceNamed(String.valueOf(request.get("name")));
      if (dataSource == null) {
        return Json.map("found", false);
      }
      var changes = new ArrayList<Map<String, Object>>();
      var updates = new LinkedHashMap<String, Object>();
      if (request.get("new_name") != null) {
        changes.add(Json.map("field", "name", "previous", dataSource.get("name"),
            "current", request.get("new_name")));
        updates.put("name", request.get("new_name"));
      }
      if (request.get("type") != null) {
        changes.add(Json.map("field", "type", "previous", dataSource.get("type"),
            "current", request.get("type")));
        updates.put("type", request.get("type"));
      }
      if (request.get("options") != null) {
        var options = Json.asMap(Json.loads(String.valueOf(request.get("options"))));
        changes.add(Json.map("field", "options", "previous", dataSource.get("options"),
            "current", options));
        updates.put("options", options);
      }
      if (!updates.isEmpty()) {
        store().update(Store.DATA_SOURCES, dataSource.get("id"), updates);
      }
      return Json.map("found", true, "changes", changes);
    });
  }

  // ------------------------------------------------------------------ organisation

  @Post("/org/set_google_apps_domains")
  public HttpResponse setGoogleAppsDomains(HttpEntity.Strict requestBody) {
    return answer(() -> {
      requireSecret();
      var org = service.requireOrg();
      var domains = new ArrayList<String>();
      for (String domain : String.valueOf(body(requestBody).get("domains")).split(",")) {
        if (!domain.isBlank()) {
          domains.add(domain.strip());
        }
      }
      var settings = new LinkedHashMap<String, Object>(Json.asMap(org.get("settings")));
      settings.put(SettingsEndpoint.GOOGLE_APPS_DOMAINS, domains);
      store().update(Store.ORGANIZATIONS, org.get("id"), Json.map("settings", settings));
      return Json.map("domains", domains);
    });
  }

  @Get("/org/google_apps_domains")
  public HttpResponse googleAppsDomains() {
    return answer(() -> {
      requireSecret();
      var org = service.currentOrg();
      return Json.map("domains", org == null ? List.of()
          : Json.asMap(org.get("settings"))
              .getOrDefault(SettingsEndpoint.GOOGLE_APPS_DOMAINS, List.of()));
    });
  }

  @Post("/org/create")
  public HttpResponse createOrganization(HttpEntity.Strict requestBody) {
    return answer(() -> {
      var request = body(requestBody);
      requireSecret();
      if (service.isSetUp()) {
        throw Http.badRequest("An organization already exists.");
      }
      service.setup(String.valueOf(request.get("name")), "Admin", "admin@localhost", null);
      return Json.map("created", true);
    });
  }

  @Get("/org/list")
  public HttpResponse listOrganizations() {
    return answer(() -> {
      requireSecret();
      var org = service.currentOrg();
      return Json.map("organizations", org == null ? List.of()
          : List.of(Json.map("id", org.get("id"), "name", org.get("name"),
              "slug", org.get("slug"))));
    });
  }

  // ------------------------------------------------------------------ queries

  @Post("/queries/rehash")
  public HttpResponse rehash(HttpEntity.Strict requestBody) {
    return answer(() -> {
      body(requestBody);
      requireSecret();
      var changed = new ArrayList<Map<String, Object>>();
      for (Map<String, Object> query : service.allQueries()) {
        var previous = String.valueOf(query.get("query_hash"));
        var refreshed = service.refreshQueryHash(query);
        var current = String.valueOf(refreshed.get("query_hash"));
        if (!previous.equals(current)) {
          changed.add(Json.map("id", query.get("id"), "previous", previous,
              "current", current));
        }
      }
      return Json.map("changed", changed);
    });
  }

  @Post("/queries/add_tag")
  public HttpResponse addTag(HttpEntity.Strict requestBody) {
    return answer(() -> {
      var request = body(requestBody);
      requireSecret();
      var query = service.queryById(Long.parseLong(String.valueOf(request.get("query_id"))));
      if (query == null) {
        return Json.map("found", false);
      }
      var tags = new java.util.LinkedHashSet<Object>(Json.asList(query.get("tags")));
      tags.add(request.get("tag"));
      store().update(Store.QUERIES, query.get("id"),
          Json.map("tags", new ArrayList<>(tags)));
      return Json.map("found", true);
    });
  }

  @Post("/queries/remove_tag")
  public HttpResponse removeTag(HttpEntity.Strict requestBody) {
    return answer(() -> {
      var request = body(requestBody);
      requireSecret();
      var query = service.queryById(Long.parseLong(String.valueOf(request.get("query_id"))));
      if (query == null) {
        return Json.map("outcome", "missing-query");
      }
      var tags = Json.asList(query.get("tags"));
      if (tags.isEmpty()) {
        return Json.map("outcome", "empty");
      }
      if (!tags.contains(request.get("tag"))) {
        return Json.map("outcome", "missing-tag");
      }
      var remaining = new java.util.LinkedHashSet<>(tags);
      remaining.remove(request.get("tag"));
      store().update(Store.QUERIES, query.get("id"),
          Json.map("tags", new ArrayList<>(remaining)));
      return Json.map("outcome", "removed");
    });
  }

  // ------------------------------------------------------------------ rq, mail

  @Get("/rq/healthcheck")
  public HttpResponse healthcheck() {
    return answer(() -> {
      requireSecret();
      var status = store().find(Store.STATE, io.akka.redash.application.Maintenance
          .REFRESH_STATUS);
      return Json.map("healthy", status != null);
    });
  }

  @Post("/send_test_mail")
  public HttpResponse sendTestMail(HttpEntity.Strict requestBody) {
    return answer(() -> {
      var request = body(requestBody);
      requireSecret();
      var server = service.mailServer();
      var address = request.get("email") == null
          ? server.defaultSender() : String.valueOf(request.get("email"));
      if (address == null || address.isEmpty()) {
        return Json.map("error", "No recipient: set REDASH_MAIL_DEFAULT_SENDER or pass one.");
      }
      var failure = Mail.send(server, List.of(address), "Test Message from Redash",
          "Test message.", "Test message.");
      return failure == null ? Json.map("sent", true) : Json.map("error", failure);
    });
  }

  // ------------------------------------------------------------------ shared

  private static final List<String> TABLES = List.of(
      Store.ORGANIZATIONS, Store.USERS, Store.GROUPS, Store.DATA_SOURCES, Store.QUERIES,
      Store.QUERY_RESULTS, Store.DASHBOARDS, Store.WIDGETS, Store.VISUALIZATIONS,
      Store.ALERTS, Store.ALERT_SUBSCRIPTIONS, Store.DESTINATIONS, Store.QUERY_SNIPPETS,
      Store.FAVORITES, Store.EVENTS, Store.API_KEYS, Store.ACCESS_PERMISSIONS,
      Store.CHANGES, Store.LOCKS, Store.TRACKER, Store.STATE);

  private List<String> permissionsFrom(Object declared) {
    if (declared == null || String.valueOf(declared).isBlank()) {
      return Access.DEFAULT_PERMISSIONS;
    }
    var out = new ArrayList<String>();
    for (String permission : String.valueOf(declared).split(",")) {
      if (!permission.isBlank()) {
        out.add(permission.strip());
      }
    }
    return out;
  }


  private Map<String, Object> dataSourceNamed(String name) {
    for (Map<String, Object> dataSource : service.allDataSources()) {
      if (name.equals(dataSource.get("name"))) {
        return dataSource;
      }
    }
    return null;
  }

  /** Kept so the hash of a query can be recomputed without an endpoint of its own. */
  static String hash(String text) {
    return QueryHash.of(text).toLowerCase(Locale.ROOT);
  }
}

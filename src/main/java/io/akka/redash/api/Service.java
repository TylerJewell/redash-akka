package io.akka.redash.api;

import akka.javasdk.client.ComponentClient;
import io.akka.redash.application.Store;
import io.akka.redash.domain.Access;
import io.akka.redash.domain.Crypto;
import io.akka.redash.domain.Json;
import io.akka.redash.domain.Parameters;
import io.akka.redash.domain.QueryHash;
import io.akka.redash.domain.Settings;
import io.akka.redash.queryrunner.Registry;
import io.akka.redash.queryrunner.RunnerType;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * What the endpoints do, minus the routing.
 *
 * <p>redash keeps this in three places — model methods, handler bodies and the permission
 * decorators — and the split is by file rather than by responsibility. Here it is one
 * layer: the endpoints parse and answer, this decides, `io.akka.redash.domain` holds the
 * rules that need no store, and `Store` holds the rows.
 */
public final class Service {

  private final Store store;
  private final Settings settings;

  public Service(ComponentClient client) {
    this(new Store(client), Settings.fromEnvironment());
  }

  public Service(Store store, Settings settings) {
    this.store = store;
    this.settings = settings;
  }

  public Store store() {
    return store;
  }

  public Settings settings() {
    return settings;
  }

  // ------------------------------------------------------------------ organisation

  /**
   * The organisation this request belongs to, created on first use.
   *
   * <p>redash makes the first organisation through its setup page or its command line and
   * has none until then; this rebuild does the same — {@link #setup} is what creates it —
   * but a read that arrives first must not fail, so an absent organisation answers null and
   * the caller decides.
   */
  public Map<String, Object> currentOrg() {
    return store.find(Store.ORGANIZATIONS, 1L);
  }

  public Map<String, Object> requireOrg() {
    var org = currentOrg();
    if (org == null) {
      throw Http.notFound();
    }
    return org;
  }

  /** Whether the instance has been set up at all, which decides where `/` redirects. */
  public boolean isSetUp() {
    return currentOrg() != null;
  }

  /** Create the organisation, its two builtin groups and the first administrator. */
  public Map<String, Object> setup(String orgName, String userName, String email,
      String password) {
    var now = Json.instant(Instant.now());
    var org = store.create(Store.ORGANIZATIONS, 1L, Json.map(
        "name", orgName, "slug", "default", "settings", Json.map(),
        "created_at", now, "updated_at", now));
    var admin = store.insert(Store.GROUPS, Json.map(
        "name", "admin", "permissions", Access.ADMIN_PERMISSIONS,
        "type", Access.BUILTIN_GROUP, "org_id", 1L, "created_at", now));
    var standard = store.insert(Store.GROUPS, Json.map(
        "name", "default", "permissions", Access.DEFAULT_PERMISSIONS,
        "type", Access.BUILTIN_GROUP, "org_id", 1L, "created_at", now));
    // The two builtin groups are written down by name as well as stored, for the same
    // reason a user is indexed by address: everything that needs "the default group" needs
    // it immediately after this call, and the list of groups comes from a view.
    store.put(Store.STATE, BUILTIN_GROUPS,
        Json.map("admin", admin.get("id"), "default", standard.get("id")));
    var user = createUser(email, userName, List.of(number(admin.get("id")), number(standard.get("id"))),
        password, false);
    return Json.map("org", org, "user", user);
  }

  // ------------------------------------------------------------------ users and groups

  public Map<String, Object> createUser(String email, String name, List<Long> groupIds,
      String password, boolean pending) {
    var now = Json.instant(Instant.now());
    var fields = new LinkedHashMap<String, Object>();
    fields.put("org_id", 1L);
    fields.put("name", name);
    fields.put("email", email == null ? null : email.toLowerCase(Locale.ROOT));
    fields.put("password_hash", password == null ? null : Crypto.hashPassword(password));
    fields.put("groups", groupIds);
    fields.put("api_key", Crypto.generateToken(40));
    fields.put("disabled_at", null);
    fields.put("is_invitation_pending", pending);
    fields.put("is_email_verified", true);
    fields.put("active_at", null);
    fields.put("created_at", now);
    fields.put("updated_at", now);
    var user = store.insert(Store.USERS, fields);
    indexEmail(user.get("email"), user.get("id"));
    return user;
  }

  /**
   * Remember which person holds an address, so that finding them is a read rather than a
   * scan.
   *
   * <p>This is not an optimisation. redash finds a person by address through a unique index
   * inside the transaction that created them, so a user created by the command line can
   * sign in on the next request. Here the list of users comes from a view, and a view is
   * updated *after* the write rather than with it — so without this, creating the first
   * administrator and immediately signing in fails, and does so only when the machine is
   * fast enough for the second request to arrive before the view has caught up. It was
   * found exactly that way: the walk passed for as long as every request took two seconds
   * and failed the moment they took fifteen milliseconds.
   */
  public void indexEmail(Object email, Object userId) {
    if (email == null) {
      return;
    }
    store.put(Store.STATE, emailKey(String.valueOf(email)), Json.map("user_id", userId));
  }

  public void forgetEmail(Object email) {
    if (email != null) {
      store.delete(Store.STATE, emailKey(String.valueOf(email)));
    }
  }

  static String emailKey(String email) {
    return "email:" + email.toLowerCase(Locale.ROOT);
  }

  public Map<String, Object> userById(Object id) {
    return store.find(Store.USERS, id);
  }

  public Map<String, Object> userByEmail(String email) {
    if (email == null) {
      return null;
    }
    var wanted = email.toLowerCase(Locale.ROOT);
    var indexed = store.find(Store.STATE, emailKey(wanted));
    if (indexed != null) {
      var user = store.find(Store.USERS, indexed.get("user_id"));
      if (user != null && wanted.equals(user.get("email"))) {
        return user;
      }
    }
    // A row written before the index existed, or one whose address moved without it: the
    // scan still answers, so the index is a shortcut rather than the only route.
    for (Map<String, Object> user : store.byOrg(Store.USERS, 1L)) {
      if (wanted.equals(user.get("email"))) {
        return user;
      }
    }
    return null;
  }

  public Map<String, Object> userByApiKey(String apiKey) {
    if (apiKey == null) {
      return null;
    }
    for (Map<String, Object> user : store.byOrg(Store.USERS, 1L)) {
      if (apiKey.equals(user.get("api_key"))) {
        return user;
      }
    }
    return null;
  }

  public List<Map<String, Object>> allUsers() {
    return store.byOrg(Store.USERS, 1L);
  }

  public List<Map<String, Object>> allGroups() {
    return store.byOrg(Store.GROUPS, 1L);
  }

  public Map<String, Object> groupById(Object id) {
    return store.find(Store.GROUPS, id);
  }

  /**
   * A user's permissions are the concatenation of their groups', in group order.
   *
   * <p>Each group is read by identity rather than found in the list of all groups. The list
   * comes from a view and a view lags its writes, so a person added to a group would be
   * refused for as long as the lag lasted — and, worse, an instance would refuse its own
   * administrator for the first moments after it was set up.
   */
  public List<String> permissionsOf(Map<String, Object> user) {
    var out = new ArrayList<String>();
    for (Object groupId : Json.asList(user.get("groups"))) {
      var group = store.find(Store.GROUPS, number(groupId));
      if (group == null) {
        continue;
      }
      for (Object permission : Json.asList(group.get("permissions"))) {
        out.add(String.valueOf(permission));
      }
    }
    return out;
  }

  /** Where the two builtin groups are written down by name. */
  public static final String BUILTIN_GROUPS = "builtin-groups";

  /**
   * The identity of a builtin group, read rather than scanned for.
   *
   * <p>Falls back to the list of groups, so an instance set up before this was written, or
   * a group somebody renamed, still resolves.
   */
  public Long builtinGroup(String name) {
    var written = store.find(Store.STATE, BUILTIN_GROUPS);
    if (written != null && written.get(name) != null) {
      return number(written.get(name));
    }
    for (Map<String, Object> group : allGroups()) {
      if (name.equals(group.get("name"))) {
        return number(group.get("id"));
      }
    }
    return null;
  }

  public Map<Long, Map<String, Object>> groupsById() {
    var out = new LinkedHashMap<Long, Map<String, Object>>();
    for (Map<String, Object> group : allGroups()) {
      out.put(number(group.get("id")), group);
    }
    return out;
  }

  public List<Long> groupIdsOf(Map<String, Object> user) {
    var out = new ArrayList<Long>();
    for (Object groupId : Json.asList(user.get("groups"))) {
      out.add(number(groupId));
    }
    return out;
  }

  // ------------------------------------------------------------------ data sources

  public Map<String, Object> dataSourceById(Object id) {
    return store.find(Store.DATA_SOURCES, id);
  }

  public List<Map<String, Object>> allDataSources() {
    return store.byOrg(Store.DATA_SOURCES, 1L);
  }

  /** The group-to-view-only map a data source carries, with its keys as numbers. */
  public Map<Long, Boolean> groupsOf(Map<String, Object> dataSource) {
    var out = new LinkedHashMap<Long, Boolean>();
    if (dataSource == null) {
      return out;
    }
    Json.asMap(dataSource.get("groups")).forEach((key, value) ->
        out.put(Long.parseLong(key), Boolean.TRUE.equals(value)));
    return out;
  }

  public RunnerType runnerFor(Map<String, Object> dataSource) {
    return dataSource == null ? null : Registry.get(String.valueOf(dataSource.get("type")));
  }

  // ------------------------------------------------------------------ access

  /** Whether the caller may reach a data source, and at what level. */
  public boolean hasAccessToDataSource(Caller caller, Map<String, Object> dataSource,
      boolean needViewOnly) {
    if (dataSource == null) {
      return false;
    }
    // An API user reaches a data source through groups like anybody else. The source's
    // check only takes the object route for an object that has a key of its own, and a
    // data source has none — so a caller holding a query's key reaches that query's data
    // source through the groups the key carries.
    return Access.hasAccessToGroups(groupsOf(dataSource), caller.permissions(),
        caller.groupIds(), needViewOnly);
  }

  /**
   * Whether the caller may reach a query. A query's groups are its data source's, and a
   * query with no data source has none — which refuses everybody who is not an admin.
   */
  public boolean hasAccessToQuery(Caller caller, Map<String, Object> query,
      boolean needViewOnly) {
    if (query == null) {
      return false;
    }
    if (caller.isApiUser()) {
      return Access.hasAccessToObject(String.valueOf(query.get("api_key")),
          dashboardApiKeysFor(number(query.get("id"))), caller.apiKey(), needViewOnly);
    }
    var dataSource = dataSourceById(query.get("data_source_id"));
    return Access.hasAccessToGroups(groupsOf(dataSource), caller.permissions(),
        caller.groupIds(), needViewOnly);
  }

  public void requireAccessToQuery(Caller caller, Map<String, Object> query,
      boolean needViewOnly) {
    if (!hasAccessToQuery(caller, query, needViewOnly)) {
      throw Http.forbidden();
    }
  }

  /** The keys of every shared dashboard a query appears on, which grant it read access. */
  public List<String> dashboardApiKeysFor(long queryId) {
    var out = new ArrayList<String>();
    var visualizations = store.byParent(Store.VISUALIZATIONS, queryId);
    var dashboardIds = new java.util.LinkedHashSet<Long>();
    for (Map<String, Object> visualization : visualizations) {
      for (Map<String, Object> widget :
          store.byGroupKey(Store.WIDGETS, "visualization:" + visualization.get("id"))) {
        dashboardIds.add(number(widget.get("dashboard_id")));
      }
    }
    for (Long dashboardId : dashboardIds) {
      var apiKey = activeApiKeyFor("dashboards", dashboardId);
      if (apiKey != null) {
        out.add(String.valueOf(apiKey.get("api_key")));
      }
    }
    return out;
  }

  public Map<String, Object> activeApiKeyFor(String objectType, long objectId) {
    for (Map<String, Object> key : store.byGroupKey(Store.API_KEYS, objectType + ":" + objectId)) {
      if (Boolean.TRUE.equals(key.get("active"))) {
        return key;
      }
    }
    return null;
  }

  public Map<String, Object> apiKeyByValue(String value) {
    if (value == null) {
      return null;
    }
    for (Map<String, Object> key : store.all(Store.API_KEYS)) {
      if (value.equals(key.get("api_key")) && Boolean.TRUE.equals(key.get("active"))) {
        return key;
      }
    }
    return null;
  }

  public Map<String, Object> queryByApiKey(String value) {
    if (value == null) {
      return null;
    }
    for (Map<String, Object> query : store.byOrg(Store.QUERIES, 1L)) {
      if (value.equals(query.get("api_key"))) {
        return query;
      }
    }
    return null;
  }

  // ------------------------------------------------------------------ queries

  public Map<String, Object> queryById(Object id) {
    return store.find(Store.QUERIES, id);
  }

  public List<Map<String, Object>> allQueries() {
    return store.byOrg(Store.QUERIES, 1L);
  }

  /**
   * Every query the caller may see (SPEC-001 R56): those on a data source one of their
   * groups reaches, plus their own drafts and nobody else's, and archived ones only when
   * asked for.
   */
  public List<Map<String, Object>> visibleQueries(Caller caller, boolean includeDrafts,
      boolean includeArchived) {
    var out = new ArrayList<Map<String, Object>>();
    for (Map<String, Object> query : allQueries()) {
      if (Boolean.TRUE.equals(query.get("is_archived")) != includeArchived) {
        continue;
      }
      if (!hasAccessToQuery(caller, query, Access.VIEW_ONLY)) {
        continue;
      }
      boolean draft = Boolean.TRUE.equals(query.get("is_draft"));
      if (draft && !includeDrafts && !java.util.Objects.equals(query.get("user_id"), caller.id())) {
        continue;
      }
      out.add(query);
    }
    return out;
  }

  /** The parameters a query declares, as the validator wants them. */
  public List<Map<String, Object>> parameterSchema(Map<String, Object> query) {
    var out = new ArrayList<Map<String, Object>>();
    for (Object parameter : Json.asList(Json.asMap(query.get("options")).get("parameters"))) {
      out.add(Json.asMap(parameter));
    }
    return out;
  }

  public Parameters parameterized(Map<String, Object> query) {
    return new Parameters(String.valueOf(query.get("query")), parameterSchema(query),
        this::dropdownValuesFor);
  }

  /** The `value` of every option a dropdown query offers, or null when it is detached. */
  public List<String> dropdownValuesFor(long queryId) {
    var query = queryById(queryId);
    if (query == null || query.get("data_source_id") == null) {
      return null;
    }
    var result = store.find(Store.QUERY_RESULTS, query.get("latest_query_data_id"));
    if (result == null) {
      return List.of();
    }
    var data = Json.asMap(result.get("data"));
    var columns = Json.asList(data.get("columns"));
    var first = columns.isEmpty() ? null : String.valueOf(Json.asMap(columns.get(0)).get("name"));
    var rows = new ArrayList<Map<String, Object>>();
    for (Object row : Json.asList(data.get("rows"))) {
      rows.add(Json.asMap(row));
    }
    var out = new ArrayList<String>();
    for (Map<String, Object> option : Parameters.dropdownValues(rows, first)) {
      out.add(String.valueOf(option.get("value")));
    }
    return out;
  }

  /** The whole dropdown document, which the two dropdown endpoints answer. */
  public List<Map<String, Object>> dropdownDocuments(long queryId) {
    var query = queryById(queryId);
    if (query == null || query.get("data_source_id") == null) {
      throw Http.badRequest(
          "This query is detached from any data source. Please select a different query.");
    }
    var result = store.find(Store.QUERY_RESULTS, query.get("latest_query_data_id"));
    if (result == null) {
      return List.of();
    }
    var data = Json.asMap(result.get("data"));
    var columns = Json.asList(data.get("columns"));
    var first = columns.isEmpty() ? null : String.valueOf(Json.asMap(columns.get(0)).get("name"));
    var rows = new ArrayList<Map<String, Object>>();
    for (Object row : Json.asList(data.get("rows"))) {
      rows.add(Json.asMap(row));
    }
    return Parameters.dropdownValues(rows, first);
  }

  /**
   * Recompute a query's hash from its own parameters' defaults and its automatic limit
   * (SPEC-001 R52), and point it at the newest result for that hash (R53).
   */
  public Map<String, Object> refreshQueryHash(Map<String, Object> query) {
    var dataSource = dataSourceById(query.get("data_source_id"));
    var runner = runnerFor(dataSource);
    var text = String.valueOf(query.get("query"));

    var defaults = new LinkedHashMap<String, Object>();
    for (Map<String, Object> parameter : parameterSchema(query)) {
      if (parameter.get("value") != null) {
        defaults.put(String.valueOf(parameter.get("name")), parameter.get("value"));
      }
    }
    if (!defaults.isEmpty()) {
      var applied = parameterized(query).apply(defaults);
      if (applied instanceof Parameters.Applied.Ok ok) {
        text = ok.text();
      }
      // An invalid parameter or a detached dropdown is swallowed: the previous hash stands.
    }
    boolean autoLimit = Boolean.TRUE.equals(Json.asMap(query.get("options")).get("apply_auto_limit"));
    if (runner != null) {
      text = runner.applyAutoLimit(text, autoLimit);
    }
    var hash = QueryHash.of(text);

    // Only the hash. Which result a query points at is written when a result is stored and
    // at no other time, which is what leaves an edited query pointing at a result that no
    // longer matches its text — the state R87 is about.
    return store.update(Store.QUERIES, query.get("id"), Json.map("query_hash", hash));
  }

  /**
   * The newest stored result for a cache key that is fresh enough (SPEC-001 R109).
   *
   * @param maxAge seconds of grace; -1 accepts any age and 0 accepts nothing
   */
  public Map<String, Object> latestResultFor(String queryHash, Object dataSourceId, long maxAge) {
    var key = QueryHash.cacheKey(queryHash, String.valueOf(dataSourceId));
    Map<String, Object> best = null;
    Instant bestAt = null;
    var now = Instant.now();
    for (Map<String, Object> result : store.byGroupKey(Store.QUERY_RESULTS, key)) {
      var retrievedAt = instant(result.get("retrieved_at"));
      if (retrievedAt == null) {
        continue;
      }
      if (maxAge >= 0 && retrievedAt.plusSeconds(maxAge).isBefore(now)) {
        continue;
      }
      if (bestAt == null || retrievedAt.isAfter(bestAt)) {
        best = result;
        bestAt = retrievedAt;
      }
    }
    return best;
  }

  // ------------------------------------------------------------------ schema

  /** Tables by name, and each table's columns by name (SPEC-001 R49). */
  public static List<Map<String, Object>> sortedSchema(List<Map<String, Object>> tables) {
    var out = new ArrayList<>(tables);
    out.sort(java.util.Comparator.comparing(table -> String.valueOf(table.get("name"))));
    var sorted = new ArrayList<Map<String, Object>>(out.size());
    for (Map<String, Object> table : out) {
      var columns = new ArrayList<Object>(Json.asList(table.get("columns")));
      columns.sort(java.util.Comparator.comparing(column -> column instanceof Map<?, ?> map
          ? String.valueOf(map.get("name")) : String.valueOf(column)));
      var copy = new LinkedHashMap<String, Object>(table);
      copy.put("columns", columns);
      sorted.add(copy);
    }
    return sorted;
  }

  // ------------------------------------------------------------------ events

  /**
   * Record what just happened (SPEC-001 R145). Every event carries the actor, the
   * organisation, the caller's client and address, and an instant.
   */
  /**
   * Record an event exactly as given, without the actor decoration a handler adds.
   *
   * <p>Signing in takes this route in the source: it is recorded from a signal that carries
   * the person but not the request, so no `user_name` is written and the event list falls
   * back to `User <id>` when it draws the row.
   */
  public void recordRawEvent(Long userId, Map<String, Object> options, String userAgent,
      String remoteAddress) {
    var properties = new LinkedHashMap<String, Object>(options);
    properties.remove("action");
    properties.remove("object_type");
    properties.remove("object_id");
    properties.put("user_agent", userAgent == null ? "" : userAgent);
    properties.put("ip", remoteAddress == null ? "" : remoteAddress);

    var fields = new LinkedHashMap<String, Object>();
    fields.put("org_id", 1L);
    fields.put("user_id", userId);
    fields.put("action", options.get("action"));
    fields.put("object_type", options.get("object_type"));
    fields.put("object_id", options.get("object_id") == null
        ? null : String.valueOf(options.get("object_id")));
    fields.put("additional_properties", properties);
    fields.put("created_at", Json.instant(Instant.now()));
    store.insert(Store.EVENTS, fields);
    forwardEvent(fields);
  }

  /**
   * Post a recorded event to every address `REDASH_EVENT_REPORTING_WEBHOOKS` names
   * (`redash/tasks/general.py`).
   *
   * <p>The document is `{"schema": "iglu:io.redash.webhooks/event/jsonschema/1-0-0",
   * "data": <the event>}`. A failure costs its own delivery and nothing else, which is what
   * the source's own `try` around each one does, and the event is already stored by the
   * time this runs — so a receiver being down does not lose the record.
   */
  private void forwardEvent(Map<String, Object> event) {
    var hooks = settings().eventReportingWebhooks();
    if (hooks.isEmpty()) {
      return;
    }
    var body = Json.dumps(Json.map(
        "schema", "iglu:io.redash.webhooks/event/jsonschema/1-0-0", "data", event));
    for (String hook : hooks) {
      try {
        var request = java.net.http.HttpRequest.newBuilder(java.net.URI.create(hook.strip()))
            .timeout(java.time.Duration.ofSeconds(30))
            .header("Content-Type", "application/json")
            .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body))
            .build();
        var response = WEBHOOKS.send(request,
            java.net.http.HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
          LOG.error("Failed posting to {}: {}", hook, response.body());
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      } catch (RuntimeException | java.io.IOException e) {
        LOG.error("Failed posting to {}", hook, e);
      }
    }
  }

  private static final java.net.http.HttpClient WEBHOOKS = java.net.http.HttpClient.newBuilder()
      .connectTimeout(java.time.Duration.ofSeconds(10))
      .followRedirects(java.net.http.HttpClient.Redirect.NEVER)
      .build();

  private static final org.slf4j.Logger LOG =
      org.slf4j.LoggerFactory.getLogger(Service.class);

  public void recordEvent(Caller caller, Map<String, Object> options, String userAgent,
      String remoteAddress) {
    var properties = new LinkedHashMap<String, Object>(options);
    properties.remove("action");
    properties.remove("object_type");
    properties.remove("object_id");
    if (caller.isApiUser()) {
      properties.put("api_key", caller.name());
    } else if (caller.user() != null) {
      properties.put("user_name", caller.user().get("name"));
    }
    properties.put("user_agent", userAgent == null ? "" : userAgent);
    properties.put("ip", remoteAddress == null ? "" : remoteAddress);

    var fields = new LinkedHashMap<String, Object>();
    fields.put("org_id", 1L);
    fields.put("user_id", caller.id());
    fields.put("action", options.get("action"));
    fields.put("object_type", options.get("object_type"));
    // The source's column is text, so every identifier comes back as a string — which is
    // visible where the event list puts it into `details.data_source`.
    fields.put("object_id", options.get("object_id") == null
        ? null : String.valueOf(options.get("object_id")));
    fields.put("additional_properties", properties);
    fields.put("created_at", Json.instant(Instant.now()));
    store.insert(Store.EVENTS, fields);
  }

  // ------------------------------------------------------------------ favourites

  /**
   * The key a favourite is stored under. It carries the object and the person, so the
   * source's unique index becomes the identity of the row and a second favourite of the
   * same thing overwrites rather than duplicating (SPEC-001 R144).
   */
  public static String favouriteKey(String objectType, Object objectId, Object userId) {
    return objectType + ":" + objectId + ":" + userId;
  }

  public Map<String, Object> favourite(String objectType, Object objectId, Object userId) {
    if (userId == null) {
      return null;
    }
    return store.find(Store.FAVORITES, favouriteKey(objectType, objectId, userId));
  }

  /** Every favourite of one kind held by one person, keyed by the object's identity. */
  public Map<Long, Map<String, Object>> favouritesOf(String objectType, Object userId) {
    var out = new LinkedHashMap<Long, Map<String, Object>>();
    if (userId == null) {
      return out;
    }
    for (Map<String, Object> favourite : store.byOwner(Store.FAVORITES, number(userId))) {
      if (objectType.equals(favourite.get("object_type"))) {
        out.put(number(favourite.get("object_id")), favourite);
      }
    }
    return out;
  }

  // ------------------------------------------------------------------ access control lists

  /** Every access permission granted on one object, in the order they were granted. */
  public List<Map<String, Object>> permissionsOn(String objectType, Object objectId) {
    return store.byGroupKey(Store.ACCESS_PERMISSIONS, objectType + ":" + objectId);
  }

  /** Whether one person holds one access type on one object (SPEC-001 R28). */
  public boolean hasAccessPermission(String objectType, Object objectId, String accessType,
      Object granteeId) {
    if (granteeId == null) {
      return false;
    }
    for (Map<String, Object> permission : permissionsOn(objectType, objectId)) {
      if (accessType.equals(permission.get("access_type"))
          && number(permission.get("grantee_id")) == number(granteeId)) {
        return true;
      }
    }
    return false;
  }

  /** Whether the caller may change an object: admin, owner, or a granted `modify`. */
  public boolean canModify(Caller caller, String objectType, Map<String, Object> object) {
    if (object == null) {
      return false;
    }
    if (caller.isAdminOrOwner(object.get("user_id"))) {
      return true;
    }
    return hasAccessPermission(objectType, object.get("id"), Access.MODIFY, caller.id());
  }

  // ------------------------------------------------------------------ mail

  /** Where this instance thinks it is, which every emailed link is built from. */
  public String baseUrl() {
    var configured = settings.host();
    return configured.isEmpty() ? "http://localhost:5000" : configured;
  }

  public io.akka.redash.destinations.Mail.Server mailServer() {
    return io.akka.redash.application.QueryExecutionWorkflow.mailServer(settings);
  }

  /** The renderer the four account emails go through, over the rebuild's own templates. */
  public Jinja templates() {
    return new Jinja(Jinja.resources());
  }

  public void sendInviteEmail(Map<String, Object> user, String inviteUrl) {
    var context = Json.map("invited", user, "inviter", Json.map("name", "Redash"),
        "org", currentOrg(), "invite_url", inviteUrl);
    send(user, "Redash invited you to join Redash", "emails/invite.html", "emails/invite.txt",
        context);
  }

  public void sendPasswordResetEmail(Map<String, Object> user, String resetLink) {
    send(user, "Reset your password", "emails/reset.html", "emails/reset.txt",
        Json.map("user", user, "reset_link", resetLink));
  }

  public void sendVerifyEmail(Map<String, Object> user, String verifyUrl) {
    send(user, user.get("name") + ", please verify your email address",
        "emails/verify.html", "emails/verify.txt", Json.map("user", user, "verify_url", verifyUrl));
  }

  public void sendDisabledEmail(Map<String, Object> user) {
    send(user, "Your Redash account is disabled", "emails/reset_disabled.html",
        "emails/reset_disabled.txt", Json.map("user", user));
  }

  private void send(Map<String, Object> user, String subject, String htmlTemplate,
      String textTemplate, Map<String, Object> context) {
    var renderer = templates();
    // An email template is a template like any other and reaches for the same helpers a
    // page does — the invitation names the instance's own address through `url_for`, which
    // is why the context it is rendered with is the shared one and not just its own values.
    var whole = Pages.base(this, context);
    io.akka.redash.destinations.Mail.send(mailServer(),
        List.of(String.valueOf(user.get("email"))), subject,
        renderer.render(htmlTemplate, whole), renderer.render(textTemplate, whole));
  }

  // ------------------------------------------------------------------ helpers

  public static long number(Object value) {
    return value instanceof Number n ? n.longValue() : 0;
  }

  public static Long numberOrNull(Object value) {
    return value instanceof Number n ? n.longValue() : null;
  }

  public static Instant instant(Object value) {
    if (value == null) {
      return null;
    }
    try {
      return Instant.parse(String.valueOf(value));
    } catch (RuntimeException e) {
      return null;
    }
  }

  /** Now, as the wire writes it — one place, so two rows written together agree. */
  public static String now() {
    return Json.instant(Instant.now());
  }
}

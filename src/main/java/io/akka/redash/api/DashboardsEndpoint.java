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
import io.akka.redash.domain.Text;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * `/api/dashboards` — boards of widgets, and how they are shared
 * (SPEC-001 R124 to R132).
 *
 * <p>A dashboard's version counts **up** on every write, unlike a query's, which is
 * whatever the caller sent back. So two writes carrying the same version disagree on the
 * second, and a write carrying none still moves it (question-log row 77).
 *
 * <p>A forked dashboard's widgets come back marked `restricted`, because the source
 * serialises the copy without saying who is asking and a widget with a visualisation is
 * only unrestricted for a named caller with access.
 */
@Acl(allow = @Acl.Matcher(principal = Acl.Principal.ALL))
@HttpEndpoint("/api/dashboards")
public class DashboardsEndpoint extends ApiBase {

  static final String TABLE = "dashboards";

  public DashboardsEndpoint(ComponentClient client) {
    super(new Service(client));
  }

  // ------------------------------------------------------------------ lists

  @Get("")
  public HttpResponse list() {
    return answer(() -> {
      var caller = caller();
      caller.require("list_dashboards");
      var term = queryParam("q");
      var dashboards = visible(caller, false);
      if (term != null && !term.isEmpty()) {
        dashboards = Listing.rankedSearch(dashboards, term);
        record(caller, Json.map("action", "search", "object_type", "dashboard", "term", term));
      } else {
        record(caller, Json.map("action", "list", "object_type", "dashboard"));
      }
      return paged(caller, dashboards, term);
    });
  }

  @Get("/my")
  public HttpResponse mine() {
    return answer(() -> {
      var caller = caller();
      caller.require("list_dashboards");
      var term = queryParam("q");
      var mine = new ArrayList<Map<String, Object>>();
      for (Map<String, Object> dashboard : store().byOrg(Store.DASHBOARDS, 1L)) {
        if (Boolean.TRUE.equals(dashboard.get("is_archived"))) {
          continue;
        }
        if (Objects.equals(Service.numberOrNull(dashboard.get("user_id")), caller.id())) {
          mine.add(dashboard);
        }
      }
      List<Map<String, Object>> selected = mine;
      if (term != null && !term.isEmpty()) {
        selected = Listing.rankedSearch(mine, term);
      }
      return paged(caller, selected, term);
    });
  }

  @Get("/favorites")
  public HttpResponse favorites() {
    return answer(() -> {
      var caller = caller();
      var term = queryParam("q");
      var favourites = service.favouritesOf("Dashboard", caller.id());
      var selected = new ArrayList<Map<String, Object>>();
      for (Map<String, Object> dashboard : visible(caller, false)) {
        if (favourites.containsKey(Service.number(dashboard.get("id")))) {
          selected.add(dashboard);
        }
      }
      List<Map<String, Object>> filtered = selected;
      if (term != null && !term.isEmpty()) {
        filtered = Listing.rankedSearch(selected, term);
      }
      record(caller, Json.map("action", "load_favorites", "object_type", "dashboard",
          "params", Json.map("q", term, "tags", queryParams("tags"),
              "page", (long) intParam("page", 1))));
      return paged(caller, filtered, term);
    });
  }

  @Get("/tags")
  public HttpResponse tags() {
    return answer(() -> {
      var caller = caller();
      caller.require("list_dashboards");
      return Listing.tagCounts(visible(caller, false));
    });
  }

  /** A shared board, read without a session (SPEC-001 R8, R130). */
  @Get("/public/{token}")
  public HttpResponse publicDashboard(String token) {
    return answer(() -> {
      if (Boolean.TRUE.equals(ClientConfig.setting(service.settings(), service.currentOrg(),
          "disable_public_urls"))) {
        throw Http.badRequest("Public URLs are disabled.");
      }
      var apiKey = service.apiKeyByValue(token);
      if (apiKey == null || !"dashboards".equals(apiKey.get("object_type"))) {
        throw Http.notFound();
      }
      var dashboard = store().find(Store.DASHBOARDS, apiKey.get("object_id"));
      if (dashboard == null) {
        throw Http.notFound();
      }
      var widgets = new ArrayList<Map<String, Object>>();
      for (Map<String, Object> widget :
          store().byParent(Store.WIDGETS, Service.number(dashboard.get("id")))) {
        var visualization = store().find(Store.VISUALIZATIONS, widget.get("visualization_id"));
        var query = visualization == null
            ? null : service.queryById(visualization.get("query_id"));
        widgets.add(Serializers.publicWidget(widget, visualization, query));
      }
      return Serializers.publicDashboard(
          Serializers.dashboard(dashboard, service.userById(dashboard.get("user_id")), null),
          widgets);
    });
  }

  // ------------------------------------------------------------------ one dashboard

  @Post("")
  public HttpResponse create(HttpEntity.Strict requestBody) {
    return answer(() -> {
      guardCsrf(requestBody);
      var caller = caller();
      caller.require("create_dashboard");
      var request = body(requestBody);
      requireFields(request, "name");
      var now = Service.now();
      var name = String.valueOf(request.get("name"));
      var dashboard = store().insert(Store.DASHBOARDS, Json.map(
          "org_id", 1L,
          "name", name,
          "slug", uniqueSlug(name),
          "user_id", caller.id(),
          "layout", List.of(),
          "dashboard_filters_enabled", false,
          "is_archived", false,
          "is_draft", true,
          "tags", List.of(),
          "options", Map.of(),
          "version", 1L,
          "created_at", now,
          "updated_at", now));
      return favourite(caller, serialize(caller, dashboard, false));
    });
  }

  @Get("/{dashboardId}")
  public HttpResponse get(String dashboardId) {
    return answer(() -> {
      var caller = caller();
      caller.require("list_dashboards");
      var dashboard = hasQueryParam("legacy")
          ? requireBySlug(dashboardId) : requireDashboard(identifier(dashboardId));
      var document = favourite(caller, serialize(caller, dashboard, true));
      var apiKey = service.activeApiKeyFor("dashboards", Service.number(dashboard.get("id")));
      if (apiKey != null) {
        document.put("public_url", service.baseUrl() + "/public/dashboards/"
            + apiKey.get("api_key"));
        document.put("api_key", apiKey.get("api_key"));
      }
      document.put("can_edit", service.canModify(caller, TABLE, dashboard));
      record(caller, Json.map("action", "view", "object_id", dashboard.get("id"),
          "object_type", "dashboard"));
      return document;
    });
  }

  @Post("/{dashboardId}")
  public HttpResponse update(String dashboardId, HttpEntity.Strict requestBody) {
    return answer(() -> {
      guardCsrf(requestBody);
      var caller = caller();
      caller.require("edit_dashboard");
      var dashboard = requireDashboard(identifier(dashboardId));
      if (!service.canModify(caller, TABLE, dashboard)) {
        throw Http.forbidden();
      }
      var updates = only(body(requestBody), "name", "layout", "version", "tags", "is_draft",
          "is_archived", "dashboard_filters_enabled", "options");
      if (updates.containsKey("version")
          && Service.number(updates.get("version")) != Service.number(dashboard.get("version"))) {
        throw Http.conflict();
      }
      updates.put("version", Service.number(dashboard.get("version")) + 1);
      updates.put("updated_at", Service.now());
      recordChange(caller, dashboard, updates);
      var updated = store().update(Store.DASHBOARDS, dashboard.get("id"), updates);
      var document = favourite(caller, serialize(caller, updated, true));
      record(caller, Json.map("action", "edit", "object_id", updated.get("id"),
          "object_type", "dashboard"));
      return document;
    });
  }

  @Delete("/{dashboardId}")
  public HttpResponse archive(String dashboardId) {
    return answer(() -> {
      guardCsrf(null);
      var caller = caller();
      caller.require("edit_dashboard");
      var dashboard = requireDashboard(identifier(dashboardId));
      var updates = Json.map("is_archived", true,
          "version", Service.number(dashboard.get("version")) + 1,
          "updated_at", Service.now());
      recordChange(caller, dashboard, updates);
      var updated = store().update(Store.DASHBOARDS, dashboard.get("id"), updates);
      var document = favourite(caller, serialize(caller, updated, true));
      record(caller, Json.map("action", "archive", "object_id", updated.get("id"),
          "object_type", "dashboard"));
      return document;
    });
  }

  @Post("/{dashboardId}/fork")
  public HttpResponse fork(String dashboardId) {
    return answer(() -> {
      guardCsrf(null);
      var caller = caller();
      caller.require("edit_dashboard");
      long id = identifier(dashboardId);
      var dashboard = requireDashboard(id);
      var now = Service.now();
      var copy = store().insert(Store.DASHBOARDS, Json.map(
          "org_id", 1L,
          "name", "Copy of (#" + dashboard.get("id") + ") " + dashboard.get("name"),
          "user_id", caller.id(),
          "layout", dashboard.get("layout"),
          "dashboard_filters_enabled", dashboard.get("dashboard_filters_enabled"),
          "is_archived", false,
          "is_draft", true,
          "tags", dashboard.get("tags"),
          "options", Map.of(),
          "version", 1L,
          "created_at", now,
          "updated_at", now));
      // The source sets the copy's slug to its own identity rather than slugifying the
      // "Copy of" name; the serialised slug is derived from the name either way.
      store().update(Store.DASHBOARDS, copy.get("id"),
          Json.map("slug", String.valueOf(copy.get("id"))));
      for (Map<String, Object> widget : store().byParent(Store.WIDGETS, id)) {
        store().insert(Store.WIDGETS, Json.map(
            "org_id", 1L,
            "parent_id", Service.number(copy.get("id")),
            "dashboard_id", Service.number(copy.get("id")),
            "visualization_id", widget.get("visualization_id"),
            "group_key", widget.get("visualization_id") == null
                ? "" : "visualization:" + widget.get("visualization_id"),
            "text", widget.get("text"),
            "width", widget.get("width"),
            "options", widget.get("options"),
            "created_at", now,
            "updated_at", now));
      }
      record(caller, Json.map("action", "fork", "object_id", dashboardId,
          "object_type", "dashboard"));
      // Serialised without a caller, so every widget carrying a visualisation is restricted.
      var refreshed = store().find(Store.DASHBOARDS, copy.get("id"));
      return favourite(caller, serialize(null, refreshed, true));
    });
  }

  // ------------------------------------------------------------------ sharing

  @Post("/{dashboardId}/share")
  public HttpResponse share(String dashboardId) {
    return answer(() -> {
      guardCsrf(null);
      var caller = caller();
      long id = identifier(dashboardId);
      var dashboard = requireDashboard(id);
      caller.requireAdminOrOwner(dashboard.get("user_id"));
      var existing = service.activeApiKeyFor("dashboards", id);
      var apiKey = existing != null ? existing : store().insert(Store.API_KEYS, Json.map(
          "org_id", 1L,
          "object_type", "dashboards",
          "object_id", id,
          "group_key", "dashboards:" + id,
          "api_key", newApiKey(),
          "active", true,
          "user_id", caller.id(),
          "created_at", Service.now()));
      record(caller, Json.map("action", "activate_api_key", "object_id", id,
          "object_type", "dashboard"));
      return Json.map(
          "public_url", service.baseUrl() + "/public/dashboards/" + apiKey.get("api_key"),
          "api_key", apiKey.get("api_key"));
    });
  }

  @Delete("/{dashboardId}/share")
  public HttpResponse unshare(String dashboardId) {
    return answer(() -> {
      guardCsrf(null);
      var caller = caller();
      long id = identifier(dashboardId);
      var dashboard = requireDashboard(id);
      caller.requireAdminOrOwner(dashboard.get("user_id"));
      var apiKey = service.activeApiKeyFor("dashboards", id);
      if (apiKey != null) {
        store().update(Store.API_KEYS, apiKey.get("id"), Json.map("active", false));
      }
      record(caller, Json.map("action", "deactivate_api_key", "object_id", id,
          "object_type", "dashboard"));
      return null;
    });
  }

  // ------------------------------------------------------------------ favourites

  @Post("/{dashboardId}/favorite")
  public HttpResponse addFavorite(String dashboardId) {
    return answer(() -> {
      guardCsrf(null);
      var caller = caller();
      long id = identifier(dashboardId);
      requireDashboard(id);
      var key = Service.favouriteKey("Dashboard", id, caller.id());
      if (store().find(Store.FAVORITES, key) == null) {
        store().put(Store.FAVORITES, key, Json.map(
            "org_id", 1L, "object_type", "Dashboard", "object_id", id,
            "group_key", "Dashboard:" + id, "user_id", caller.id(), "created_at", Service.now()));
      }
      record(caller, Json.map("action", "favorite", "object_id", id,
          "object_type", "dashboard"));
      return null;
    });
  }

  @Delete("/{dashboardId}/favorite")
  public HttpResponse removeFavorite(String dashboardId) {
    return answer(() -> {
      guardCsrf(null);
      var caller = caller();
      long id = identifier(dashboardId);
      requireDashboard(id);
      store().delete(Store.FAVORITES, Service.favouriteKey("Dashboard", id, caller.id()));
      record(caller, Json.map("action", "unfavorite", "object_id", id,
          "object_type", "dashboard"));
      return null;
    });
  }

  // ------------------------------------------------------------------ access control list

  @Get("/{dashboardId}/acl")
  public HttpResponse acl(String dashboardId) {
    return answer(() -> {
      caller();
      long id = identifier(dashboardId);
      requireDashboard(id);
      return Permissions.list(service, TABLE, id);
    });
  }

  @Post("/{dashboardId}/acl")
  public HttpResponse grant(String dashboardId, HttpEntity.Strict requestBody) {
    return answer(() -> {
      guardCsrf(requestBody);
      var caller = caller();
      long id = identifier(dashboardId);
      var dashboard = requireDashboard(id);
      caller.requireAdminOrOwner(dashboard.get("user_id"));
      var granted = Permissions.grant(service, TABLE, id, body(requestBody), caller);
      record(caller, Json.map("action", "grant_permission", "object_id", id,
          "object_type", TABLE, "grantee", granted.get("grantee"),
          "access_type", granted.get("access_type")));
      return granted;
    });
  }

  @Delete("/{dashboardId}/acl")
  public HttpResponse revoke(String dashboardId, HttpEntity.Strict requestBody) {
    return answer(() -> {
      guardCsrf(requestBody);
      var caller = caller();
      long id = identifier(dashboardId);
      var dashboard = requireDashboard(id);
      caller.requireAdminOrOwner(dashboard.get("user_id"));
      var request = body(requestBody);
      Permissions.revoke(service, TABLE, id, request);
      record(caller, Json.map("action", "revoke_permission", "object_id", id,
          "object_type", TABLE, "access_type", request.get("access_type"),
          "grantee_id", request.get("user_id")));
      return null;
    });
  }

  @Get("/{dashboardId}/acl/{accessType}")
  public HttpResponse checkAcl(String dashboardId, String accessType) {
    return answer(() -> {
      var caller = caller();
      long id = identifier(dashboardId);
      requireDashboard(id);
      return Json.map("response",
          service.hasAccessPermission(TABLE, id, accessType, caller.id()));
    });
  }

  // ------------------------------------------------------------------ shared

  /**
   * Serialise a dashboard.
   *
   * @param caller who is asking, or null where the source serialises without a caller —
   *     which is what marks every widget carrying a visualisation as restricted
   */
  Map<String, Object> serialize(Caller caller, Map<String, Object> dashboard,
      boolean withWidgets) {
    List<Map<String, Object>> widgets = null;
    if (withWidgets) {
      widgets = new ArrayList<>();
      for (Map<String, Object> widget :
          store().byParent(Store.WIDGETS, Service.number(dashboard.get("id")))) {
        widgets.add(widgetFor(caller, widget));
      }
    }
    return Serializers.dashboard(dashboard, service.userById(dashboard.get("user_id")), widgets);
  }

  private Map<String, Object> widgetFor(Caller caller, Map<String, Object> widget) {
    if (widget.get("visualization_id") == null) {
      return Serializers.widget(widget, null);
    }
    var visualization = store().find(Store.VISUALIZATIONS, widget.get("visualization_id"));
    var query = visualization == null ? null : service.queryById(visualization.get("query_id"));
    if (caller == null || !service.hasAccessToQuery(caller, query, Access.VIEW_ONLY)) {
      return Serializers.restrictedWidget(widget);
    }
    return Serializers.widget(widget,
        Serializers.visualization(visualization, queryDocument(query)));
  }

  /** The query a widget's visualisation carries, serialised the way the source does. */
  Map<String, Object> queryDocument(Map<String, Object> query) {
    if (query == null) {
      return null;
    }
    var author = service.userById(query.get("user_id"));
    var lastModifiedBy = service.userById(query.get("last_modified_by_id"));
    boolean isSafe = service.parameterized(query).isSafe();
    return Serializers.query(query, author, lastModifiedBy, isSafe, false, null, null);
  }

  private List<Map<String, Object>> visible(Caller caller, boolean includeArchived) {
    var out = new ArrayList<Map<String, Object>>();
    for (Map<String, Object> dashboard : store().byOrg(Store.DASHBOARDS, 1L)) {
      if (Boolean.TRUE.equals(dashboard.get("is_archived")) != includeArchived) {
        continue;
      }
      boolean owned = Objects.equals(Service.numberOrNull(dashboard.get("user_id")), caller.id());
      if (Boolean.TRUE.equals(dashboard.get("is_draft")) && !owned) {
        continue;
      }
      if (owned || caller.has("admin") || reachable(caller, dashboard)) {
        out.add(dashboard);
      }
    }
    return out;
  }

  /** A dashboard is reachable when any widget on it shows a query the caller can see. */
  private boolean reachable(Caller caller, Map<String, Object> dashboard) {
    var widgets = store().byParent(Store.WIDGETS, Service.number(dashboard.get("id")));
    if (widgets.isEmpty()) {
      return false;
    }
    for (Map<String, Object> widget : widgets) {
      if (widget.get("visualization_id") == null) {
        continue;
      }
      var visualization = store().find(Store.VISUALIZATIONS, widget.get("visualization_id"));
      var query = visualization == null ? null : service.queryById(visualization.get("query_id"));
      if (service.hasAccessToQuery(caller, query, Access.VIEW_ONLY)) {
        return true;
      }
    }
    return false;
  }

  private Map<String, Object> paged(Caller caller, List<Map<String, Object>> dashboards,
      String term) {
    var filtered = Listing.filterByTags(dashboards, queryParams("tags"));
    var favourites = service.favouritesOf("Dashboard", caller.id());
    var serialized = new ArrayList<Map<String, Object>>(filtered.size());
    for (Map<String, Object> dashboard : filtered) {
      var document = serialize(caller, dashboard, false);
      if (!caller.isApiUser()) {
        Serializers.withFavourite(document, favourites.get(Service.number(dashboard.get("id"))));
      }
      serialized.add(document);
    }
    var ordered = Listing.order(serialized, queryParam("order"), "-created_at",
        Listing.DASHBOARD_ORDER, term == null || term.isEmpty());
    return Listing.paginate(ordered, intParam("page", 1), intParam("page_size", 25), null);
  }

  private Map<String, Object> favourite(Caller caller, Map<String, Object> document) {
    if (caller == null || caller.isApiUser()) {
      return document;
    }
    return Serializers.withFavourite(document,
        service.favourite("Dashboard", document.get("id"), caller.id()));
  }

  /** A slug derived from the name, with a rising suffix where one is already taken. */
  private String uniqueSlug(String name) {
    var base = Text.slugify(name);
    var candidate = base;
    int suffix = 1;
    while (true) {
      boolean taken = false;
      for (Map<String, Object> dashboard : store().byOrg(Store.DASHBOARDS, 1L)) {
        if (candidate.equals(dashboard.get("slug"))) {
          taken = true;
          break;
        }
      }
      if (!taken) {
        return candidate;
      }
      suffix++;
      candidate = base + "_" + suffix;
    }
  }

  private void recordChange(Caller caller, Map<String, Object> dashboard,
      Map<String, Object> updates) {
    var moved = new LinkedHashMap<String, Object>();
    updates.forEach((name, value) -> {
      if (List.of("id", "created_at", "updated_at", "version").contains(name)) {
        return;
      }
      var previous = dashboard.get(name);
      if (!Objects.equals(previous, value)) {
        moved.put(name, Json.map("previous", previous, "current", value));
      }
    });
    if (moved.isEmpty()) {
      return;
    }
    store().insert(Store.CHANGES, Json.map(
        "object_type", "Dashboard",
        "object_id", dashboard.get("id"),
        "group_key", "Dashboard:" + dashboard.get("id"),
        "object_version", Service.number(dashboard.get("version")),
        "user_id", caller.id(),
        "change", moved,
        "created_at", Service.now()));
  }

  private Map<String, Object> requireDashboard(long id) {
    var dashboard = store().find(Store.DASHBOARDS, id);
    if (dashboard == null) {
      throw Http.notFound();
    }
    return dashboard;
  }

  private Map<String, Object> requireBySlug(String slug) {
    for (Map<String, Object> dashboard : store().byOrg(Store.DASHBOARDS, 1L)) {
      if (slug.equals(dashboard.get("slug"))) {
        return dashboard;
      }
    }
    throw Http.notFound();
  }
}

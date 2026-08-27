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
import io.akka.redash.domain.Sql;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * `/api/queries` — writing, finding, running and sharing a query
 * (SPEC-001 R51 to R66, R144, R27, R28).
 *
 * <p>The version field is the source's optimistic check and it does **not** count up by
 * itself: a caller sends the version it read, the handler refuses a mismatch with 409, and
 * the value the caller sent is written back. A dashboard's version does count up; a query's
 * does not, and the two are checked separately (question-log rows 76, 77).
 */
@Acl(allow = @Acl.Matcher(principal = Acl.Principal.ALL))
@HttpEndpoint("/api/queries")
public class QueriesEndpoint extends ApiBase {

  static final String TABLE = "queries";

  public QueriesEndpoint(ComponentClient client) {
    super(new Service(client));
  }

  // ------------------------------------------------------------------ lists

  @Get("")
  public HttpResponse list() {
    return answer(() -> {
      var caller = caller();
      caller.require("view_query");
      var term = queryParam("q");
      var queries = service.visibleQueries(caller, true, false);
      if (term != null && !term.isEmpty()) {
        queries = search(queries, term);
        record(caller, Json.map("action", "search", "object_type", "query", "term", term));
      } else {
        record(caller, Json.map("action", "list", "object_type", "query"));
      }
      return paged(caller, queries, term, true, false);
    });
  }

  @Get("/my")
  public HttpResponse mine() {
    return answer(() -> {
      var caller = caller();
      caller.require("view_query");
      var term = queryParam("q");
      var queries = new ArrayList<Map<String, Object>>();
      for (Map<String, Object> query : service.allQueries()) {
        if (Boolean.TRUE.equals(query.get("is_archived"))) {
          continue;
        }
        if (Objects.equals(Service.numberOrNull(query.get("user_id")), caller.id())) {
          queries.add(query);
        }
      }
      if (term != null && !term.isEmpty()) {
        queries = new ArrayList<>(search(queries, term));
      }
      return paged(caller, queries, term, true, false);
    });
  }

  @Get("/archive")
  public HttpResponse archived() {
    return answer(() -> {
      var caller = caller();
      caller.require("view_query");
      var term = queryParam("q");
      var queries = service.visibleQueries(caller, false, true);
      if (term != null && !term.isEmpty()) {
        queries = search(queries, term);
        record(caller, Json.map("action", "search", "object_type", "query", "term", term));
      } else {
        record(caller, Json.map("action", "list", "object_type", "query"));
      }
      return paged(caller, queries, term, true, false);
    });
  }

  @Get("/recent")
  public HttpResponse recent() {
    return answer(() -> {
      var caller = caller();
      caller.require("view_query");
      var mine = new ArrayList<Map<String, Object>>();
      for (Map<String, Object> query : service.allQueries()) {
        if (Boolean.TRUE.equals(query.get("is_archived"))) {
          continue;
        }
        if (Objects.equals(Service.numberOrNull(query.get("user_id")), caller.id())) {
          mine.add(query);
        }
      }
      mine.sort(Comparator.comparing(
          (Map<String, Object> query) -> String.valueOf(query.getOrDefault("updated_at", "")))
          .reversed());
      var out = new ArrayList<Map<String, Object>>();
      var favourites = service.favouritesOf("Query", caller.id());
      for (Map<String, Object> query : mine.subList(0, Math.min(10, mine.size()))) {
        out.add(favourite(caller, favourites, serialize(query, false, false, false, false)));
      }
      return out;
    });
  }

  @Get("/favorites")
  public HttpResponse favorites() {
    return answer(() -> {
      var caller = caller();
      var term = queryParam("q");
      var favourites = service.favouritesOf("Query", caller.id());
      var queries = new ArrayList<Map<String, Object>>();
      for (Map<String, Object> query : service.visibleQueries(caller, true, false)) {
        if (favourites.containsKey(Service.number(query.get("id")))) {
          queries.add(query);
        }
      }
      List<Map<String, Object>> selected = queries;
      if (term != null && !term.isEmpty()) {
        selected = search(queries, term);
      }
      record(caller, Json.map("action", "load_favorites", "object_type", "query",
          "params", Json.map("q", term, "tags", queryParams("tags"),
              "page", (long) intParam("page", 1))));
      return paged(caller, selected, term, true, false);
    });
  }

  @Get("/tags")
  public HttpResponse tags() {
    return answer(() -> {
      var caller = caller();
      return Listing.tagCounts(service.visibleQueries(caller, true, false));
    });
  }

  /** A redirect kept because the front end still follows it (SPEC-001 R61). */
  @Get("/search")
  public HttpResponse searchRedirect() {
    return answer(() -> {
      var caller = caller();
      caller.require("view_query");
      var term = queryParam("q");
      if (term == null || term.isEmpty()) {
        return List.of();
      }
      record(caller, Json.map("action", "search", "object_type", "query", "term", term));
      var drafts = hasQueryParam("include_drafts") ? "true" : "false";
      // The organisation's slug is in the address the source builds even in a single-org
      // deployment, because it builds it from the route rather than from the request.
      var slug = service.currentOrg() == null
          ? "default" : String.valueOf(service.currentOrg().get("slug"));
      var location = "/api/queries?q=" + java.net.URLEncoder.encode(term,
          java.nio.charset.StandardCharsets.UTF_8) + "&org_slug=" + slug
          + "&drafts=" + drafts;
      return Http.of(new Http.Refused(301, Map.of())).addHeader(
          akka.http.javadsl.model.headers.RawHeader.create("Location", location));
    });
  }

  @Post("/format")
  public HttpResponse format(HttpEntity.Strict requestBody) {
    return answer(() -> {
      guardCsrf(requestBody);
      caller();
      var request = body(requestBody);
      var text = request.get("query") == null ? "" : String.valueOf(request.get("query"));
      return Json.map("query", Sql.format(text));
    });
  }

  // ------------------------------------------------------------------ one query

  @Post("")
  public HttpResponse create(HttpEntity.Strict requestBody) {
    return answer(() -> {
      guardCsrf(requestBody);
      var caller = caller();
      caller.require("create_query");
      var request = new LinkedHashMap<>(body(requestBody));
      var dataSource = service.dataSourceById(request.remove("data_source_id"));
      if (dataSource == null) {
        // The source looks the data source up before it checks access, and its lookup
        // raises on a missing row rather than answering null — so a query created against
        // an identifier that names nothing is a server error, not a refusal (walk step 49).
        throw Http.serverError();
      }
      if (!service.hasAccessToDataSource(caller, dataSource, Access.NOT_VIEW_ONLY)) {
        throw Http.forbidden();
      }
      requireAccessToDropdownQueries(caller, request);
      for (String field : List.of("id", "created_at", "api_key", "visualizations",
          "latest_query_data", "last_modified_by")) {
        request.remove(field);
      }
      var now = Service.now();
      var fields = new LinkedHashMap<String, Object>();
      fields.put("org_id", 1L);
      fields.put("name", request.getOrDefault("name", "New Query"));
      fields.put("description", request.get("description"));
      fields.put("query", request.get("query"));
      fields.put("query_hash", null);
      fields.put("data_source_id", Service.numberOrNull(dataSource.get("id")));
      fields.put("latest_query_data_id", null);
      fields.put("api_key", newApiKey());
      fields.put("user_id", caller.id());
      fields.put("last_modified_by_id", caller.id());
      fields.put("is_archived", false);
      fields.put("is_draft", true);
      fields.put("schedule", request.get("schedule"));
      fields.put("schedule_failures", 0L);
      fields.put("options", request.getOrDefault("options", Map.of()));
      fields.put("tags", cleanTags(request.get("tags")));
      fields.put("version", 1L);
      fields.put("created_at", now);
      fields.put("updated_at", now);
      var query = store().insert(Store.QUERIES, fields);

      store().insert(Store.VISUALIZATIONS, Json.map(
          "org_id", 1L,
          "parent_id", Service.number(query.get("id")),
          "query_id", Service.number(query.get("id")),
          "type", "TABLE",
          "name", "Table",
          "description", "",
          "options", Map.of(),
          "created_at", now,
          "updated_at", now));

      var refreshed = service.refreshQueryHash(query);
      record(caller, Json.map("action", "create", "object_id", refreshed.get("id"),
          "object_type", "query"));
      return favourite(caller, serialize(refreshed, false, true, true, true));
    });
  }

  @Get("/{queryId}")
  public HttpResponse get(String queryId) {
    return answer(() -> {
      var caller = callerFor(identifier(queryId));
      caller.require("view_query");
      var query = requireQuery(identifier(queryId));
      service.requireAccessToQuery(caller, query, Access.VIEW_ONLY);
      var document = favourite(caller, serialize(query, false, true, true, true));
      document.put("can_edit", service.canModify(caller, TABLE, query));
      record(caller, Json.map("action", "view", "object_id", queryId, "object_type", "query"));
      return document;
    });
  }

  @Post("/{queryId}")
  public HttpResponse update(String queryId, HttpEntity.Strict requestBody) {
    return answer(() -> {
      guardCsrf(requestBody);
      var caller = caller();
      caller.require("edit_query");
      var query = requireQuery(identifier(queryId));
      if (!service.canModify(caller, TABLE, query)) {
        throw Http.forbidden();
      }
      var request = new LinkedHashMap<>(body(requestBody));
      requireAccessToDropdownQueries(caller, request);
      for (String field : List.of("id", "created_at", "api_key", "visualizations",
          "latest_query_data", "user", "last_modified_by", "org")) {
        request.remove(field);
      }
      if (request.containsKey("tags")) {
        request.put("tags", cleanTags(request.get("tags")));
      }
      if (request.containsKey("data_source_id")) {
        var dataSource = service.dataSourceById(request.get("data_source_id"));
        if (!service.hasAccessToDataSource(caller, dataSource, Access.NOT_VIEW_ONLY)) {
          throw Http.forbidden();
        }
        request.put("data_source_id", Service.numberOrNull(request.get("data_source_id")));
      }
      if (request.containsKey("version")
          && Service.number(request.get("version")) != Service.number(query.get("version"))) {
        throw Http.conflict();
      }
      request.put("last_modified_by_id", caller.id());
      request.put("updated_at", Service.now());
      recordChange(caller, query, request);
      var updated = store().update(Store.QUERIES, query.get("id"), request);
      var refreshed = service.refreshQueryHash(updated);
      return favourite(caller, serialize(refreshed, false, true, true, true));
    });
  }

  @Delete("/{queryId}")
  public HttpResponse archive(String queryId) {
    return answer(() -> {
      guardCsrf(null);
      var caller = caller();
      long id = identifier(queryId);
      var query = requireQuery(id);
      caller.requireAdminOrOwner(query.get("user_id"));
      for (Map<String, Object> visualization : store().byParent(Store.VISUALIZATIONS, id)) {
        for (Map<String, Object> widget :
            store().byGroupKey(Store.WIDGETS, "visualization:" + visualization.get("id"))) {
          store().delete(Store.WIDGETS, widget.get("id"));
        }
      }
      for (Map<String, Object> alert : store().byParent(Store.ALERTS, id)) {
        store().delete(Store.ALERTS, alert.get("id"));
      }
      var updates = Json.map("is_archived", true, "schedule", null,
          "updated_at", Service.now());
      recordChange(caller, query, updates);
      store().update(Store.QUERIES, id, updates);
      return null;
    });
  }

  @Post("/{queryId}/fork")
  public HttpResponse fork(String queryId) {
    return answer(() -> {
      guardCsrf(null);
      var caller = caller();
      caller.require("edit_query");
      long id = identifier(queryId);
      var query = requireQuery(id);
      var dataSource = service.dataSourceById(query.get("data_source_id"));
      if (!service.hasAccessToDataSource(caller, dataSource, Access.NOT_VIEW_ONLY)) {
        throw Http.forbidden();
      }
      var now = Service.now();
      var fields = new LinkedHashMap<String, Object>();
      fields.put("org_id", 1L);
      fields.put("name", "Copy of (#" + query.get("id") + ") " + query.get("name"));
      fields.put("description", query.get("description"));
      fields.put("query", query.get("query"));
      fields.put("query_hash", query.get("query_hash"));
      fields.put("data_source_id", query.get("data_source_id"));
      fields.put("latest_query_data_id", query.get("latest_query_data_id"));
      fields.put("api_key", newApiKey());
      fields.put("user_id", caller.id());
      fields.put("last_modified_by_id", caller.id());
      fields.put("is_archived", false);
      fields.put("is_draft", true);
      fields.put("schedule", null);
      fields.put("schedule_failures", 0L);
      fields.put("options", query.get("options"));
      fields.put("tags", query.get("tags"));
      fields.put("version", 1L);
      fields.put("created_at", now);
      fields.put("updated_at", now);
      var copy = store().insert(Store.QUERIES, fields);

      // Every visualisation is copied in identity order, and no extra `Table` is made:
      // the fork already has whichever the original had.
      for (Map<String, Object> visualization : store().byParent(Store.VISUALIZATIONS, id)) {
        store().insert(Store.VISUALIZATIONS, Json.map(
            "org_id", 1L,
            "parent_id", Service.number(copy.get("id")),
            "query_id", Service.number(copy.get("id")),
            "type", visualization.get("type"),
            "name", visualization.get("name"),
            "description", visualization.get("description"),
            "options", visualization.get("options"),
            "created_at", now,
            "updated_at", now));
      }
      record(caller, Json.map("action", "fork", "object_id", queryId, "object_type", "query"));
      return favourite(caller, serialize(copy, false, true, true, true));
    });
  }

  @Post("/{queryId}/regenerate_api_key")
  public HttpResponse regenerateApiKey(String queryId) {
    return answer(() -> {
      guardCsrf(null);
      var caller = caller();
      caller.require("edit_query");
      long id = identifier(queryId);
      var query = requireQuery(id);
      caller.requireAdminOrOwner(query.get("user_id"));
      var updated = store().update(Store.QUERIES, id, Json.map("api_key", newApiKey()));
      record(caller, Json.map("action", "regnerate_api_key", "object_id", id,
          "object_type", "query"));
      return favourite(caller, serialize(updated, false, true, true, false));
    });
  }

  // ------------------------------------------------------------------ favourites

  @Post("/{queryId}/favorite")
  public HttpResponse addFavorite(String queryId) {
    return answer(() -> {
      guardCsrf(null);
      var caller = caller();
      long id = identifier(queryId);
      var query = requireQuery(id);
      service.requireAccessToQuery(caller, query, Access.VIEW_ONLY);
      var key = Service.favouriteKey("Query", id, caller.id());
      if (store().find(Store.FAVORITES, key) == null) {
        store().put(Store.FAVORITES, key, Json.map(
            "org_id", 1L, "object_type", "Query", "object_id", id,
            "group_key", "Query:" + id, "user_id", caller.id(), "created_at", Service.now()));
      }
      record(caller, Json.map("action", "favorite", "object_id", id, "object_type", "query"));
      return null;
    });
  }

  @Delete("/{queryId}/favorite")
  public HttpResponse removeFavorite(String queryId) {
    return answer(() -> {
      guardCsrf(null);
      var caller = caller();
      long id = identifier(queryId);
      var query = requireQuery(id);
      service.requireAccessToQuery(caller, query, Access.VIEW_ONLY);
      store().delete(Store.FAVORITES, Service.favouriteKey("Query", id, caller.id()));
      record(caller, Json.map("action", "favorite", "object_id", id, "object_type", "query"));
      return null;
    });
  }

  // ------------------------------------------------------------------ access control list

  @Get("/{queryId}/acl")
  public HttpResponse acl(String queryId) {
    return answer(() -> {
      caller();
      long id = identifier(queryId);
      requireQuery(id);
      return Permissions.list(service, TABLE, id);
    });
  }

  @Post("/{queryId}/acl")
  public HttpResponse grant(String queryId, HttpEntity.Strict requestBody) {
    return answer(() -> {
      guardCsrf(requestBody);
      var caller = caller();
      long id = identifier(queryId);
      var query = requireQuery(id);
      caller.requireAdminOrOwner(query.get("user_id"));
      var granted = Permissions.grant(service, TABLE, id, body(requestBody), caller);
      record(caller, Json.map("action", "grant_permission", "object_id", id,
          "object_type", TABLE, "grantee", granted.get("grantee"),
          "access_type", granted.get("access_type")));
      return granted;
    });
  }

  @Delete("/{queryId}/acl")
  public HttpResponse revoke(String queryId, HttpEntity.Strict requestBody) {
    return answer(() -> {
      guardCsrf(requestBody);
      var caller = caller();
      long id = identifier(queryId);
      var query = requireQuery(id);
      caller.requireAdminOrOwner(query.get("user_id"));
      var request = body(requestBody);
      Permissions.revoke(service, TABLE, id, request);
      record(caller, Json.map("action", "revoke_permission", "object_id", id,
          "object_type", TABLE, "access_type", request.get("access_type"),
          "grantee_id", request.get("user_id")));
      return null;
    });
  }

  @Get("/{queryId}/acl/{accessType}")
  public HttpResponse checkAcl(String queryId, String accessType) {
    return answer(() -> {
      var caller = caller();
      long id = identifier(queryId);
      requireQuery(id);
      return Json.map("response",
          service.hasAccessPermission(TABLE, id, accessType, caller.id()));
    });
  }

  // ------------------------------------------------------------------ running and reading

  @Post("/{queryId}/results")
  public HttpResponse runSaved(String queryId, HttpEntity.Strict requestBody) {
    return answer(() -> {
      guardCsrf(requestBody);
      long id = identifier(queryId);
      var caller = callerFor(id);
      caller.requireAny("view_query", "execute_query");
      var request = body(requestBody);
      long maxAge = maxAgeOf(request);
      var query = requireQuery(id);
      var parameterized = service.parameterized(query);
      boolean safe = parameterized.isSafe();
      boolean autoLimit = request.containsKey("apply_auto_limit")
          ? Boolean.TRUE.equals(request.get("apply_auto_limit"))
          : Boolean.TRUE.equals(Json.asMap(query.get("options")).get("apply_auto_limit"));

      if (!service.hasAccessToQuery(caller, query, safe)) {
        if (!safe) {
          throw QueryRunning.refuse(caller.isApiUser()
              ? QueryRunning.UNSAFE_WHEN_SHARED : QueryRunning.UNSAFE_ON_VIEW_ONLY);
        }
        throw QueryRunning.refuse(QueryRunning.NO_PERMISSION);
      }
      return QueryRunning.run(service, caller, parameterized,
          Json.asMap(request.get("parameters")),
          service.dataSourceById(query.get("data_source_id")), id, autoLimit, maxAge,
          this::record);
    });
  }

  @Post("/{queryId}/refresh")
  public HttpResponse refresh(String queryId) {
    return answer(() -> {
      guardCsrf(null);
      long id = identifier(queryId);
      var caller = caller();
      if (caller.isApiUser()) {
        throw Http.forbidden("Please use a user API key.");
      }
      var query = requireQuery(id);
      service.requireAccessToQuery(caller, query, Access.NOT_VIEW_ONLY);
      var values = new LinkedHashMap<String, Object>();
      requestContext().queryParams().toMap().forEach((name, value) -> {
        if (name.startsWith("p_")) {
          values.put(name.substring(2), value);
        }
      });
      boolean autoLimit = Boolean.TRUE.equals(
          Json.asMap(query.get("options")).get("apply_auto_limit"));
      return QueryRunning.run(service, caller, service.parameterized(query), values,
          service.dataSourceById(query.get("data_source_id")), id, autoLimit, 0, this::record);
    });
  }

  /**
   * The suffixed form of the same address under POST, which the source cannot serve.
   *
   * <p>Its handler takes a query identifier and nothing else, and the suffix arrives as a
   * second argument — so the original answers 500 rather than running anything. The
   * unsuffixed `/results` is a literal and is matched by the route above this one, which is
   * the one that runs a saved query.
   */
  @Post("/{queryId}/{spec}")
  public HttpResponse suffixedResultByPost(String queryId, String spec,
      HttpEntity.Strict requestBody) {
    return answer(() -> {
      callerFor(identifier(queryId)).requireAny("view_query", "execute_query");
      throw Http.serverError();
    });
  }

  /**
   * `/api/queries/<id>/results` and its three suffixed forms.
   *
   * <p>One route rather than four, because a literal segment beats a parameter at the same
   * depth in this runtime and the suffix is part of the segment rather than its own.
   */
  @Get("/{queryId}/{spec}")
  public HttpResponse results(String queryId, String spec) {
    return answer(() -> {
      long id = identifier(queryId);
      var caller = callerFor(id);
      var split = Results.split(spec);
      if (!"results".equals(split.identity())) {
        throw Http.notFound();
      }
      caller.requireAny("view_query", "execute_query");
      var query = requireQuery(id);
      var result = store().find(Store.QUERY_RESULTS, query.get("latest_query_data_id"));
      if (result == null) {
        throw Http.notFound("No cached result found for this query.");
      }
      if (caller.isApiUser()
          && !Objects.equals(query.get("query_hash"), result.get("query_hash"))) {
        throw Http.notFound("No cached result found for this query.");
      }
      var dataSource = service.dataSourceById(result.get("data_source_id"));
      if (!canView(caller, dataSource)) {
        throw Http.forbidden();
      }
      if (caller.isApiUser()) {
        record(caller, Json.map("action", "api_get", "object_type", "query",
            "object_id", id, "file_type", split.filetype(), "api_key", caller.apiKey()));
      }
      return Results.respond(service, result, query, split.filetype(), false);
    });
  }

  /** `/api/queries/<id>/results/<result_id>.<filetype>`, which names both. */

  /**
   * The addresses the source registers this resource at that its own handler cannot serve.
   *
   * <p>flask-restful registers one class at five addresses and dispatches by method, so a
   * POST to any of them reaches a `post` that takes a query identifier and nothing else —
   * and the extra path arguments make the call fail before it does anything. The original
   * answers **500** with `{"message": "Internal Server Error"}`, which was run rather than
   * read (question-log row 104). Reproduced rather than repaired: a caller that gets a
   * refusal here where the original gives a fault is being told something different.
   */
  @Post("/{queryId}/results/{spec}")
  public HttpResponse namedResultByPost(String queryId, String spec,
      HttpEntity.Strict requestBody) {
    return answer(() -> {
      callerFor(identifier(queryId)).requireAny("view_query", "execute_query");
      throw Http.serverError();
    });
  }

  @Get("/{queryId}/results/{spec}")
  public HttpResponse namedResult(String queryId, String spec) {
    return answer(() -> {
      long id = identifier(queryId);
      var caller = callerFor(id);
      caller.requireAny("view_query", "execute_query");
      var query = requireQuery(id);
      var split = Results.split(spec);
      var result = store().find(Store.QUERY_RESULTS, identifier(split.identity()));
      if (result == null) {
        throw Http.notFound("No cached result found for this query.");
      }
      if (caller.isApiUser()
          && !Objects.equals(query.get("query_hash"), result.get("query_hash"))) {
        throw Http.notFound("No cached result found for this query.");
      }
      var dataSource = service.dataSourceById(result.get("data_source_id"));
      if (!canView(caller, dataSource)) {
        throw Http.forbidden();
      }
      if (caller.isApiUser()) {
        record(caller, Json.map("action", "api_get", "object_type", "query",
            "object_id", id, "file_type", split.filetype(), "api_key", caller.apiKey()));
      }
      return Results.respond(service, result, query, split.filetype(), true);
    });
  }

  @Get("/{queryId}/dropdown")
  public HttpResponse dropdown(String queryId) {
    return answer(() -> {
      var caller = caller();
      long id = identifier(queryId);
      var query = requireQuery(id);
      if (!canView(caller, service.dataSourceById(query.get("data_source_id")))) {
        throw Http.forbidden();
      }
      return service.dropdownDocuments(id);
    });
  }

  @Get("/{queryId}/dropdowns/{dropdownQueryId}")
  public HttpResponse dropdownOf(String queryId, String dropdownQueryId) {
    return answer(() -> {
      var caller = caller();
      long id = identifier(queryId);
      long dropdownId = identifier(dropdownQueryId);
      var query = requireQuery(id);
      service.requireAccessToQuery(caller, query, Access.VIEW_ONLY);
      var related = new ArrayList<Long>();
      for (Object parameter : Json.asList(Json.asMap(query.get("options")).get("parameters"))) {
        var declared = Json.asMap(parameter);
        if ("query".equals(declared.get("type"))) {
          related.add(Service.number(declared.get("queryId")));
        }
      }
      if (!related.contains(dropdownId)) {
        var dropdown = requireQuery(dropdownId);
        if (!canView(caller, service.dataSourceById(dropdown.get("data_source_id")))) {
          throw Http.forbidden();
        }
      }
      return service.dropdownDocuments(dropdownId);
    });
  }

  @Get("/{queryId}/jobs/{jobId}")
  public HttpResponse job(String queryId, String jobId) {
    return answer(() -> {
      callerFor(identifier(queryId));
      return Jobs.read(service, jobId);
    });
  }

  /**
   * The query-scoped form of cancelling a job.
   *
   * <p>The source registers one resource at two addresses — `/api/jobs/<id>` and
   * `/api/queries/<query_id>/jobs/<id>` — with both methods on each, so a caller holding
   * only a query's own API key can cancel the job it started. The identifier in the path is
   * what decides who may ask; the job it names is the same job either way.
   */
  @Delete("/{queryId}/jobs/{jobId}")
  public HttpResponse cancelJob(String queryId, String jobId) {
    return answer(() -> {
      guardCsrf(null);
      callerFor(identifier(queryId));
      return Jobs.cancel(service, jobId);
    });
  }

  // ------------------------------------------------------------------ shared

  /** Every dropdown query named in a request must be reachable by the caller. */
  private void requireAccessToDropdownQueries(Caller caller, Map<String, Object> request) {
    var ids = new java.util.LinkedHashSet<Long>();
    for (Object parameter : Json.asList(Json.asMap(request.get("options")).get("parameters"))) {
      var declared = Json.asMap(parameter);
      if ("query".equals(declared.get("type")) && declared.get("queryId") != null) {
        ids.add(Service.number(declared.get("queryId")));
      }
    }
    for (Long id : ids) {
      var dropdown = service.queryById(id);
      if (dropdown == null) {
        throw Http.badRequest("You are trying to associate a dropdown query that does not have"
            + " a matching group. Please verify the dropdown query id you are trying to"
            + " associate with this query.");
      }
      service.requireAccessToQuery(caller, dropdown, Access.VIEW_ONLY);
    }
  }

  static List<Object> cleanTags(Object tags) {
    var out = new ArrayList<Object>();
    for (Object tag : Json.asList(tags)) {
      if (tag != null && !String.valueOf(tag).isEmpty()) {
        out.add(tag);
      }
    }
    return out;
  }

  /** A change row, carrying every field that moved (SPEC-001 R55). */
  private void recordChange(Caller caller, Map<String, Object> query,
      Map<String, Object> updates) {
    var moved = new LinkedHashMap<String, Object>();
    updates.forEach((name, value) -> {
      if (List.of("id", "created_at", "updated_at", "version").contains(name)) {
        return;
      }
      var previous = query.get(name);
      if (!Objects.equals(previous, value)) {
        moved.put(name, Json.map("previous", previous, "current", value));
      }
    });
    if (moved.isEmpty()) {
      return;
    }
    store().insert(Store.CHANGES, Json.map(
        "object_type", "Query",
        "object_id", query.get("id"),
        "group_key", "Query:" + query.get("id"),
        "object_version", Service.number(query.get("version")),
        "user_id", caller.id(),
        "change", moved,
        "created_at", Service.now()));
  }

  Map<String, Object> serialize(Map<String, Object> query, boolean withStats,
      boolean withLastModifiedBy, boolean withUser, boolean withVisualizations) {
    var author = withUser ? service.userById(query.get("user_id")) : null;
    var lastModifiedBy = withLastModifiedBy
        ? service.userById(query.get("last_modified_by_id")) : null;
    var latest = withStats
        ? store().find(Store.QUERY_RESULTS, query.get("latest_query_data_id")) : null;
    List<Map<String, Object>> visualizations = null;
    if (withVisualizations) {
      visualizations = new ArrayList<>();
      for (Map<String, Object> item :
          store().byParent(Store.VISUALIZATIONS, Service.number(query.get("id")))) {
        visualizations.add(Serializers.visualization(item, null));
      }
    }
    boolean isSafe = service.parameterized(query).isSafe();
    return Serializers.query(query, author, lastModifiedBy, isSafe, withStats, latest,
        visualizations);
  }

  private Map<String, Object> favourite(Caller caller, Map<String, Object> document) {
    if (caller.isApiUser()) {
      return document;
    }
    return Serializers.withFavourite(document,
        service.favourite("Query", document.get("id"), caller.id()));
  }

  private Map<String, Object> favourite(Caller caller,
      Map<Long, Map<String, Object>> favourites, Map<String, Object> document) {
    if (caller.isApiUser()) {
      return document;
    }
    return Serializers.withFavourite(document,
        favourites.get(Service.number(document.get("id"))));
  }

  /** The list body: tags, order, page, and the favourite state each row carries. */
  private Map<String, Object> paged(Caller caller, List<Map<String, Object>> queries,
      String term, boolean withStats, boolean withLastModifiedBy) {
    var tags = queryParams("tags");
    var filtered = Listing.filterByTags(queries, tags);
    var favourites = service.favouritesOf("Query", caller.id());
    var serialized = new ArrayList<Map<String, Object>>(filtered.size());
    for (Map<String, Object> query : filtered) {
      serialized.add(favourite(caller, favourites,
          serialize(query, withStats, withLastModifiedBy, true, false)));
    }
    var ordered = Listing.order(serialized, queryParam("order"), "-created_at",
        Listing.QUERY_ORDER, term == null || term.isEmpty());
    return Listing.paginate(ordered, intParam("page", 1), intParam("page_size", 25), null);
  }

  /** Ranked or substring search, whichever the organisation is configured for. */
  private List<Map<String, Object>> search(List<Map<String, Object>> queries, String term) {
    boolean multiByte = Boolean.TRUE.equals(ClientConfig.setting(service.settings(),
        service.currentOrg(), "multi_byte_search_enabled"));
    return multiByte
        ? Listing.substringSearch(queries, term)
        : Listing.rankedSearch(queries, term);
  }

  private Map<String, Object> requireQuery(long id) {
    var query = service.queryById(id);
    if (query == null) {
      throw Http.notFound();
    }
    return query;
  }
}

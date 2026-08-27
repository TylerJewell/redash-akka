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
import io.akka.redash.domain.Json;
import java.util.ArrayList;
import java.util.Map;

/** `/api/query_snippets` — the fragments the query editor offers (SPEC-001 R143). */
@Acl(allow = @Acl.Matcher(principal = Acl.Principal.ALL))
@HttpEndpoint("/api/query_snippets")
public class QuerySnippetsEndpoint extends ApiBase {

  public QuerySnippetsEndpoint(ComponentClient client) {
    super(new Service(client));
  }

  @Get("")
  public HttpResponse list() {
    return answer(() -> {
      var caller = caller();
      record(caller, Json.map("action", "list", "object_type", "query_snippet"));
      var out = new ArrayList<Map<String, Object>>();
      for (Map<String, Object> snippet : store().byOrg(Store.QUERY_SNIPPETS, 1L)) {
        out.add(Serializers.snippet(snippet, service.userById(snippet.get("user_id"))));
      }
      return out;
    });
  }

  @Post("")
  public HttpResponse create(HttpEntity.Strict requestBody) {
    return answer(() -> {
      guardCsrf(requestBody);
      var caller = caller();
      var request = body(requestBody);
      requireFields(request, "trigger", "description", "snippet");
      var now = Service.now();
      var snippet = store().insert(Store.QUERY_SNIPPETS, Json.map(
          "org_id", 1L,
          "trigger", request.get("trigger"),
          "description", request.get("description"),
          "snippet", request.get("snippet"),
          "user_id", caller.id(),
          "created_at", now,
          "updated_at", now));
      record(caller, Json.map("action", "create", "object_id", snippet.get("id"),
          "object_type", "query_snippet"));
      return Serializers.snippet(snippet, caller.user());
    });
  }

  @Get("/{snippetId}")
  public HttpResponse get(String snippetId) {
    return answer(() -> {
      var caller = caller();
      var snippet = requireSnippet(identifier(snippetId));
      record(caller, Json.map("action", "view", "object_id", snippetId,
          "object_type", "query_snippet"));
      return Serializers.snippet(snippet, service.userById(snippet.get("user_id")));
    });
  }

  @Post("/{snippetId}")
  public HttpResponse update(String snippetId, HttpEntity.Strict requestBody) {
    return answer(() -> {
      guardCsrf(requestBody);
      var caller = caller();
      var snippet = requireSnippet(identifier(snippetId));
      caller.requireAdminOrOwner(snippet.get("user_id"));
      var updates = only(body(requestBody), "trigger", "description", "snippet");
      updates.put("updated_at", Service.now());
      var updated = store().update(Store.QUERY_SNIPPETS, snippet.get("id"), updates);
      record(caller, Json.map("action", "edit", "object_id", snippet.get("id"),
          "object_type", "query_snippet"));
      return Serializers.snippet(updated, service.userById(updated.get("user_id")));
    });
  }

  @Delete("/{snippetId}")
  public HttpResponse remove(String snippetId) {
    return answer(() -> {
      guardCsrf(null);
      var caller = caller();
      long id = identifier(snippetId);
      var snippet = requireSnippet(id);
      caller.requireAdminOrOwner(snippet.get("user_id"));
      store().delete(Store.QUERY_SNIPPETS, id);
      record(caller, Json.map("action", "delete", "object_id", id,
          "object_type", "query_snippet"));
      return null;
    });
  }

  private Map<String, Object> requireSnippet(long id) {
    var snippet = store().find(Store.QUERY_SNIPPETS, id);
    if (snippet == null) {
      throw Http.notFound();
    }
    return snippet;
  }
}

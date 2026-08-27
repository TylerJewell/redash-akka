package io.akka.redash.api;

import akka.http.javadsl.model.HttpEntity;
import akka.http.javadsl.model.HttpResponse;
import akka.http.javadsl.model.headers.RawHeader;
import akka.javasdk.annotations.Acl;
import akka.javasdk.annotations.http.Get;
import akka.javasdk.annotations.http.HttpEndpoint;
import akka.javasdk.annotations.http.Post;
import akka.javasdk.client.ComponentClient;
import io.akka.redash.application.Store;
import io.akka.redash.domain.Access;
import io.akka.redash.domain.Json;
import io.akka.redash.domain.Parameters;
import java.util.Map;

/**
 * `/api/query_results` — running text that is not a saved query, and reading a result back
 * as a file (SPEC-001 R76 to R90).
 *
 * <p>The two refusals here answer a **job document** carrying status 4 and the message
 * rather than the usual error body, and they carry 401 rather than 400 — which is what the
 * front end reads to decide what to draw.
 */
@Acl(allow = @Acl.Matcher(principal = Acl.Principal.ALL))
@HttpEndpoint("/api/query_results")
public class QueryResultsEndpoint extends ApiBase {

  static final long ONE_YEAR_SECONDS = 31_557_600L;

  public QueryResultsEndpoint(ComponentClient client) {
    super(new Service(client));
  }

  @Post("")
  public HttpResponse run(HttpEntity.Strict requestBody) {
    return answer(() -> {
      guardCsrf(requestBody);
      var caller = caller();
      caller.require("execute_query");
      var request = body(requestBody);
      var text = String.valueOf(request.get("query"));
      long maxAge = maxAgeOf(request);
      Object queryId = request.getOrDefault("query_id", "adhoc");
      var values = Json.asMap(request.get("parameters"));
      boolean autoLimit = Boolean.TRUE.equals(request.get("apply_auto_limit"));

      if (request.get("data_source_id") == null) {
        throw QueryRunning.refuse(QueryRunning.SELECT_DATA_SOURCE);
      }
      var dataSource = service.dataSourceById(request.get("data_source_id"));
      if (!service.hasAccessToDataSource(caller, dataSource, Access.NOT_VIEW_ONLY)) {
        throw QueryRunning.refuse(QueryRunning.NO_PERMISSION);
      }
      var parameterized = new Parameters(text, java.util.List.of(), service::dropdownValuesFor);
      return QueryRunning.run(service, caller, parameterized, values, dataSource, queryId,
          autoLimit, maxAge, this::record);
    });
  }

  /** `/api/query_results/<id>` and `/api/query_results/<id>.<filetype>`. */

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
  @Post("/{spec}")
  public HttpResponse downloadByPost(String spec, HttpEntity.Strict requestBody) {
    return answer(() -> {
      var caller = caller();
      caller.requireAny("view_query", "execute_query");
      throw Http.serverError();
    });
  }

  @Get("/{spec}")
  public HttpResponse download(String spec) {
    return answer(() -> {
      var caller = caller();
      caller.requireAny("view_query", "execute_query");
      var split = Results.split(spec);
      var result = store().find(Store.QUERY_RESULTS, identifier(split.identity()));
      if (result == null) {
        throw Http.notFound("No cached result found for this query.");
      }
      var dataSource = service.dataSourceById(result.get("data_source_id"));
      if (!canView(caller, dataSource)) {
        throw Http.forbidden();
      }
      if (caller.isApiUser()) {
        record(caller, Json.map("action", "api_get", "object_type", "query_result",
            "object_id", result.get("id"), "file_type", split.filetype(),
            "api_key", caller.apiKey()));
      }
      // A result addressed by its own identity can never change, so it is the only form the
      // source lets a client hold on to.
      return Results.respond(service, result, null, split.filetype(), true);
    });
  }
}

package io.akka.redash.api;

import akka.http.javadsl.model.HttpResponse;
import akka.http.javadsl.model.headers.RawHeader;
import io.akka.redash.domain.Json;
import java.util.Map;

/**
 * Turning a stored result into the four things a caller can ask for
 * (SPEC-001 R86 to R90).
 *
 * <p>The file type rides on the last segment of the path as a suffix — `12.csv` — rather
 * than as its own segment, so it is split here rather than in the route.
 */
final class Results {

  private Results() {}

  /** A path segment split into an identity and a file type, which defaults to JSON. */
  record Spec(String identity, String filetype) {}

  static Spec split(String segment) {
    int dot = segment.lastIndexOf('.');
    if (dot <= 0) {
      return new Spec(segment, "json");
    }
    var suffix = segment.substring(dot + 1);
    return switch (suffix) {
      case "json", "csv", "tsv", "xlsx" -> new Spec(segment.substring(0, dot), suffix);
      default -> new Spec(segment, "json");
    };
  }

  /**
   * The answer itself.
   *
   * @param query the saved query the result belongs to, which names the download
   * @param cacheable whether a client may keep it, which is true only for a result
   *     addressed by its own identity
   */
  static HttpResponse respond(Service service, Map<String, Object> result,
      Map<String, Object> query, String filetype, boolean cacheable) {
    var settings = service.settings();
    var org = service.currentOrg();
    var dateFormat = String.valueOf(ClientConfig.setting(settings, org, "date_format"));
    var timeFormat = String.valueOf(ClientConfig.setting(settings, org, "time_format"));
    var data = Json.asMap(result.get("data"));

    HttpResponse response = switch (filetype) {
      case "csv" -> Http.text(200, "text/csv; charset=UTF-8",
          Downloads.dsv(data, ',', dateFormat, timeFormat));
      case "tsv" -> Http.text(200, "text/tab-separated-values; charset=UTF-8",
          Downloads.dsv(data, '\t', dateFormat, timeFormat));
      case "xlsx" -> Http.bytes(200, Http.mediaTypeFor("xlsx"), Downloads.xlsx(data));
      // Whole, whoever is asking. The source abbreviates a result to its data and its
      // instant only where a *run* answers with one already cached; a request for a stored
      // result answers the row itself, which carries its identity, its hash, the text it
      // came from, the source it came from and how long it took.
      default -> Http.json(Json.map("query_result",
          Serializers.queryResult(result, false)));
    };

    for (String value : Http.contentDisposition(Downloads.filename(result, query, filetype))) {
      response = response.addHeader(RawHeader.create("Content-Disposition", value));
    }
    if (cacheable) {
      response = response.addHeader(RawHeader.create("Cache-Control",
          "private,max-age=" + QueryResultsEndpoint.ONE_YEAR_SECONDS));
    }
    return response;
  }
}
